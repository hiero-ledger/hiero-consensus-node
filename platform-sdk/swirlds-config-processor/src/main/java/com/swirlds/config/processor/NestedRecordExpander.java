// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigDefault;
import com.swirlds.config.api.ConfigProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Expands the record components of a config data record that hold a nested config data object (see {@link ConfigData})
 * into the properties they really define.
 * <p>
 * The source of a config data record is parsed on its own, so a nested record that is declared in another file can not
 * be resolved from that parse. This class therefore works on the element model of the compiler, which resolves a type
 * wherever it is declared and gives access to the annotations and the javadoc of the nested record.
 */
public final class NestedRecordExpander {

    private final Elements elements;

    private final Types types;

    public NestedRecordExpander(@NonNull final Elements elements, @NonNull final Types types) {
        this.elements = Objects.requireNonNull(elements, "elements must not be null");
        this.types = Objects.requireNonNull(types, "types must not be null");
    }

    /**
     * Replaces every property of the given definition that holds a nested config data object by the properties of that
     * nested record. A property of a nested config data object can not be set on its own, so it is not reported.
     *
     * @param definition  the definition that was parsed from the source of the config data record
     * @param typeElement the element of the config data record
     * @return the definition with all nested config data objects expanded
     */
    @NonNull
    public ConfigDataRecordDefinition expand(
            @NonNull final ConfigDataRecordDefinition definition, @NonNull final TypeElement typeElement) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(typeElement, "typeElement must not be null");

        final Map<String, RecordComponentElement> componentsByName = new HashMap<>();
        ElementFilter.recordComponentsIn(typeElement.getEnclosedElements())
                .forEach(component ->
                        componentsByName.put(component.getSimpleName().toString(), component));

        final Set<ConfigDataPropertyDefinition> expanded = new LinkedHashSet<>();
        for (final ConfigDataPropertyDefinition property : definition.propertyDefinitions()) {
            final RecordComponentElement component = componentsByName.get(property.fieldName());
            final TypeElement nestedRecord = component == null ? null : asNestedConfigDataObject(component);
            if (nestedRecord == null) {
                expanded.add(property);
            } else {
                expanded.addAll(expandNested(
                        nestedRecord,
                        property.name(),
                        property.fieldName(),
                        collectDefaultOverrides(component, property.name()),
                        new HashSet<>()));
            }
        }
        // the parsed definition holds the properties in an unordered set, so the generated constants and documentation
        // are ordered by the property name to keep them stable and reviewable
        final Set<ConfigDataPropertyDefinition> ordered = expanded.stream()
                .sorted(Comparator.comparing(ConfigDataPropertyDefinition::name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new ConfigDataRecordDefinition(
                definition.packageName(), definition.simpleClassName(), definition.configDataName(), ordered);
    }

    /**
     * Collects the properties of the given nested config data object.
     *
     * @param recordElement     the element of the nested record
     * @param namePrefix        the full name of the property that holds the nested record
     * @param fieldName         the name of the record component of the config data record that leads to the nested
     *                          record, used to link the generated constant to the source
     * @param defaultOverrides  the default values defined by {@link ConfigDefault}, keyed by the full property name
     * @param visitedTypes      the nested records that are currently being expanded, to detect a cycle
     * @return the properties of the nested record and of all records below it
     */
    @NonNull
    private Set<ConfigDataPropertyDefinition> expandNested(
            @NonNull final TypeElement recordElement,
            @NonNull final String namePrefix,
            @NonNull final String fieldName,
            @NonNull final Map<String, String> defaultOverrides,
            @NonNull final Set<String> visitedTypes) {
        final String qualifiedName = recordElement.getQualifiedName().toString();
        if (!visitedTypes.add(qualifiedName)) {
            throw new IllegalArgumentException(
                    "Circular reference detected for record type '" + qualifiedName + "' at '" + namePrefix + "'");
        }

        try {
            final Set<ConfigDataPropertyDefinition> properties = new LinkedHashSet<>();
            for (final RecordComponentElement component :
                    ElementFilter.recordComponentsIn(recordElement.getEnclosedElements())) {
                final String propertyName = createPropertyName(namePrefix, getPropertyNameSegment(component));
                final TypeElement nestedRecord = asNestedConfigDataObject(component);
                if (nestedRecord != null) {
                    final Map<String, String> merged = new HashMap<>(collectDefaultOverrides(component, propertyName));
                    // an override of an enclosing config data object wins over one that is declared closer
                    merged.putAll(defaultOverrides);
                    properties.addAll(expandNested(nestedRecord, propertyName, fieldName, merged, visitedTypes));
                } else {
                    properties.add(new ConfigDataPropertyDefinition(
                            fieldName,
                            propertyName,
                            component.asType().toString(),
                            defaultOverrides.getOrDefault(propertyName, getDefaultValue(component)),
                            getDescription(component)));
                }
            }
            return properties;
        } finally {
            visitedTypes.remove(qualifiedName);
        }
    }

    /**
     * Returns the element of the nested config data object that the given record component holds.
     *
     * @param component the record component
     * @return the element of the nested config data object, or null if the component does not hold one
     */
    @Nullable
    private TypeElement asNestedConfigDataObject(@NonNull final RecordComponentElement component) {
        final Element element = types.asElement(component.asType());
        if (element instanceof final TypeElement typeElement
                && element.getKind() == ElementKind.RECORD
                && typeElement.getAnnotation(ConfigData.class) != null) {
            return typeElement;
        }
        return null;
    }

    /**
     * Collects the default values that the {@link ConfigDefault} annotations of the given record component define,
     * keyed by the full name of the property they apply to.
     *
     * @param component  the record component that holds a nested config data object
     * @param namePrefix the full name of that record component
     * @return the default values
     */
    @NonNull
    private static Map<String, String> collectDefaultOverrides(
            @NonNull final RecordComponentElement component, @NonNull final String namePrefix) {
        final Map<String, String> overrides = new HashMap<>();
        for (final ConfigDefault configDefault : component.getAnnotationsByType(ConfigDefault.class)) {
            overrides.put(createPropertyName(namePrefix, configDefault.property()), configDefault.defaultValue());
        }
        return overrides;
    }

    /**
     * Returns the name that the given record component has in the config, which is the name defined by
     * {@link ConfigProperty#value()} if one is defined and the name of the component otherwise.
     *
     * @param component the record component
     * @return the name of the property without any prefix
     */
    @NonNull
    private static String getPropertyNameSegment(@NonNull final RecordComponentElement component) {
        return Optional.ofNullable(component.getAnnotation(ConfigProperty.class))
                .map(ConfigProperty::value)
                .filter(name -> !name.isBlank())
                .orElseGet(() -> component.getSimpleName().toString());
    }

    @NonNull
    private static String getDefaultValue(@NonNull final RecordComponentElement component) {
        return Optional.ofNullable(component.getAnnotation(ConfigProperty.class))
                .map(ConfigProperty::defaultValue)
                .orElse(ConfigProperty.UNDEFINED_DEFAULT_VALUE);
    }

    @NonNull
    private String getDescription(@NonNull final RecordComponentElement component) {
        return Optional.ofNullable(elements.getDocComment(component))
                .map(String::strip)
                .orElse("");
    }

    @NonNull
    private static String createPropertyName(@NonNull final String prefix, @NonNull final String name) {
        if (prefix.isBlank()) {
            return name;
        }
        return prefix + "." + name;
    }
}

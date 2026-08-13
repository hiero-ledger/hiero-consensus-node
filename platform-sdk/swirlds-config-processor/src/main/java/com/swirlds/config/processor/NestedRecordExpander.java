// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigDefault;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.NestedConfig;
import com.swirlds.config.processor.antlr.AntlrUtils;
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
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Expands the record components of a config data record (see {@link ConfigData}) that hold a nested config data object
 * (see {@link NestedConfig}) into the properties they really define.
 * <p>
 * The source of a config data record is parsed on its own, so a nested record that is declared in another file can not
 * be resolved from that parse. This class therefore works on the element model of the compiler, which resolves a type
 * wherever it is declared and gives access to the annotations and the javadoc of the nested record.
 * <p>
 * A nested config data object is never a config data type of its own, so the processor does not run over it and it gets
 * neither its own constants class nor its own documentation. Its properties are reported here instead, under the full
 * names they have below the config data record that uses them.
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
            final TypeElement nestedRecord = component == null ? null : asNestedConfig(component);
            if (nestedRecord == null) {
                expanded.add(property);
            } else {
                expanded.addAll(expandNested(
                        nestedRecord,
                        property.name(),
                        property.fieldName(),
                        collectDefaultOverrides(component, property.name(), nestedRecord),
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
            // a record component carries no javadoc of its own, the description of a property comes from the @param
            // tag of the record that declares it
            final Map<String, String> descriptions = getJavadocParams(recordElement);

            final Set<ConfigDataPropertyDefinition> properties = new LinkedHashSet<>();
            for (final RecordComponentElement component :
                    ElementFilter.recordComponentsIn(recordElement.getEnclosedElements())) {
                final String propertyName = createPropertyName(namePrefix, getPropertyNameSegment(component));
                final TypeElement nestedRecord = asNestedConfig(component);
                if (nestedRecord != null) {
                    final Map<String, String> merged =
                            new HashMap<>(collectDefaultOverrides(component, propertyName, nestedRecord));
                    // an override of an enclosing config data object wins over one that is declared closer
                    merged.putAll(defaultOverrides);
                    properties.addAll(expandNested(nestedRecord, propertyName, fieldName, merged, visitedTypes));
                } else {
                    properties.add(new ConfigDataPropertyDefinition(
                            fieldName,
                            propertyName,
                            component.asType().toString(),
                            defaultOverrides.getOrDefault(propertyName, getDefaultValue(component)),
                            descriptions.getOrDefault(component.getSimpleName().toString(), "")));
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
    private TypeElement asNestedConfig(@NonNull final RecordComponentElement component) {
        final Element element = types.asElement(component.asType());
        if (element instanceof final TypeElement typeElement
                && element.getKind() == ElementKind.RECORD
                && typeElement.getAnnotation(NestedConfig.class) != null) {
            return typeElement;
        }
        return null;
    }

    /**
     * Collects the default values that the {@link ConfigDefault} annotations of the given record component define,
     * keyed by the full name of the property they apply to.
     *
     * @param component    the record component that holds a nested config data object
     * @param namePrefix   the full name of that record component
     * @param nestedRecord the element of the nested config data object
     * @return the default values
     */
    @NonNull
    private Map<String, String> collectDefaultOverrides(
            @NonNull final RecordComponentElement component,
            @NonNull final String namePrefix,
            @NonNull final TypeElement nestedRecord) {
        final Map<String, String> overrides = new HashMap<>();
        for (final ConfigDefault configDefault : component.getAnnotationsByType(ConfigDefault.class)) {
            validateAddressesAProperty(configDefault, namePrefix, nestedRecord);
            overrides.put(createPropertyName(namePrefix, configDefault.property()), configDefault.defaultValue());
        }
        return overrides;
    }

    /**
     * Checks that the given {@link ConfigDefault} addresses a single property that really exists, so that a typo is
     * reported at compile time rather than silently documenting the default that the nested record defines itself. The
     * same check is done again when the configuration is created, since a config data record may be compiled without
     * this processor.
     *
     * @param configDefault the annotation
     * @param namePrefix    the full name of the record component that holds the nested config data object
     * @param nestedRecord  the element of the nested config data object
     */
    private void validateAddressesAProperty(
            @NonNull final ConfigDefault configDefault,
            @NonNull final String namePrefix,
            @NonNull final TypeElement nestedRecord) {
        TypeElement owner = nestedRecord;
        String prefix = namePrefix;
        for (final String segment : configDefault.property().split("\\.", -1)) {
            final RecordComponentElement match = owner == null ? null : findComponent(owner, segment);
            if (match == null) {
                throw new IllegalArgumentException("The " + ConfigDefault.class.getSimpleName() + " for '"
                        + createPropertyName(namePrefix, configDefault.property())
                        + "' does not match any property. Known properties: " + getPropertyNames(prefix, owner));
            }
            prefix = createPropertyName(prefix, segment);
            owner = asNestedConfig(match);
        }
        if (owner != null) {
            throw new IllegalArgumentException("The " + ConfigDefault.class.getSimpleName() + " for '"
                    + createPropertyName(namePrefix, configDefault.property())
                    + "' addresses a nested config data object instead of a single property. Address one of its"
                    + " properties: " + getPropertyNames(prefix, owner));
        }
    }

    @Nullable
    private static RecordComponentElement findComponent(
            @NonNull final TypeElement recordElement, @NonNull final String segment) {
        return ElementFilter.recordComponentsIn(recordElement.getEnclosedElements()).stream()
                .filter(candidate -> Objects.equals(segment, getPropertyNameSegment(candidate)))
                .findAny()
                .orElse(null);
    }

    @NonNull
    private static Set<String> getPropertyNames(@NonNull final String prefix, @Nullable final TypeElement owner) {
        if (owner == null) {
            return Set.of();
        }
        return ElementFilter.recordComponentsIn(owner.getEnclosedElements()).stream()
                .map(candidate -> createPropertyName(prefix, getPropertyNameSegment(candidate)))
                .collect(Collectors.toCollection(TreeSet::new));
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

    /**
     * Returns the description of every property of the given record, keyed by the name of the record component, taken
     * from the {@code @param} tags of the javadoc of the record.
     * <p>
     * A record component has no javadoc of its own, so {@link Elements#getDocComment(Element)} is asked for the record
     * and not for the component. The comment is handed back to the same {@code @param} extraction the parser of the
     * config data record uses, which needs the raw form of the comment, while {@code getDocComment} returns its content
     * with the leading asterisks already removed.
     *
     * @param recordElement the element of the record
     * @return the description of every property, keyed by the name of the record component
     */
    @NonNull
    private Map<String, String> getJavadocParams(@NonNull final TypeElement recordElement) {
        final String docComment = elements.getDocComment(recordElement);
        if (docComment == null || docComment.isBlank()) {
            return Map.of();
        }
        final StringBuilder rawJavadoc = new StringBuilder("/**");
        docComment.lines().forEach(line -> rawJavadoc.append("\n *").append(line));
        rawJavadoc.append("\n */");
        return AntlrUtils.getJavaDocParams(rawJavadoc.toString());
    }

    @NonNull
    private static String createPropertyName(@NonNull final String prefix, @NonNull final String name) {
        if (prefix.isBlank()) {
            return name;
        }
        return prefix + "." + name;
    }
}

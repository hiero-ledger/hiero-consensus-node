// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.NestedConfig;
import com.swirlds.config.processor.antlr.AntlrUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
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
 * <p>
 * The runtime is the authority on whether a config data record is legal: a converter and a registered config data type
 * are runtime registrations that no processor can see, so the rules that depend on them are checked only when the
 * configuration is created. What is checked here are the rules that follow from the source alone, and those are checked
 * exactly as the runtime checks them, so that the generated constants and documentation never describe a record the
 * runtime refuses to build.
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
     * nested record. A component that holds a nested config data object can not be set on its own, so it is not
     * reported as a property.
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

        // The record that is being processed carries ConfigData, since that is what the processor runs over, so it must
        // not carry NestedConfig as well. Checking only the components would let the mistake through for the record it
        // is declared on and generate constants and documentation that the runtime then refuses to use.
        if (typeElement.getAnnotation(NestedConfig.class) != null) {
            throw new IllegalArgumentException(bothAnnotationsMessage(typeElement));
        }

        final Map<String, RecordComponentElement> componentsByName = new HashMap<>();
        ElementFilter.recordComponentsIn(typeElement.getEnclosedElements())
                .forEach(component ->
                        componentsByName.put(component.getSimpleName().toString(), component));

        final Set<ConfigDataPropertyDefinition> expanded = new LinkedHashSet<>();
        for (final ConfigDataPropertyDefinition property : definition.propertyDefinitions()) {
            final RecordComponentElement component = componentsByName.get(property.fieldName());
            if (component == null) {
                expanded.add(property);
                continue;
            }
            final TypeElement nestedRecord = asNestedConfig(component);
            // the name of every property is taken from the element model rather than from the parsed source, so that
            // the value of a ConfigProperty is the one the compiler evaluated and the generated constants and
            // documentation describe the properties the runtime really reads
            final String propertyName = propertyName(definition, component);
            if (nestedRecord == null) {
                validateIsNotACollectionOfNestedRecords(component);
                expanded.add(property.withName(propertyName));
            } else {
                validateNestedComponent(component);
                expanded.addAll(expandNested(nestedRecord, propertyName, property.fieldName(), new HashSet<>()));
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
     * @param recordElement the element of the nested record
     * @param namePrefix    the full name of the property that holds the nested record
     * @param fieldName     the name of the record component of the config data record that leads to the nested record,
     *                      used to link the generated constant to the source
     * @param visitedTypes  the nested records that are currently being expanded, to detect a cycle
     * @return the properties of the nested record and of all records below it
     */
    @NonNull
    private Set<ConfigDataPropertyDefinition> expandNested(
            @NonNull final TypeElement recordElement,
            @NonNull final String namePrefix,
            @NonNull final String fieldName,
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
                    validateNestedComponent(component);
                    properties.addAll(expandNested(nestedRecord, propertyName, fieldName, visitedTypes));
                } else {
                    validateIsNotACollectionOfNestedRecords(component);
                    properties.add(new ConfigDataPropertyDefinition(
                            fieldName,
                            propertyName,
                            component.asType().toString(),
                            getDefaultValue(component),
                            descriptions.getOrDefault(component.getSimpleName().toString(), ""),
                            // the property is declared by the nested record, not by the config data record that uses
                            // it, so that is what the generated constant refers to
                            new ConfigDataPropertyDefinition.DeclaringComponent(
                                    recordElement.getQualifiedName().toString(),
                                    component.getSimpleName().toString())));
                }
            }
            return properties;
        } finally {
            visitedTypes.remove(qualifiedName);
        }
    }

    /**
     * Returns the element of the nested config data object that the given record component holds.
     * <p>
     * The type is erased first, since that is what the runtime sees: it decides the same question from
     * {@link java.lang.reflect.RecordComponent#getType()}, which is the erasure. A component whose type is a type
     * variable like {@code T extends Leaf} is therefore recognised here as well, and then rejected by
     * {@link #validateNestedComponent(RecordComponentElement)} rather than silently documented as a single property.
     *
     * @param component the record component
     * @return the element of the nested config data object, or null if the component does not hold one
     */
    @Nullable
    private TypeElement asNestedConfig(@NonNull final RecordComponentElement component) {
        final Element element = types.asElement(types.erasure(component.asType()));
        if (element instanceof final TypeElement typeElement
                && element.getKind() == ElementKind.RECORD
                && typeElement.getAnnotation(NestedConfig.class) != null) {
            // the two annotations describe the two different roles a config record can have and are mutually
            // exclusive: a nested config data object takes its prefix from the component that holds it, so the prefix
            // of the ConfigData would never be used
            if (typeElement.getAnnotation(ConfigData.class) != null) {
                throw new IllegalArgumentException(
                        bothAnnotationsMessage(typeElement) + ", reached through '" + component.getSimpleName() + "'");
            }
            return typeElement;
        }
        return null;
    }

    /**
     * Checks the rules that a component holding a nested config data object has to follow, exactly as the runtime
     * checks them.
     *
     * @param component the nested record component
     */
    private static void validateNestedComponent(@NonNull final RecordComponentElement component) {
        // The properties of a group follow from its type, and the type has to be written out so that this processor
        // and the runtime provably arrive at the same set: this reads the declared type while the runtime reads the
        // erasure, and only a concrete record type makes the two the same.
        if (!(component.asType() instanceof final DeclaredType declaredType)
                || !declaredType.getTypeArguments().isEmpty()) {
            throw new IllegalArgumentException("Can not handle the record property '" + component.getSimpleName()
                    + "' since it declares the nested config data object as '" + component.asType()
                    + "' instead of naming the record type. The properties of a group follow from its type, so the type"
                    + " has to be written out");
        }

        // A group has no value of its own that a config source could define, so there is nothing a default value of
        // the component could mean. Without this the value would silently be dropped here while the runtime rejects
        // the same declaration.
        final String defaultValue = getDefaultValue(component);
        if (!Objects.equals(ConfigProperty.UNDEFINED_DEFAULT_VALUE, defaultValue)) {
            throw new IllegalArgumentException("Can not use a default value for the property '"
                    + component.getSimpleName() + "' since '" + component.asType()
                    + "' is a nested config data object, which is a group of properties rather than a value. Define the"
                    + " default values of its properties instead");
        }
    }

    /**
     * Returns the message for a record that carries both {@link ConfigData} and {@link NestedConfig}. The two
     * annotations describe the two different roles a config record can have and are mutually exclusive: a nested config
     * data object takes its prefix from the component that holds it, so the prefix of the {@link ConfigData} would
     * never be used.
     *
     * @param typeElement the element of the record
     * @return the message
     */
    @NonNull
    private static String bothAnnotationsMessage(@NonNull final TypeElement typeElement) {
        return "The record '" + typeElement.getQualifiedName() + "' is annotated with both "
                + ConfigData.class.getSimpleName() + " and " + NestedConfig.class.getSimpleName()
                + ", which are mutually exclusive. Remove one of the two";
    }

    /**
     * Checks that the given record component is not a {@link List} or {@link Set} of nested config data objects.
     * <p>
     * A nested config data object is a group of properties rather than a value, and the name of a group comes from the
     * single component that holds it. A collection has no such name for each of its elements, so there is no property
     * name a config source could use. Without this it would be documented as a single settable property and a constant
     * would be generated for it, while the configuration can never populate it. The runtime rejects it as well, since a
     * config data record may be compiled without this processor.
     *
     * @param component the record component
     */
    private void validateIsNotACollectionOfNestedRecords(@NonNull final RecordComponentElement component) {
        if (!(component.asType() instanceof final DeclaredType declaredType)) {
            return;
        }
        final String rawTypeName = types.erasure(declaredType).toString();
        if (!List.class.getName().equals(rawTypeName) && !Set.class.getName().equals(rawTypeName)) {
            return;
        }
        final List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
        if (typeArguments.size() != 1) {
            return;
        }

        if (types.asElement(types.erasure(typeArguments.getFirst())) instanceof final TypeElement elementType
                && elementType.getKind() == ElementKind.RECORD
                && elementType.getAnnotation(NestedConfig.class) != null) {
            throw new IllegalArgumentException("Can not handle the property '" + component.getSimpleName()
                    + "' since '" + rawTypeName + "' holds '" + elementType.getQualifiedName()
                    + "', which is annotated with " + NestedConfig.class.getSimpleName()
                    + ". A nested config data object is a group of properties that takes its name from the single"
                    + " component holding it, so there is no property name for an element of a collection. Use a"
                    + " component of that type per group, or a type with a registered converter as the element type");
        }
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

    /**
     * Returns the full name that the given record component of the given config data record has in the config.
     *
     * @param definition the definition of the config data record that declares the component
     * @param component  the record component
     * @return the full name of the property
     */
    @NonNull
    private static String propertyName(
            @NonNull final ConfigDataRecordDefinition definition, @NonNull final RecordComponentElement component) {
        return createPropertyName(definition.configDataName(), getPropertyNameSegment(component));
    }

    @NonNull
    private static String createPropertyName(@NonNull final String prefix, @NonNull final String name) {
        if (prefix.isBlank()) {
            return name;
        }
        return prefix + "." + name;
    }
}

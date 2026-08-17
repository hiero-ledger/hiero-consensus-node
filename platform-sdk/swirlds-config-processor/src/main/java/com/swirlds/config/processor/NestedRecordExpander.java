// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigDefault;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.NestedConfig;
import com.swirlds.config.processor.antlr.AntlrUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
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
            final TypeElement nestedRecord = component == null ? null : asNestedConfig(component);
            if (nestedRecord == null) {
                if (component != null) {
                    validateHasNoConfigDefault(component);
                    validateIsNotACollectionOfNestedRecords(component);
                }
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
                    validateHasNoConfigDefault(component);
                    validateIsNotACollectionOfNestedRecords(component);
                    properties.add(new ConfigDataPropertyDefinition(
                            fieldName,
                            propertyName,
                            getReportedTypeName(component),
                            defaultOverrides.getOrDefault(propertyName, getDefaultValue(component)),
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
     * variable like {@code T extends Leaf} would otherwise be taken for a single property here while the runtime reads
     * the properties of {@code Leaf}, so the generated constant would name a property that is never read.
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
     * Returns the name of the type that is documented for the given record component.
     * <p>
     * A type variable is erased, since the components of a nested config data object are read from its declaration and
     * a bare name like {@code T} describes nothing. The erasure is also the type the runtime converts the value to, so
     * a bound like {@code T extends Duration} is documented as {@code java.time.Duration} and an unbounded one as
     * {@code java.lang.Object}. Any other type is reported as it is written, which keeps the type arguments of a
     * generic type like {@code java.util.Set<java.time.Duration>}.
     *
     * @param component the record component
     * @return the name of the type to document
     */
    @NonNull
    private String getReportedTypeName(@NonNull final RecordComponentElement component) {
        final TypeMirror componentType = component.asType();
        return componentType.getKind() == TypeKind.TYPEVAR
                ? types.erasure(componentType).toString()
                : componentType.toString();
    }

    /**
     * Checks that the given record component, which does not hold a nested config data object, defines no
     * {@link ConfigDefault}. Such an annotation would silently have no effect, while the value that it was meant to
     * define belongs into the {@link ConfigProperty} of the component.
     *
     * @param component the record component
     */
    private static void validateHasNoConfigDefault(@NonNull final RecordComponentElement component) {
        if (component.getAnnotationsByType(ConfigDefault.class).length > 0) {
            throw new IllegalArgumentException("Can not use " + ConfigDefault.class.getSimpleName()
                    + " for the property '" + component.getSimpleName()
                    + "' since it is not a nested config data object. Use " + ConfigProperty.class.getSimpleName()
                    + " to define a default value for it");
        }
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
     * Collects the default values that the {@link ConfigDefault} annotations of the given record component define,
     * keyed by the full name of the property they apply to.
     * <p>
     * An annotation that addresses a property that does not exist is a typo that has to be reported at compile time
     * rather than silently documenting the default that the nested record defines itself. The same checks are done
     * again when the configuration is created, since a config data record may be compiled without this processor.
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
            final String addressed = createPropertyName(namePrefix, configDefault.property());
            final List<RecordComponentElement> matches =
                    findProperties(nestedRecord, configDefault.property(), new HashSet<>());

            final List<RecordComponentElement> leaves = matches.stream()
                    .filter(match -> asNestedConfig(match) == null)
                    .toList();
            if (leaves.size() > 1) {
                throw new IllegalArgumentException("The " + ConfigDefault.class.getSimpleName() + " for '" + addressed
                        + "' matches more than one property, since a dot both separates the segments of a property of a"
                        + " nested config data object and can be part of a single name. Rename one of them.");
            }
            if (leaves.isEmpty()) {
                throw new IllegalArgumentException(
                        matches.isEmpty()
                                ? "The " + ConfigDefault.class.getSimpleName() + " for '" + addressed
                                        + "' does not match any property. Known properties: "
                                        + getPropertyNames(namePrefix, nestedRecord, new HashSet<>())
                                : "The " + ConfigDefault.class.getSimpleName() + " for '" + addressed
                                        + "' addresses a nested config data object instead of a single property. Address one of"
                                        + " its properties: "
                                        + getPropertyNames(
                                                addressed, asNestedConfig(matches.getFirst()), new HashSet<>()));
            }

            // the marker means "no default is defined" everywhere a default value is read, so a ConfigDefault carrying
            // it is indistinguishable from not writing the annotation. The runtime rejects it as well, since a config
            // data record may be compiled without this processor
            if (Objects.equals(ConfigProperty.UNDEFINED_DEFAULT_VALUE, configDefault.defaultValue())) {
                throw new IllegalArgumentException("The " + ConfigDefault.class.getSimpleName() + " for '" + addressed
                        + "' uses " + ConfigProperty.class.getSimpleName()
                        + ".UNDEFINED_DEFAULT_VALUE as its default value, which means that no default is defined."
                        + " Remove the annotation to leave the default of the property alone, or use "
                        + ConfigProperty.class.getSimpleName()
                        + ".NULL_DEFAULT_VALUE to default the property to null.");
            }

            // two annotations addressing the same property simply drop one of the two values, so there is no reading
            // of that which is not a mistake
            final String clashing = overrides.put(addressed, configDefault.defaultValue());
            if (clashing != null) {
                throw new IllegalArgumentException("There is more than one " + ConfigDefault.class.getSimpleName()
                        + " for the property '" + addressed + "', defining '" + clashing + "' and '"
                        + configDefault.defaultValue() + "'. Remove one of them.");
            }
        }
        return overrides;
    }

    /**
     * Finds the properties that the given path addresses below the given nested config data object.
     * <p>
     * A dot has two meanings that can not be told apart by looking at the path alone: it separates the segments of a
     * property of a more deeply nested record, and it may be part of a single name that {@link ConfigProperty#value()}
     * defines. Every reading of the path is therefore followed, so that a name containing a dot is addressable and an
     * ambiguous path can be reported as such instead of one reading silently winning.
     *
     * @param recordElement the element to search in
     * @param path          the path to resolve, relative to the given record
     * @param visitedTypes  the records that are currently being searched, to stop at a cycle
     * @return the components the path addresses, which is empty when it addresses none
     */
    @NonNull
    private List<RecordComponentElement> findProperties(
            @Nullable final TypeElement recordElement,
            @NonNull final String path,
            @NonNull final Set<String> visitedTypes) {
        // a cycle is reported by expandNested with a message that names the offending type, so here it is enough to
        // stop walking
        if (recordElement == null
                || !visitedTypes.add(recordElement.getQualifiedName().toString())) {
            return List.of();
        }
        try {
            final List<RecordComponentElement> matches = new ArrayList<>();
            for (final RecordComponentElement candidate :
                    ElementFilter.recordComponentsIn(recordElement.getEnclosedElements())) {
                final String segment = getPropertyNameSegment(candidate);
                if (Objects.equals(segment, path)) {
                    matches.add(candidate);
                } else if (path.startsWith(segment + ".")) {
                    matches.addAll(findProperties(
                            asNestedConfig(candidate), path.substring(segment.length() + 1), visitedTypes));
                }
            }
            return matches;
        } finally {
            visitedTypes.remove(recordElement.getQualifiedName().toString());
        }
    }

    /**
     * Returns the full names of every property below the given nested config data object, so that a
     * {@link ConfigDefault} that addresses none of them can report what it could have addressed. Only the properties
     * that a default value can be defined for are listed, so a component that holds a nested config data object is
     * replaced by the properties below it.
     *
     * @param prefix       the full name of the given record
     * @param owner        the nested config data object
     * @param visitedTypes the records that are currently being listed, to stop at a cycle
     * @return the full names of the properties below the given record
     */
    @NonNull
    private Set<String> getPropertyNames(
            @NonNull final String prefix, @Nullable final TypeElement owner, @NonNull final Set<String> visitedTypes) {
        if (owner == null || !visitedTypes.add(owner.getQualifiedName().toString())) {
            return Set.of();
        }
        try {
            final Set<String> names = new TreeSet<>();
            for (final RecordComponentElement candidate :
                    ElementFilter.recordComponentsIn(owner.getEnclosedElements())) {
                final String name = createPropertyName(prefix, getPropertyNameSegment(candidate));
                final TypeElement nested = asNestedConfig(candidate);
                if (nested == null) {
                    names.add(name);
                } else {
                    names.addAll(getPropertyNames(name, nested, visitedTypes));
                }
            }
            return names;
        } finally {
            visitedTypes.remove(owner.getQualifiedName().toString());
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

    @NonNull
    private static String createPropertyName(@NonNull final String prefix, @NonNull final String name) {
        if (prefix.isBlank()) {
            return name;
        }
        return prefix + "." + name;
    }
}

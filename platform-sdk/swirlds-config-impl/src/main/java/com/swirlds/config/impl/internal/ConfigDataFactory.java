// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigDefault;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.NestedConfig;
import com.swirlds.config.extensions.reflection.ConfigReflectionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
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

/**
 * Internal factory for config data objects. See {@link Configuration#getConfigData(Class)} for a detailed description
 * on config data objects.
 */
class ConfigDataFactory {

    /**
     * The configuration that is internally used to fill the properties of the config data instances.
     */
    private final Configuration configuration;

    /**
     * The converter service that is used to convert raw values from the config to custom data types.
     */
    private final ConverterService converterService;

    ConfigDataFactory(@NonNull final Configuration configuration, @NonNull final ConverterService converterService) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.converterService = Objects.requireNonNull(converterService, "converterService must not be null");
    }

    @NonNull
    <T extends Record> T createConfigInstance(@NonNull final Class<T> type)
            throws InvocationTargetException, InstantiationException, IllegalAccessException {
        validateIsRecord(type);

        if (ConfigReflectionUtils.isNestedConfig(type)) {
            throw new IllegalArgumentException("Can not create config instance for '" + type + "' since it is annotated"
                    + " with " + NestedConfig.class.getSimpleName() + ", which means it is a group of properties that"
                    + " is only used as a record component of a config data object and never registered on its own");
        }
        if (!type.isAnnotationPresent(ConfigData.class)) {
            throw new IllegalArgumentException("Can not create config instance for '" + type + "' since "
                    + ConfigData.class.getName() + "' " + "annotation is missing");
        }

        final String namePrefix = getNamePrefix(type);
        return instantiateRecord(namePrefix, type, new HashSet<>(), new DefaultValueOverrides());
    }

    private void validateIsRecord(@NonNull final Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");

        if (!type.isRecord()) {
            throw new IllegalArgumentException(
                    "Can not create config instance for '" + type + "' since it is not record");
        }
        if (!ConfigReflectionUtils.isPublic(type)) {
            throw new IllegalArgumentException(
                    "Can not create config instance for '" + type + "' since it is not public");
        }
        if (type.getConstructors().length != 1) {
            throw new IllegalArgumentException(
                    "Can not create config instance for '" + type + "' since it has not exactly 1 constructor");
        }
    }

    @SuppressWarnings("unchecked")
    @NonNull
    private <T extends Record> T instantiateRecord(
            @NonNull final String namePrefix,
            @NonNull final Class<T> type,
            @NonNull final Set<Class<?>> circularRefStack,
            @NonNull final DefaultValueOverrides defaultValueOverrides)
            throws InvocationTargetException, InstantiationException, IllegalAccessException {
        if (!circularRefStack.add(type)) {
            throw new IllegalStateException("Circular reference detected for record type '" + type + "'");
        }

        try {
            final RecordComponent[] recordComponents = type.getRecordComponents();
            final Object[] paramValues = new Object[recordComponents.length];

            for (int i = 0; i < recordComponents.length; i++) {
                paramValues[i] = getValueForRecordComponent(
                        namePrefix, recordComponents[i], circularRefStack, defaultValueOverrides);
            }

            final Constructor<T> constructor = (Constructor<T>) type.getConstructors()[0];
            return constructor.newInstance(paramValues);
        } finally {
            circularRefStack.remove(type);
        }
    }

    @Nullable
    private Object getValueForRecordComponent(
            @NonNull final String namePrefix,
            @NonNull final RecordComponent component,
            @NonNull final Set<Class<?>> circularRefStack,
            @NonNull final DefaultValueOverrides defaultValueOverrides) {
        Objects.requireNonNull(component, "component must not be null");
        final String name = createPropertyName(namePrefix, component);
        final Class<?> valueType = component.getType();

        final boolean isNestedRecord = validateComponentSchema(name, component);

        // a value that is defined by an enclosing ConfigDefault annotation takes precedence over the default value
        // that the property defines itself
        final String overriddenDefaultValue = defaultValueOverrides.get(name);
        final String rawDefaultValue = overriddenDefaultValue != null
                ? overriddenDefaultValue
                : getRawDefaultValue(component).orElse(null);

        if (isNestedRecord) {
            return getValueForNestedRecordComponent(
                    name, component, circularRefStack, defaultValueOverrides, rawDefaultValue);
        }

        if (rawDefaultValue != null) {
            if (Objects.equals(List.class, component.getType())) {
                final Class<?> genericType = getGenericListType(component);
                return configuration.getValues(name, genericType, getDefaultValues(component, rawDefaultValue));
            }
            if (Objects.equals(Set.class, component.getType())) {
                final Class<?> genericType = getGenericSetType(component);
                return configuration.getValueSet(name, genericType, getDefaultValueSet(component, rawDefaultValue));
            }
            return configuration.getValue(name, valueType, getDefaultValue(component, rawDefaultValue));
        } else {
            if (Objects.equals(List.class, component.getType())) {
                final Class<?> genericType = getGenericListType(component);
                return configuration.getValues(name, genericType);
            }
            if (Objects.equals(Set.class, component.getType())) {
                final Class<?> genericType = getGenericSetType(component);
                return configuration.getValueSet(name, genericType);
            }
            if (configuration.isListValue(name)) {
                return configuration.getValues(name, valueType);
            } else {
                return configuration.getValue(name, valueType);
            }
        }
    }

    /**
     * Creates the value for a record component whose type is a nested config data object.
     *
     * @param name                  the full name of the property
     * @param component             the record component
     * @param circularRefStack      the record types that are currently being instantiated
     * @param defaultValueOverrides the collected {@link ConfigDefault} values
     * @param rawDefaultValue       the raw default value of the component, or null if none is defined
     * @return the created record, or null if the component defaults to null
     */
    @Nullable
    private Object getValueForNestedRecordComponent(
            @NonNull final String name,
            @NonNull final RecordComponent component,
            @NonNull final Set<Class<?>> circularRefStack,
            @NonNull final DefaultValueOverrides defaultValueOverrides,
            @Nullable final String rawDefaultValue) {
        // the declared default was already checked by validateComponentSchema, this also rejects an override that a
        // ConfigDefault of an enclosing record supplied
        validateNestedRecordDefaultValue(name, component, rawDefaultValue);

        // the prefix of a nested config data object is always the name of the property that holds it, so a prefix that
        // the record defines for its own use as a registered config data type is not used here
        final Class<? extends Record> recordType = component.getType().asSubclass(Record.class);

        if (Objects.equals(ConfigProperty.NULL_DEFAULT_VALUE, rawDefaultValue)
                && !isAnyPropertyDefined(name, component.getType())) {
            // The group is optional and nothing below it is defined by the config, so it stays null. It is only the
            // properties below the component that a config source can define, never the component itself, so the whole
            // group has to be asked about rather than the name of the component.
            //
            // Absent is the normal state of an optional group, so the group is still checked for the mistakes that
            // instantiating it would have reported. A group that is only declared wrongly would otherwise build
            // everywhere until a config defines one property below it.
            validateNestedSchema(name, component, recordType, circularRefStack);
            return null;
        }

        validateIsRecord(recordType);
        defaultValueOverrides.add(resolveConfigDefaults(name, component, recordType));

        try {
            return instantiateRecord(name, recordType, circularRefStack, defaultValueOverrides);
        } catch (final InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Unable to instantiate record for '" + name + "'", e);
        }
    }

    /**
     * Checks everything about the given record component that follows from its declaration alone, so that the same
     * mistake is reported whether or not the config asks for the value below it. This is what lets an optional group
     * that is absent be checked exactly like one that is created.
     *
     * @param name      the full name of the property
     * @param component the record component
     * @return true if the component holds a nested config data object
     */
    private boolean validateComponentSchema(@NonNull final String name, @NonNull final RecordComponent component) {
        final Class<?> valueType = component.getType();

        final boolean isNestedRecord = isNestedRecord(valueType);
        if (valueType.isRecord() && !isNestedRecord && converterService.getConverterForType(valueType) == null) {
            throw new IllegalArgumentException("Can not handle the record property '" + name + "' since '" + valueType
                    + "' is neither annotated with " + NestedConfig.class.getSimpleName()
                    + ", which would make it a nested config data object, nor has a converter registered, which would"
                    + " make it a single property that is converted from one value");
        }
        if (isNestedRecord && converterService.getConverterForType(valueType) != null) {
            throw new IllegalArgumentException("Can not handle the record property '" + name + "' since '" + valueType
                    + "' is annotated with " + NestedConfig.class.getSimpleName() + " and also has a converter"
                    + " registered. A nested config data object is read property by property, so the converter would"
                    + " never be used. Remove one of the two");
        }
        if (isNestedRecord) {
            validateIsNotAConfigDataType(name, valueType);
        }
        if (component.getAnnotationsByType(ConfigDefault.class).length > 0 && !isNestedRecord) {
            throw new IllegalArgumentException("Can not use " + ConfigDefault.class.getSimpleName()
                    + " for the property '" + name + "' since '" + valueType
                    + "' is not a nested config data object. Use " + ConfigProperty.class.getSimpleName()
                    + " to define a default value for it");
        }
        if (isNestedRecord) {
            // The default that the component declares is checked here rather than only where it is used, since a group
            // that the config never asks for would otherwise keep an invalid declaration. A ConfigDefault of an
            // enclosing record can not reach a group, resolveConfigDefaults rejects that, so the declared default is
            // the only one there is to check.
            validateNestedRecordDefaultValue(
                    name, component, getRawDefaultValue(component).orElse(null));
        }
        return isNestedRecord;
    }

    /**
     * Checks that the given default value of a component that holds a nested config data object is one the component
     * can have. A nested config data object has no value of its own, so the only default it accepts is
     * {@link ConfigProperty#NULL_DEFAULT_VALUE}, which makes the whole group optional.
     *
     * @param name            the full name of the property
     * @param component       the nested record component
     * @param rawDefaultValue the raw default value of the component, or null if none is defined
     */
    private static void validateNestedRecordDefaultValue(
            @NonNull final String name,
            @NonNull final RecordComponent component,
            @Nullable final String rawDefaultValue) {
        if (rawDefaultValue != null && !Objects.equals(ConfigProperty.NULL_DEFAULT_VALUE, rawDefaultValue)) {
            throw new IllegalArgumentException("Can not use a default value for the property '" + name + "' since '"
                    + component.getType() + "' is a nested config data object. Use "
                    + ConfigDefault.class.getSimpleName() + " to define the default values of its properties");
        }
    }

    /**
     * Checks the whole nested config data object below the given component without creating it, which is what an
     * optional group that the config does not ask for needs: every mistake that instantiating the group would have
     * reported has to be reported while it is absent as well.
     *
     * @param name             the full name of the nested record component
     * @param component        the nested record component
     * @param recordType       the type of the nested record component
     * @param circularRefStack the record types that are currently being instantiated
     */
    private void validateNestedSchema(
            @NonNull final String name,
            @NonNull final RecordComponent component,
            @NonNull final Class<? extends Record> recordType,
            @NonNull final Set<Class<?>> circularRefStack) {
        validateIsRecord(recordType);
        validateIsNotAConfigDataType(name, recordType);
        resolveConfigDefaults(name, component, recordType);

        if (!circularRefStack.add(recordType)) {
            throw new IllegalStateException("Circular reference detected for record type '" + recordType + "'");
        }
        try {
            // Every component is checked and not only the ones that are nested records themselves, since the mistakes
            // that a component can carry do not depend on the config asking for its value.
            for (final RecordComponent nested : recordType.getRecordComponents()) {
                final String nestedName = createPropertyName(name, nested);
                if (validateComponentSchema(nestedName, nested)) {
                    validateNestedSchema(
                            nestedName, nested, nested.getType().asSubclass(Record.class), circularRefStack);
                }
            }
        } finally {
            circularRefStack.remove(recordType);
        }
    }

    /**
     * Checks that the given type of a nested config data object is not a config data type of its own. The two
     * annotations describe the two different roles a config record can have and are mutually exclusive: a
     * {@link ConfigData} record is registered and provides the prefix of its properties, while a {@link NestedConfig}
     * record takes its prefix from the component that holds it, so the prefix would silently be ignored here.
     *
     * @param name the full name of the nested record component
     * @param type the type of the nested record component
     */
    private static void validateIsNotAConfigDataType(@NonNull final String name, @NonNull final Class<?> type) {
        if (type.isAnnotationPresent(ConfigData.class)) {
            throw new IllegalArgumentException("Can not handle the record property '" + name + "' since '" + type
                    + "' is annotated with both " + ConfigData.class.getSimpleName() + " and "
                    + NestedConfig.class.getSimpleName() + ", which are mutually exclusive. A nested config data object"
                    + " takes its prefix from the component that holds it, so the prefix of the "
                    + ConfigData.class.getSimpleName() + " would never be used. Remove one of the two");
        }
    }

    /**
     * Resolves every {@link ConfigDefault} of the given nested record component into the full name of the property it
     * defines the default value of.
     * <p>
     * An annotation that addresses a property that does not exist has no effect and is most likely a typo, so it fails
     * here instead. The most likely mistake is using the name of the record component instead of the name it was given
     * by {@link ConfigProperty#value()}, so the properties that do exist are reported as well.
     * <p>
     * This is done while the annotation is read instead of afterwards, because a default that does not arrive leaves
     * the property it was meant for without a value, which would fail with an unrelated error first.
     *
     * @param namePrefix the full name of the nested record component
     * @param component  the nested record component
     * @param recordType the type of the nested record component
     * @return the default value of every addressed property, keyed by the full name of the property
     */
    @NonNull
    private Map<String, String> resolveConfigDefaults(
            @NonNull final String namePrefix,
            @NonNull final RecordComponent component,
            @NonNull final Class<? extends Record> recordType) {
        final Map<String, String> defaultValuesByPropertyName = new HashMap<>();
        for (final ConfigDefault configDefault : component.getAnnotationsByType(ConfigDefault.class)) {
            final String addressed = createPropertyName(namePrefix, configDefault.property());
            final List<RecordComponent> matches =
                    findProperties(namePrefix, recordType, configDefault.property(), new HashSet<>());

            final List<RecordComponent> leaves = matches.stream()
                    .filter(match -> !isNestedRecord(match.getType()))
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
                                        + getPropertyNames(namePrefix, recordType)
                                : "The " + ConfigDefault.class.getSimpleName() + " for '" + addressed
                                        + "' addresses a nested config data object instead of a single property. Address one of"
                                        + " its properties: "
                                        + getPropertyNames(
                                                addressed, matches.getFirst().getType()));
            }

            // The marker means "no default is defined" wherever a default value is read, and a ConfigDefault that
            // defines no default is indistinguishable from not writing the annotation at all. Storing the marker as an
            // ordinary default would assign its text as the value of the property and, since a ConfigDefault wins over
            // the default the property declares, silently drop that one.
            if (Objects.equals(ConfigProperty.UNDEFINED_DEFAULT_VALUE, configDefault.defaultValue())) {
                throw new IllegalArgumentException("The " + ConfigDefault.class.getSimpleName() + " for '" + addressed
                        + "' uses " + ConfigProperty.class.getSimpleName()
                        + ".UNDEFINED_DEFAULT_VALUE as its default value, which means that no default is defined."
                        + " Remove the annotation to leave the default of the property alone, or use "
                        + ConfigProperty.class.getSimpleName()
                        + ".NULL_DEFAULT_VALUE to default the property to null.");
            }

            // Two annotations may address the same property, and there is no reading of that which is not a mistake:
            // one of the two values is simply dropped. Rejecting it keeps the runtime and the annotation processor,
            // which documents these defaults, from having to agree on a precedence.
            final String clashing = defaultValuesByPropertyName.put(addressed, configDefault.defaultValue());
            if (clashing != null) {
                throw new IllegalArgumentException("There is more than one " + ConfigDefault.class.getSimpleName()
                        + " for the property '" + addressed + "', defining '" + clashing + "' and '"
                        + configDefault.defaultValue() + "'. Remove one of them.");
            }
        }
        return defaultValuesByPropertyName;
    }

    /**
     * Finds the properties that the given path addresses below the given nested config data object.
     * <p>
     * A dot has two meanings that can not be told apart by looking at the path alone: it separates the segments of a
     * property of a more deeply nested record, and it may be part of a single name that {@link ConfigProperty#value()}
     * defines. Every reading of the path is therefore followed, so that a name containing a dot is addressable and an
     * ambiguous path can be reported as such instead of one reading silently winning.
     *
     * @param namePrefix   the full name of the given record
     * @param recordType   the type to search in
     * @param path         the path to resolve, relative to the given record
     * @param visitedTypes the types that are currently being searched, to stop at a cycle
     * @return the components the path addresses, which is empty when it addresses none
     */
    @NonNull
    private static List<RecordComponent> findProperties(
            @NonNull final String namePrefix,
            @NonNull final Class<?> recordType,
            @NonNull final String path,
            @NonNull final Set<Class<?>> visitedTypes) {
        // a cycle is reported by the schema validation with a message that names the offending type, so here it is
        // enough to stop walking
        if (!isNestedRecord(recordType) || !visitedTypes.add(recordType)) {
            return List.of();
        }
        try {
            final List<RecordComponent> matches = new ArrayList<>();
            for (final RecordComponent candidate : recordType.getRecordComponents()) {
                final String segment = getPropertyNameSegment(candidate);
                if (Objects.equals(segment, path)) {
                    matches.add(candidate);
                } else if (path.startsWith(segment + ".")) {
                    matches.addAll(findProperties(
                            createPropertyName(namePrefix, segment),
                            candidate.getType(),
                            path.substring(segment.length() + 1),
                            visitedTypes));
                }
            }
            return matches;
        } finally {
            visitedTypes.remove(recordType);
        }
    }

    /**
     * Checks whether the given type is a nested config data object, meaning its properties are read individually.
     *
     * @param type the type
     * @return true if the type is a nested config data object
     */
    private static boolean isNestedRecord(@NonNull final Class<?> type) {
        return ConfigReflectionUtils.isNestedConfig(type);
    }

    /**
     * Returns the full names of every property below the given nested config data object, so that a
     * {@link ConfigDefault} that addresses none of them can report what it could have addressed. Only the properties
     * that a default value can be defined for are listed, so a component that holds a nested config data object is
     * replaced by the properties below it.
     *
     * @param prefix the full name of the given record
     * @param owner  the nested config data object
     * @return the full names of the properties below the given record
     */
    @NonNull
    private static Set<String> getPropertyNames(@NonNull final String prefix, @NonNull final Class<?> owner) {
        return getPropertyNames(prefix, owner, new HashSet<>());
    }

    @NonNull
    private static Set<String> getPropertyNames(
            @NonNull final String prefix, @NonNull final Class<?> owner, @NonNull final Set<Class<?>> visitedTypes) {
        if (!isNestedRecord(owner) || !visitedTypes.add(owner)) {
            return Set.of();
        }
        try {
            final Set<String> names = new TreeSet<>();
            for (final RecordComponent candidate : owner.getRecordComponents()) {
                final String name = createPropertyName(prefix, getPropertyNameSegment(candidate));
                if (isNestedRecord(candidate.getType())) {
                    names.addAll(getPropertyNames(name, candidate.getType(), visitedTypes));
                } else {
                    names.add(name);
                }
            }
            return names;
        } finally {
            visitedTypes.remove(owner);
        }
    }

    /**
     * Checks whether the config defines a value for at least one of the properties of the given nested config data
     * object, which is what decides whether an optional group is created or stays null.
     * <p>
     * The walk is done on the record type and not on an instance, since the instance is exactly what this decides about.
     * Only the leaves are asked about: a component that holds a nested config data object is itself never defined by a
     * config source, so it is walked into instead.
     *
     * @param namePrefix the full name of the component that holds the nested config data object
     * @param recordType the type of the nested config data object
     * @return true if the config defines a value for at least one property below the given component
     */
    private boolean isAnyPropertyDefined(@NonNull final String namePrefix, @NonNull final Class<?> recordType) {
        return isAnyPropertyDefined(namePrefix, recordType, new HashSet<>());
    }

    private boolean isAnyPropertyDefined(
            @NonNull final String namePrefix,
            @NonNull final Class<?> recordType,
            @NonNull final Set<Class<?>> visitedTypes) {
        // A cycle is reported by instantiateRecord with a message that names the offending type, so here it is enough
        // to stop walking instead of failing with a less helpful error from a check that only collects names.
        if (!visitedTypes.add(recordType)) {
            return false;
        }
        try {
            for (final RecordComponent component : recordType.getRecordComponents()) {
                final String name = createPropertyName(namePrefix, component);
                final boolean defined = isNestedRecord(component.getType())
                        ? isAnyPropertyDefined(name, component.getType(), visitedTypes)
                        : configuration.exists(name);
                if (defined) {
                    return true;
                }
            }
            return false;
        } finally {
            visitedTypes.remove(recordType);
        }
    }

    private static boolean isGenericType(@NonNull final RecordComponent component, @NonNull final Class<?> type) {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(type, "type must not be null");
        final ParameterizedType stringSetType = (ParameterizedType) component.getGenericType();
        return Objects.equals(type, stringSetType.getRawType());
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> getGenericSetType(@NonNull final RecordComponent component) {
        if (!isGenericType(component, Set.class)) {
            throw new IllegalArgumentException("Only Set interface is supported");
        }
        return (Class<T>)
                ConfigReflectionUtils.getSingleGenericTypeArgument((ParameterizedType) component.getGenericType());
    }

    @SuppressWarnings("unchecked")
    @NonNull
    private static <T> Class<T> getGenericListType(@NonNull final RecordComponent component) {
        Objects.requireNonNull(component, "component must not be null");
        if (!isGenericType(component, List.class)) {
            throw new IllegalArgumentException("Only List interface is supported");
        }
        final Class<T> cls = (Class<T>)
                ConfigReflectionUtils.getSingleGenericTypeArgument((ParameterizedType) component.getGenericType());
        if (cls == null) {
            throw new IllegalArgumentException("No generic class found!");
        }
        return cls;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private <T> Set<T> getDefaultValueSet(
            @NonNull final RecordComponent component, @NonNull final String rawDefaultValue) {
        Objects.requireNonNull(component, "component must not be null");
        final Class<?> type = getGenericSetType(component);
        if (Objects.equals(ConfigProperty.NULL_DEFAULT_VALUE, rawDefaultValue)) {
            return null;
        }
        return (Set<T>) ConfigListUtils.createList(rawDefaultValue).stream()
                .map(value -> converterService.convert(value, type))
                // We want to retain the iteration order of items from the original list, so we use a LinkedHashSet:
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private <T> List<T> getDefaultValues(
            @NonNull final RecordComponent component, @NonNull final String rawDefaultValue) {
        Objects.requireNonNull(component, "component must not be null");
        final Class<?> type = getGenericListType(component);
        if (Objects.equals(ConfigProperty.NULL_DEFAULT_VALUE, rawDefaultValue)) {
            return null;
        }
        return (List<T>) ConfigListUtils.createList(rawDefaultValue).stream()
                .map(value -> converterService.convert(value, type))
                .toList();
    }

    @NonNull
    private static <T extends Record> String getNamePrefix(@NonNull final Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        return Optional.ofNullable(type.getAnnotation(ConfigData.class))
                .map(ConfigData::value)
                .orElse("");
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private <T> T getDefaultValue(@NonNull final RecordComponent component, @NonNull final String rawDefaultValue) {
        Objects.requireNonNull(component, "component must not be null");
        if (Objects.equals(ConfigProperty.NULL_DEFAULT_VALUE, rawDefaultValue)) {
            return null;
        }
        return (T) converterService.convert(rawDefaultValue, component.getType());
    }

    @NonNull
    private static Optional<String> getRawDefaultValue(@NonNull final RecordComponent component) {
        Objects.requireNonNull(component, "component must not be null");
        return Optional.ofNullable(component.getAnnotation(ConfigProperty.class))
                .map(ConfigProperty::defaultValue)
                .filter(defaultValue -> !Objects.equals(ConfigProperty.UNDEFINED_DEFAULT_VALUE, defaultValue));
    }

    @NonNull
    private static String createPropertyName(@NonNull final String prefix, @NonNull final RecordComponent component) {
        Objects.requireNonNull(component, "component must not be null");
        return createPropertyName(prefix, getPropertyNameSegment(component));
    }

    /**
     * Returns the name that the given record component has in the config, which is the name defined by
     * {@link ConfigProperty#value()} if one is defined and the name of the component otherwise.
     *
     * @param component the record component
     * @return the name of the property without any prefix
     */
    @NonNull
    private static String getPropertyNameSegment(@NonNull final RecordComponent component) {
        Objects.requireNonNull(component, "component must not be null");
        return Optional.ofNullable(component.getAnnotation(ConfigProperty.class))
                .map(ConfigProperty::value)
                .filter(name -> !name.isBlank())
                .orElseGet(component::getName);
    }

    @NonNull
    private static String createPropertyName(@NonNull final String prefix, @NonNull final String name) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (prefix.isBlank()) {
            return name;
        }
        return prefix + "." + name;
    }

    /**
     * The default values that are defined by the {@link ConfigDefault} annotations of a config data object, keyed by
     * the full name of the property they apply to.
     * <p>
     * Since a config data object is created top down, an annotation of an enclosing config data object is always added
     * before the annotations of the records below it and therefore wins over them. This allows a config data object to
     * override the defaults of everything it contains.
     */
    private static final class DefaultValueOverrides {

        private final Map<String, String> valuesByPropertyName = new HashMap<>();

        /**
         * Adds the values that the {@link ConfigDefault} annotations of one nested record component define, as resolved
         * by {@link #resolveConfigDefaults(String, RecordComponent, Class)}.
         * <p>
         * A value that is already known is kept, which is what makes an enclosing config data object win: it is created
         * top down, so its annotations are always added before the annotations of the records below it. A duplicate
         * within one component is rejected while it is resolved and never arrives here.
         *
         * @param defaultValuesByPropertyName the default values, keyed by the full name of the property
         */
        void add(@NonNull final Map<String, String> defaultValuesByPropertyName) {
            defaultValuesByPropertyName.forEach(valuesByPropertyName::putIfAbsent);
        }

        /**
         * Returns the default value for the given property.
         *
         * @param propertyName the full name of the property
         * @return the default value, or null if no {@link ConfigDefault} defines one
         */
        @Nullable
        String get(@NonNull final String propertyName) {
            return valuesByPropertyName.get(propertyName);
        }
    }
}

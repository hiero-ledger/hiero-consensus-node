// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.swirlds.config.api.ConfigData;
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
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Internal factory for config data objects. See {@link Configuration#getConfigData(Class)} for a detailed description
 * on config data objects.
 * <p>
 * A record component whose type is annotated with {@link NestedConfig} is a group of properties rather than a value.
 * Such a group behaves exactly as if its properties had been declared on the enclosing record with dotted names, so
 * creating a config data object is done in two phases that mirror that: {@link #validateSchema(String, Class, Set)}
 * checks everything that follows from the declaration of a record alone, and {@link #instantiateRecord(String, Class)}
 * then only resolves values.
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

        if (!type.isAnnotationPresent(ConfigData.class)) {
            throw new IllegalArgumentException("Can not create config instance for '" + type + "' since "
                    + ConfigData.class.getName() + "' " + "annotation is missing");
        }
        if (isNestedConfig(type)) {
            throw new IllegalArgumentException("Can not create config instance for '" + type + "' since it is annotated"
                    + " with " + NestedConfig.class.getSimpleName() + ", which means it is a group of properties that"
                    + " is only used as a record component of a config data object and never registered on its own");
        }

        final String namePrefix = getNamePrefix(type);
        validateSchema(namePrefix, type, new HashSet<>());
        return instantiateRecord(namePrefix, type);
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

    /**
     * Checks everything about the given record that follows from its declaration alone, for the record itself and for
     * every nested config data object below it. This is done before any value is read, so that a record which is
     * declared wrongly is reported as such instead of failing with an unrelated error while a value is resolved.
     *
     * @param namePrefix the property name prefix of the given record
     * @param recordType the record type to check
     * @param inProgress the record types that are currently being checked, to detect a cycle
     */
    private void validateSchema(
            @NonNull final String namePrefix,
            @NonNull final Class<? extends Record> recordType,
            @NonNull final Set<Class<?>> inProgress) {
        if (!inProgress.add(recordType)) {
            throw new IllegalStateException("Circular reference detected for record type '" + recordType + "'");
        }
        try {
            for (final RecordComponent component : recordType.getRecordComponents()) {
                final String name = createPropertyName(namePrefix, component);
                if (validateComponentSchema(name, component)) {
                    validateSchema(name, component.getType().asSubclass(Record.class), inProgress);
                }
            }
        } finally {
            inProgress.remove(recordType);
        }
    }

    /**
     * Checks everything about the given record component that follows from its declaration alone.
     *
     * @param name      the full name of the property
     * @param component the record component
     * @return true if the component holds a nested config data object
     */
    private boolean validateComponentSchema(@NonNull final String name, @NonNull final RecordComponent component) {
        final Class<?> valueType = component.getType();
        final boolean isNestedRecord = isNestedConfig(valueType);

        validateIsNotACollectionOfNestedRecords(name, component);

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
        if (!isNestedRecord) {
            return false;
        }

        validateIsNotAConfigDataType(name, valueType);
        validateIsConcreteType(name, component);
        validateHasNoDefaultValue(name, component);
        validateIsRecord(valueType);
        return true;
    }

    /**
     * Checks that the given record component is not a {@link List} or {@link Set} of nested config data objects.
     * <p>
     * A nested config data object is a group of properties rather than a value, and the name of a group comes from the
     * single component that holds it. A collection has no such name for each of its elements, so there is no property
     * name a config source could use, and the collection would be read as a single property whose elements a converter
     * creates. That converter can not exist, since a nested config data object must not have one, and the failure would
     * name the missing converter instead of the mistake.
     *
     * @param name      the full name of the property
     * @param component the record component
     */
    private static void validateIsNotACollectionOfNestedRecords(
            @NonNull final String name, @NonNull final RecordComponent component) {
        final Class<?> valueType = component.getType();
        if (!Objects.equals(List.class, valueType) && !Objects.equals(Set.class, valueType)) {
            return;
        }

        // The element type is read without the helpers that create the value, so that a declaration those reject, like
        // a raw or wildcard collection, keeps being reported where the value is read rather than here.
        if (!(component.getGenericType() instanceof final ParameterizedType parameterizedType)) {
            return;
        }
        final Type[] typeArguments = parameterizedType.getActualTypeArguments();
        if (typeArguments.length != 1 || !(typeArguments[0] instanceof final Class<?> elementType)) {
            return;
        }

        if (isNestedConfig(elementType)) {
            throw new IllegalArgumentException("Can not handle the property '" + name + "' since '" + valueType
                    + "' holds '" + elementType + "', which is annotated with " + NestedConfig.class.getSimpleName()
                    + ". A nested config data object is a group of properties that takes its name from the single"
                    + " component holding it, so there is no property name for an element of a collection. Use a"
                    + " component of that type per group, or a type with a registered converter as the element type");
        }
    }

    /**
     * Checks that the given component holding a nested config data object declares the group by its concrete record
     * type rather than by a type variable or a wildcard.
     * <p>
     * The type of the component is what decides which properties the group has, and the annotation processor resolves
     * that type from the source while the runtime resolves it by reflection. Requiring the type to be written out is
     * what makes the two provably arrive at the same set of properties.
     *
     * @param name      the full name of the property
     * @param component the nested record component
     */
    private static void validateIsConcreteType(@NonNull final String name, @NonNull final RecordComponent component) {
        if (!(component.getGenericType() instanceof Class<?>)) {
            throw new IllegalArgumentException("Can not handle the record property '" + name + "' since it declares the"
                    + " nested config data object as '" + component.getGenericType()
                    + "' instead of naming the record type. The properties of a group follow from its type, so the type"
                    + " has to be written out");
        }
    }

    /**
     * Checks that the given component holding a nested config data object declares no default value. A group has no
     * value of its own that a config source could define, so there is nothing a default value of the component could
     * mean.
     *
     * @param name      the full name of the property
     * @param component the nested record component
     */
    private static void validateHasNoDefaultValue(
            @NonNull final String name, @NonNull final RecordComponent component) {
        if (getRawDefaultValue(component).isPresent()) {
            throw new IllegalArgumentException("Can not use a default value for the property '" + name + "' since '"
                    + component.getType() + "' is a nested config data object, which is a group of properties rather"
                    + " than a value. Define the default values of its properties instead");
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
     * Creates the given record by resolving the value of each of its components. The schema of the record was checked
     * before, so nothing is validated here.
     *
     * @param namePrefix the property name prefix of the given record
     * @param type       the record type to create
     * @return the created record
     */
    @SuppressWarnings("unchecked")
    @NonNull
    private <T extends Record> T instantiateRecord(@NonNull final String namePrefix, @NonNull final Class<T> type)
            throws InvocationTargetException, InstantiationException, IllegalAccessException {
        final RecordComponent[] recordComponents = type.getRecordComponents();
        final Object[] paramValues = new Object[recordComponents.length];

        for (int i = 0; i < recordComponents.length; i++) {
            paramValues[i] = getValueForRecordComponent(namePrefix, recordComponents[i]);
        }

        final Constructor<T> constructor = (Constructor<T>) type.getConstructors()[0];
        return constructor.newInstance(paramValues);
    }

    @Nullable
    private Object getValueForRecordComponent(
            @NonNull final String namePrefix, @NonNull final RecordComponent component) {
        Objects.requireNonNull(component, "component must not be null");
        final String name = createPropertyName(namePrefix, component);
        final Class<?> valueType = component.getType();

        if (isNestedConfig(valueType)) {
            return instantiateNestedRecord(name, valueType.asSubclass(Record.class));
        }

        final String rawDefaultValue = getRawDefaultValue(component).orElse(null);
        if (rawDefaultValue != null) {
            if (Objects.equals(List.class, valueType)) {
                final Class<?> genericType = getGenericListType(component);
                return configuration.getValues(name, genericType, getDefaultValues(component, rawDefaultValue));
            }
            if (Objects.equals(Set.class, valueType)) {
                final Class<?> genericType = getGenericSetType(component);
                return configuration.getValueSet(name, genericType, getDefaultValueSet(component, rawDefaultValue));
            }
            return configuration.getValue(name, valueType, getDefaultValue(component, rawDefaultValue));
        } else {
            if (Objects.equals(List.class, valueType)) {
                final Class<?> genericType = getGenericListType(component);
                return configuration.getValues(name, genericType);
            }
            if (Objects.equals(Set.class, valueType)) {
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

    @NonNull
    private Record instantiateNestedRecord(
            @NonNull final String name, @NonNull final Class<? extends Record> recordType) {
        // the prefix of a nested config data object is always the name of the property that holds it, so a prefix that
        // the record defines for its own use as a registered config data type is not used here
        try {
            return instantiateRecord(name, recordType);
        } catch (final InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Unable to instantiate record for '" + name + "'", e);
        }
    }

    private static boolean isNestedConfig(@NonNull final Class<?> type) {
        return ConfigReflectionUtils.isNestedConfig(type);
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
}

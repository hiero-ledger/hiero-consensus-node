// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.reflection;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.NestedConfig;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Some methods that are needed for the initialization of the config that internally use reflection.
 */
public final class ConfigReflectionUtils {

    private ConfigReflectionUtils() {}

    /**
     * Returns the generic type of the class or throws an {@link IllegalArgumentException} if the given class has not
     * exactly one generic type.
     *
     * @param parameterizedType the class
     * @return the generic type of the class
     */
    public static Type getSingleGenericTypeArgument(final ParameterizedType parameterizedType) {
        if (parameterizedType.getActualTypeArguments().length != 1) {
            throw new IllegalArgumentException("Only exactly 1 generic type is supported");
        }
        return parameterizedType.getActualTypeArguments()[0];
    }

    /**
     * Returns true if the given class is public.
     *
     * @param type the class
     * @return true if the given class is public
     */
    public static boolean isPublic(final Class<?> type) {
        return Modifier.isPublic(type.getModifiers());
    }

    /**
     * Returns the config property name for a property of a config data object (see {@link ConfigData}).
     *
     * @param prefix    the prefix of the  config data type
     * @param component the record component that defines the property
     * @return the config property name for a property
     */
    public static String getPropertyNameForConfigDataProperty(final String prefix, final RecordComponent component) {
        return Optional.ofNullable(component.getAnnotation(ConfigProperty.class))
                .map(propertyAnnotation -> {
                    if (!propertyAnnotation.value().isBlank()) {
                        return getPropertyNameForConfigDataProperty(prefix, propertyAnnotation.value());
                    } else {
                        return getPropertyNameForConfigDataProperty(prefix, component.getName());
                    }
                })
                .orElseGet(() -> getPropertyNameForConfigDataProperty(prefix, component.getName()));
    }

    /**
     * Returns the config property name for a property of a config data object (see {@link ConfigData}).
     *
     * @param prefix the prefix of the  config data type
     * @param name   the name of the property
     * @return the config property name
     */
    public static String getPropertyNameForConfigDataProperty(final String prefix, final String name) {
        if (prefix.isBlank()) {
            return name;
        }
        return prefix + "." + name;
    }

    /**
     * Returns the name of a config data type (see {@link ConfigData}).
     *
     * @param type the config data type
     * @return the name of a config data type
     */
    public static String getNamePrefixForConfigDataRecord(final AnnotatedElement type) {
        return Optional.ofNullable(type.getAnnotation(ConfigData.class))
                .map(ConfigData::value)
                .orElse("");
    }

    /**
     * Returns all properties that are annotated with the given constraint annotation, including the properties of
     * nested config data objects (see {@link NestedConfig}). The annotation itself can be read from a returned property
     * by {@link ConfigDataProperty#annotation(Class)}.
     * <p>
     * Unlike {@link #getAllProperties(Configuration)} this also reports the component that holds a nested config data
     * object, so that a constraint can be defined for a group as a whole.
     * <p>
     * Finding the properties reads the record types and their annotations only, so a config data object that defines no
     * constraint at all costs no access to its values.
     *
     * @param constraintAnnotationType the type of the constraint annotation
     * @param configuration            the configuration that should be used for the search
     * @param <A>                      the annotation type
     * @return all properties that are annotated with the given constraint annotation
     */
    public static <A extends Annotation> List<ConfigDataProperty> getAllMatchingPropertiesForConstraintAnnotation(
            final Class<A> constraintAnnotationType, final Configuration configuration) {
        Objects.requireNonNull(constraintAnnotationType, "annotationType can not be null");
        Objects.requireNonNull(configuration, "configuration can not be null");

        return collectAllComponents(configuration)
                .filter(property -> property.component().isAnnotationPresent(constraintAnnotationType))
                .collect(Collectors.toList());
    }

    /**
     * Returns all properties of all config data objects that are registered for the given configuration, including the
     * properties of nested config data objects (see {@link NestedConfig}).
     * <p>
     * A nested config data object groups properties instead of holding a value, so the component that holds one is
     * replaced by the properties below it: a config source defines {@code "nested.leaf.value"} and never
     * {@code "nested.leaf"}. The result is therefore exactly the set of properties that the same declaration would
     * define if it had been written flat, with dotted names and scalar components.
     * <p>
     * A record valued property that a converter populates is a single settable property and is reported as it is,
     * since a converter decides how one value is read rather than grouping several.
     * <p>
     * The properties are found from the record types and their annotations alone. The value of a property is read when
     * {@link ConfigDataProperty#propertyValue()} is called, so the name of a property can be used without reading any
     * value of a config data object.
     *
     * @param configuration the configuration
     * @return all settable properties of all registered config data objects
     */
    public static Stream<ConfigDataProperty> getAllProperties(final Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration can not be null");
        return collectAllComponents(configuration).filter(property -> !isNestedConfig(property.propertyType()));
    }

    /**
     * Returns every record component of every registered config data object, walking into the components that hold a
     * nested config data object.
     * <p>
     * The walk reads the record types and their annotations only. The value of a component that holds a nested config
     * data object is not read here but resolved through {@link ConfigDataProperty#owner()} when it is needed, so that
     * finding the properties of a configuration never reads the values of its config data objects. Describing a flat
     * config data object needs no access to its values either, and a nested one must not differ.
     * <p>
     * A cycle in the record types would not terminate this walk, and can not occur: a cycle is rejected when a config
     * data type is registered, so every type that a configuration reports is acyclic.
     *
     * @param configuration the configuration
     * @return every record component of every registered config data object
     */
    private static Stream<ConfigDataProperty> collectAllComponents(final Configuration configuration) {
        return configuration.getConfigDataTypes().stream()
                .flatMap(recordType -> collectComponents(
                        getNamePrefixForConfigDataRecord(recordType),
                        recordType,
                        configuration.getConfigData(recordType),
                        null));
    }

    /**
     * Recursively collects all record components of the given record type.
     * <p>
     * The walk is done on the record types while the property name follows the components that lead to them, since a
     * record component of a nested record does not identify a single config property: the same nested record type can
     * be used several times below one config data object, and the name prefix is what tells the occurrences apart.
     *
     * @param namePrefix the property name prefix of the given record type
     * @param recordType the record type to collect the components from
     * @param configData the config data object that is being walked, which declares the components of the registered
     *                   record type itself
     * @param holder     the property that holds the given record type, or null for the registered record type
     * @return all record components of the given record type and of all nested config data objects below it
     */
    private static Stream<ConfigDataProperty> collectComponents(
            final String namePrefix,
            final Class<? extends Record> recordType,
            final Record configData,
            final ConfigDataProperty holder) {
        return Arrays.stream(recordType.getRecordComponents()).flatMap(component -> {
            final String propertyName = getPropertyNameForConfigDataProperty(namePrefix, component);
            final ConfigDataProperty property = new ConfigDataProperty(propertyName, component, configData, holder);

            if (!isNestedConfig(component.getType())) {
                return Stream.of(property);
            }
            return Stream.concat(
                    Stream.of(property),
                    collectComponents(
                            propertyName, component.getType().asSubclass(Record.class), configData, property));
        });
    }

    /**
     * Checks whether the given type is a nested config data object, meaning it holds properties of its own rather than
     * being a single value. A record type has to be annotated with {@link NestedConfig} to be treated as one, so that a
     * record type that is populated from a single value by a converter is never mistaken for a group of properties.
     *
     * @param type the type
     * @return true if the type is a nested config data object
     */
    public static boolean isNestedConfig(final Class<?> type) {
        Objects.requireNonNull(type, "type can not be null");
        return type.isRecord() && type.isAnnotationPresent(NestedConfig.class);
    }

    /**
     * Reads the value of the given record component from the given record instance.
     *
     * @param component      the component
     * @param recordInstance the record instance that declares the component
     * @return the value of the component
     */
    private static Object getPropertyValue(final RecordComponent component, final Record recordInstance) {
        try {
            return component.getAccessor().invoke(recordInstance);
        } catch (final IllegalAccessException | InvocationTargetException e) {
            throw new IllegalArgumentException(
                    "Can not read the value of the property '" + component.getName() + "' of '"
                            + recordInstance.getClass().getName() + "'",
                    e);
        }
    }

    /**
     * A property of a config data object.
     *
     * @param propertyName the full name of the property, including the prefixes of all enclosing config data objects
     * @param component    the record component that defines the property
     * @param configData   the config data object that the property belongs to
     * @param holder       the property that holds the nested config data object which declares this property, or null
     *                     when the config data object declares it itself
     */
    public record ConfigDataProperty(
            String propertyName, RecordComponent component, Record configData, ConfigDataProperty holder) {

        /**
         * Returns the record instance that declares the property. For a property of a nested config data object this is
         * the nested record instance and not the config data object.
         * <p>
         * A nested config data object is read from the component that holds it, so this reads the value of every
         * component on the way down from the config data object.
         *
         * @return the record instance that declares the property
         */
        public Record owner() {
            return holder == null ? configData : (Record) holder.propertyValue();
        }

        /**
         * Returns the value of the property.
         *
         * @return the value of the property
         */
        public Object propertyValue() {
            return getPropertyValue(component, owner());
        }

        /**
         * Returns the type of the property.
         *
         * @return the type of the property
         */
        public Class<?> propertyType() {
            return component.getType();
        }

        /**
         * Returns the annotation of the given type that the property is annotated with.
         *
         * @param annotationType the type of the annotation
         * @param <A>            the annotation type
         * @return the annotation, or null if the property is not annotated with it
         */
        public <A extends Annotation> A annotation(final Class<A> annotationType) {
            return component.getAnnotation(annotationType);
        }
    }
}

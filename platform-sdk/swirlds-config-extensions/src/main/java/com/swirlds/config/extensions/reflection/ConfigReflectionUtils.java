// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.reflection;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
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
     * Returns all {@link AnnotatedProperty} that can be found for the given constraint annotation.
     *
     * @param constraintAnnotationType the type of the constraint annotation
     * @param configuration            the configuration that should be used for the search
     * @param <A>                      the annotation type
     * @param <V>                      the type of possible values
     * @return all {@link AnnotatedProperty} that can be found for the given constraint annotation
     */
    @SuppressWarnings("unchecked")
    public static <A extends Annotation, V>
            List<AnnotatedProperty<A, V>> getAllMatchingPropertiesForConstraintAnnotation(
                    final Class<A> constraintAnnotationType, final Configuration configuration) {
        Objects.requireNonNull(constraintAnnotationType, "annotationType can not be null");
        Objects.requireNonNull(configuration, "configuration can not be null");

        return configuration.getConfigDataTypes().stream()
                .flatMap(recordType -> collectMatchingProperties(
                        constraintAnnotationType,
                        getNamePrefixForConfigDataRecord(recordType),
                        configuration.getConfigData(recordType)))
                .map(property -> (AnnotatedProperty<A, V>) property)
                .collect(Collectors.toList());
    }

    /**
     * Recursively collects all properties of the given record instance that are annotated with the given annotation.
     * Record components that are themselves records are treated as nested config data objects and are descended into,
     * so that a nested property is reported with its full property name (like {@code "root.nested.value"}).
     * <p>
     * The walk is done on the already created object graph instead of on the record types, since a record component of
     * a nested record does not identify a single config property: the same nested record type can be used several times
     * below one config data object, each time with its own property name and value.
     *
     * @param annotationType the type of the constraint annotation
     * @param namePrefix     the property name prefix of the given record instance
     * @param recordInstance the record instance to collect the properties from
     * @param <A>            the annotation type
     * @return all annotated properties of the given record instance and all its nested records
     */
    private static <A extends Annotation> Stream<AnnotatedProperty<A, Object>> collectMatchingProperties(
            final Class<A> annotationType, final String namePrefix, final Record recordInstance) {
        return Arrays.stream(recordInstance.getClass().getRecordComponents()).flatMap(component -> {
            final String propertyName = getPropertyNameForConfigDataProperty(namePrefix, component);
            final Object propertyValue = getPropertyValue(component, recordInstance);

            Stream<AnnotatedProperty<A, Object>> properties = Stream.empty();
            if (component.isAnnotationPresent(annotationType)) {
                properties =
                        Stream.of(createData(annotationType, component, propertyName, propertyValue, recordInstance));
            }
            // A null value can only happen for a record that was created by a converter, an instance of a nested config
            // data object is never null. The pattern match therefore doubles as the null check.
            if (component.getType().isRecord() && propertyValue instanceof final Record nestedInstance) {
                properties = Stream.concat(
                        properties, collectMatchingProperties(annotationType, propertyName, nestedInstance));
            }
            return properties;
        });
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
            throw new IllegalArgumentException("Can not get the needed metadata for the given type", e);
        }
    }

    /**
     * Creates a {@link AnnotatedProperty} for the given values.
     *
     * @param annotationType the type of the annotation
     * @param component      the component
     * @param propertyName   the full name of the property
     * @param propertyValue  the value of the property
     * @param owner          the record instance that declares the component
     * @param <A>            type of the annotation
     * @return the AnnotatedProperty
     */
    @SuppressWarnings("unchecked")
    private static <A extends Annotation> AnnotatedProperty<A, Object> createData(
            final Class<A> annotationType,
            final RecordComponent component,
            final String propertyName,
            final Object propertyValue,
            final Record owner) {
        return new AnnotatedProperty<>(
                component.getAnnotation(annotationType),
                component,
                propertyName,
                propertyValue,
                (Class<Object>) component.getType(),
                owner);
    }

    /**
     * A property of a config data object that is annotated with a constraint annotation.
     *
     * @param annotation    the constraint annotation
     * @param component     the record component that defines the property
     * @param propertyName  the full name of the property, including the prefixes of all enclosing config data objects
     * @param propertyValue the value of the property
     * @param propertyType  the type of the property
     * @param owner         the record instance that declares the property. For a property of a nested config data
     *                      object this is the nested record instance and not the root config data object
     * @param <A>           type of the annotation
     * @param <V>           type of the value
     */
    public record AnnotatedProperty<A extends Annotation, V>(
            A annotation,
            RecordComponent component,
            String propertyName,
            V propertyValue,
            Class<V> propertyType,
            Record owner) {}
}

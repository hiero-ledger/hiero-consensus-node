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
    public static <A extends Annotation, V>
            List<AnnotatedProperty<A, V>> getAllMatchingPropertiesForConstraintAnnotation(
                    final Class<A> constraintAnnotationType, final Configuration configuration) {
        Objects.requireNonNull(constraintAnnotationType, "annotationType can not be null");
        Objects.requireNonNull(configuration, "configuration can not be null");
        return configuration.getConfigDataTypes().stream()
                .flatMap(recordType -> Arrays.stream(recordType.getRecordComponents()))
                .filter(component -> component.isAnnotationPresent(constraintAnnotationType))
                .map(component ->
                        (AnnotatedProperty<A, V>) createData(constraintAnnotationType, configuration, component))
                .collect(Collectors.toList());
    }

    /**
     * Creates a {@link AnnotatedProperty} for the given values.
     *
     * @param annotationType the type of the annotation
     * @param configuration  the configuration
     * @param component      the component
     * @param <A>            type of the annotation
     * @param <V>            type of the value
     * @return the AnnotatedProperty
     */
    private static <A extends Annotation, V> AnnotatedProperty<A, V> createData(
            final Class<A> annotationType, final Configuration configuration, final RecordComponent component) {
        try {
            final A annotation = component.getAnnotation(annotationType);
            final Class<? extends Record> recordType = (Class<? extends Record>) component.getDeclaringRecord();
            final String propertyNamePrefix = getNamePrefixForConfigDataRecord(recordType);
            final Object recordInstance = configuration.getConfigData(recordType);
            final V propertyValue;
            propertyValue = (V) component.getAccessor().invoke(recordInstance);

            final String propertyName = getPropertyNameForConfigDataProperty(propertyNamePrefix, component);
            final Class<V> propertyType = (Class<V>) component.getType();
            return new AnnotatedProperty<>(annotation, component, propertyName, propertyValue, propertyType);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalArgumentException("Can not get the needed metadata for the given type", e);
        }
    }

    public record AnnotatedProperty<A extends Annotation, V>(
            A annotation, RecordComponent component, String propertyName, V propertyValue, Class<V> propertyType) {}
}

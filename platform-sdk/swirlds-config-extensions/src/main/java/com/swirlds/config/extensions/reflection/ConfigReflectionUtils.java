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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
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
     * Returns all properties that are annotated with the given constraint annotation. The annotation itself can be read
     * from a returned property by {@link ConfigDataProperty#annotation(Class)}.
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

        // A record that is created by a converter is walked into as well. A converter decides how a value is
        // populated, while a constraint is about the resolved value and is therefore enforced whatever populated it.
        return collectAllProperties(configuration, Traversal.ALL_COMPONENTS)
                .filter(property -> property.component().isAnnotationPresent(constraintAnnotationType))
                .collect(Collectors.toList());
    }

    /**
     * Returns all properties of all config data objects that are registered for the given configuration, including the
     * properties of nested config data objects.
     * <p>
     * Only the properties that can be set are reported. A component that holds a nested config data object is
     * therefore replaced by the properties of that object, since the component itself has no value of its own, while a
     * record valued property that a converter populates is a single settable property and is reported as it is.
     * <p>
     * The value of a property is read on demand by {@link ConfigDataProperty#propertyValue()}, so collecting the
     * properties does not access any config data object. A config data object that lives in a package its module does
     * not export to this one is therefore still reported, but reading the value of one of its properties fails.
     *
     * @param configuration the configuration
     * @return all properties of all registered config data objects as stream, including the properties of nested config data objects
     */
    public static Stream<ConfigDataProperty> getAllProperties(final Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration can not be null");
        return collectAllProperties(configuration, Traversal.SETTABLE_PROPERTIES);
    }

    /**
     * Returns the value of every property that matches the given filter, keyed by the full name of the property and
     * sorted by it. See {@link #getAllProperties(Configuration)} for the properties that are reported.
     * <p>
     * The values are read here, so unlike {@link #getAllProperties(Configuration)} this accesses every config data
     * object that matches the filter.
     * <p>
     * Note that {@link java.util.stream.Collectors#toMap(java.util.function.Function, java.util.function.Function)} can
     * not be used to build the result: it is implemented with {@link java.util.Map#merge}, which rejects a null value,
     * while the value of a config property is allowed to be null (see {@link ConfigProperty#NULL_DEFAULT_VALUE}). Two
     * config data objects can also define the same property name, in which case the last one wins.
     *
     * @param configuration the configuration
     * @param filter        selects the properties to return
     * @return the value of every matching property, keyed by the full name of the property
     * @throws IllegalArgumentException if the value of a matching property can not be read. Use
     *                                  {@link #getAllPropertiesAsMap(Configuration, Predicate, BiConsumer)} to skip
     *                                  such a property instead.
     */
    public static SortedMap<String, Object> getAllPropertiesAsMap(
            final Configuration configuration, final Predicate<ConfigDataProperty> filter) {
        return getAllPropertiesAsMap(configuration, filter, null);
    }

    /**
     * Returns the value of every property that matches the given filter, keyed by the full name of the property and
     * sorted by it, skipping every property whose value can not be read. See
     * {@link #getAllProperties(Configuration)} for the properties that are reported and for why reading a value can
     * fail.
     * <p>
     * This is for a caller that must not fail because of a single unreadable property, like one that only logs the
     * configuration. A caller that wants an unreadable property to be an error uses
     * {@link #getAllPropertiesAsMap(Configuration, Predicate)} instead.
     *
     * @param configuration  the configuration
     * @param filter         selects the properties to return
     * @param failureHandler notified with the name of the property and the failure for every property that is skipped
     * @return the value of every matching and readable property, keyed by the full name of the property
     */
    public static SortedMap<String, Object> getAllPropertiesAsMap(
            final Configuration configuration,
            final Predicate<ConfigDataProperty> filter,
            final BiConsumer<String, RuntimeException> failureHandler) {
        Objects.requireNonNull(configuration, "configuration can not be null");
        Objects.requireNonNull(filter, "filter can not be null");

        final TreeMap<String, Object> values = new TreeMap<>();
        getAllProperties(configuration).filter(filter).forEach(property -> {
            try {
                values.put(property.propertyName(), property.propertyValue());
            } catch (final RuntimeException e) {
                if (failureHandler == null) {
                    throw e;
                }
                failureHandler.accept(property.propertyName(), e);
            }
        });
        return Collections.unmodifiableSortedMap(values);
    }

    /**
     * Returns all properties of all config data objects that are registered for the given configuration.
     *
     * @param configuration the configuration
     * @param traversal     which record components to report
     * @return all properties of all registered config data objects as a stream
     */
    private static Stream<ConfigDataProperty> collectAllProperties(
            final Configuration configuration, final Traversal traversal) {
        return configuration.getConfigDataTypes().stream()
                .flatMap(recordType -> collectProperties(
                        getNamePrefixForConfigDataRecord(recordType),
                        configuration.getConfigData(recordType),
                        traversal));
    }

    /**
     * Defines which record components a traversal reports. The two modes differ in what is reported and in what is
     * walked into, and the two are not the same question: a record valued component can be a single value or a group
     * of properties, depending on whether it is a nested config data object.
     */
    private enum Traversal {
        /**
         * Every component that is one value a config source can set, whichever type it has. This is not the same as
         * every component that is not a record: a {@link java.util.List} property is set as {@code "404,500"} and a
         * record that a converter populates is set as a single value like {@code "1:10"}, so both are reported as they
         * are and are not walked into.
         * <p>
         * The only component that is not reported is one that holds a nested config data object. Such a component has
         * no value of its own, since a config source defines {@code "nested.leaf.value"} and never
         * {@code "nested.leaf"}, so it is replaced by the properties below it.
         */
        SETTABLE_PROPERTIES,
        /**
         * Every record component, walking into every record valued component, so that an annotation is found wherever
         * it is declared.
         * <p>
         * This reports more than {@link #SETTABLE_PROPERTIES} in two ways. A record that a converter populates is
         * walked into, so that a constraint on one of its components is enforced even though the record itself is a
         * single value. And a component that holds a nested config data object is reported, so that a constraint can
         * be defined for the group as a whole.
         */
        ALL_COMPONENTS
    }

    /**
     * Recursively collects all properties of the given record instance.
     * <p>
     * The walk is done on the already created object graph instead of on the record types, since a record component of
     * a nested record does not identify a single config property: the same nested record type can be used several times
     * below one config data object, each time with its own property name and value.
     *
     * @param namePrefix     the property name prefix of the given record instance
     * @param recordInstance the record instance to collect the properties from
     * @param traversal      which record components to report
     * @return all properties of the given record instance and of all records below it
     */
    private static Stream<ConfigDataProperty> collectProperties(
            final String namePrefix, final Record recordInstance, final Traversal traversal) {
        return Arrays.stream(recordInstance.getClass().getRecordComponents()).flatMap(component -> {
            final String propertyName = getPropertyNameForConfigDataProperty(namePrefix, component);
            final boolean nested = isNestedConfig(component.getType());

            // a component that holds a nested config data object has no value of its own, so it is not a property that
            // can be set and is only reported when every component is asked for
            final Stream<ConfigDataProperty> property = traversal == Traversal.ALL_COMPONENTS || !nested
                    ? Stream.of(new ConfigDataProperty(propertyName, component, recordInstance))
                    : Stream.empty();

            // Reading the value is the only way to walk into a record, so a component that is not walked into is left
            // alone and a caller that is only interested in the metadata of a property never causes the value to be
            // read.
            //
            // The accessibility is checked first since a config data object may live in a package that its module does
            // not export to this one. Nothing inside such a record can be read either, so there is nothing to report
            // for it and it is treated as a leaf. A nested config data object can default to null, so the pattern
            // match doubles as the null check.
            final boolean descend =
                    traversal == Traversal.ALL_COMPONENTS ? component.getType().isRecord() : nested;
            if (descend
                    && component.getAccessor().canAccess(recordInstance)
                    && getPropertyValue(component, recordInstance) instanceof final Record nestedInstance) {
                return Stream.concat(property, collectProperties(propertyName, nestedInstance, traversal));
            }
            return property;
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
     * @param owner        the record instance that declares the property. For a property of a nested config data
     *                     object this is the nested record instance and not the root config data object
     */
    public record ConfigDataProperty(String propertyName, RecordComponent component, Record owner) {

        /**
         * Returns the value of the property. The value is read on demand, so a caller that is only interested in the
         * metadata of a property never accesses the config data object it belongs to.
         *
         * @return the value of the property
         */
        public Object propertyValue() {
            return getPropertyValue(component, owner);
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

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Central interface to provide configuration properties. The API provides different methods how properties can be
 * accessed.
 */
public interface Configuration {

    /**
     * A constant that can be used to define an empty list.
     */
    String EMPTY_LIST = "[]";

    /**
     * Returns a {@link Stream} of all available property names.
     *
     * @return the stream of all property names
     */
    @NonNull
    Stream<String> getPropertyNames();

    /**
     * Checks if a property with the given name exists and returns true in that case.
     *
     * @param propertyName the name of the property that should be checked
     * @return true if the property exists, false otherwise
     */
    boolean exists(@NonNull String propertyName);

    /**
     * Checks if the property with the given name is a list property.
     * @param propertyName the name of the property that should be checked
     * @return true if the property is a list property, false otherwise
     */
    boolean isListValue(@NonNull String propertyName);

    /**
     * Returns the {@link String} value of the property with the given name.
     *
     * @param propertyName the name of the property
     * @return the value of the property
     * @throws NoSuchElementException if the property does not exist.
     */
    @Nullable
    String getValue(@NonNull String propertyName) throws NoSuchElementException;

    /**
     * Returns the {@link String} value of the property with the given name or the given default value if the property
     * does not exist.
     *
     * @param propertyName the name of the property
     * @param defaultValue the default value that will be used if the property does not exist.
     * @return the value of the property or the given default value if the property does not exist
     */
    @Nullable
    String getValue(@NonNull String propertyName, @Nullable String defaultValue);

    /**
     * Returns the value of the property with the given name.
     *
     * @param propertyName the name of the property
     * @param propertyType the type of the property
     * @param <T>          the generic type of the property
     * @return the value of the property
     * @throws NoSuchElementException   if the property does not exist.
     * @throws IllegalArgumentException if the raw {@code String} value of the property can not be converted to the
     *                                  given type
     */
    @Nullable
    <T> T getValue(@NonNull String propertyName, @NonNull Class<T> propertyType)
            throws NoSuchElementException, IllegalArgumentException;

    /**
     * Returns the value of the property with the given name or the given default value if the property does not exist.
     *
     * @param propertyName the name of the property
     * @param propertyType the type of the property
     * @param defaultValue the default value that will be used if the property does not exist.
     * @param <T>          the generic type of the property
     * @return the value of the property or the given default value if the property does not exist
     * @throws IllegalArgumentException if the raw {@code String} value of the property can not be converted to the
     *                                  given type
     */
    @Nullable
    <T> T getValue(@NonNull String propertyName, @NonNull Class<T> propertyType, @Nullable T defaultValue)
            throws IllegalArgumentException;

    /**
     * Returns a {@link List} of string elements of the property with the given name.
     *
     * @param propertyName the name of the property
     * @return a {@link List} of elements of the property with the given name
     * @throws NoSuchElementException   if the property does not exist
     * @throws IllegalArgumentException if the raw {@link String} value of the property can not be converted to a list
     *                                  or the given type
     */
    @Nullable
    List<String> getValues(@NonNull String propertyName);

    /**
     * Returns a {@link List} of string elements of the property with the given name or the given default {@link List}.
     *
     * @param propertyName the name of the property
     * @param defaultValue the default {@link List}
     * @return a {@link List} of elements of the property with the given name
     * @throws IllegalArgumentException if the raw {@link String} value of the property can not be converted to a list
     *                                  or the given type
     */
    @Nullable
    List<String> getValues(@NonNull String propertyName, @Nullable List<String> defaultValue);

    /**
     * Returns a {@link List} of elements of the property with the given name.
     *
     * @param propertyName the name of the property
     * @param propertyType the type of the elements
     * @param <T>          the generic type of the elements
     * @return a {@link List} of elements of the property with the given name
     * @throws NoSuchElementException   if the property does not exist
     * @throws IllegalArgumentException if the raw {@link String} value of the property can not be converted to a list
     *                                  or the given type
     */
    @Nullable
    <T> List<T> getValues(@NonNull String propertyName, @NonNull Class<T> propertyType)
            throws NoSuchElementException, IllegalArgumentException;

    /**
     * Returns a {@link List} of elements of the property with the given name or the given default {@link List}.
     *
     * @param propertyName the name of the property
     * @param propertyType the type of the elements
     * @param defaultValue the default {@link List}
     * @param <T>          the generic type of the elements
     * @return a {@link List} of elements of the property with the given name or the given default list
     * @throws IllegalArgumentException if the raw {@link String} value of the property can not be converted to a list
     *                                  or the given type
     */
    @Nullable
    <T> List<T> getValues(@NonNull String propertyName, @NonNull Class<T> propertyType, @Nullable List<T> defaultValue)
            throws IllegalArgumentException;

    /**
     * Returns a {@link Set} of string elements of the property with the given name.
     * The set has a stable iteration order.
     *
     * @param propertyName the name of the property
     * @return a {@link Set} of elements of the property with the given name
     * @throws NoSuchElementException   if the property does not exist.
     * @throws IllegalArgumentException if the raw {@link String} value of the property can not be converted to a set or
     *                                  the given type
     */
    @Nullable
    Set<String> getValueSet(@NonNull String propertyName);

    /**
     * Returns a {@link Set} of string elements of the property with the given name or the given default {@link Set}.
     * The set has a stable iteration order.
     *
     * @param propertyName the name of the property
     * @param defaultValue the default {@link Set}
     * @return a {@link Set} of elements of the property with the given name
     * @throws IllegalArgumentException if the raw {@link String} value of the property can not be converted to a set or
     *                                  the given type
     */
    @Nullable
    Set<String> getValueSet(@NonNull String propertyName, @Nullable Set<String> defaultValue);

    /**
     * Returns a {@link Set} of elements of the property with the given name.
     * The set has a stable iteration order.
     *
     * @param propertyName the name of the property
     * @param propertyType the type of the elements
     * @param <T>          the generic type of the elements
     * @return a {@link Set} of elements of the property with the given name
     * @throws NoSuchElementException   if the property does not exist.
     * @throws IllegalArgumentException if the raw {@link String} value of the property can not be converted to a set or
     *                                  the given type
     */
    @Nullable
    <T> Set<T> getValueSet(@NonNull String propertyName, @NonNull Class<T> propertyType)
            throws NoSuchElementException, IllegalArgumentException;

    /**
     * Returns a {@link Set} of elements of the property with the given name or the given default {@link Set}.
     * The set has a stable iteration order.
     *
     * @param propertyName the name of the property
     * @param propertyType the type of the elements
     * @param defaultValue the default {@link Set}
     * @param <T>          the generic type of the elements
     * @return a {@link Set} of elements of the property with the given name or the given default set
     * @throws IllegalArgumentException if the raw {@link String} value of the property can not be converted to a set or
     *                                  the given type
     */
    @Nullable
    <T> Set<T> getValueSet(@NonNull String propertyName, @NonNull Class<T> propertyType, @Nullable Set<T> defaultValue)
            throws IllegalArgumentException;

    /**
     * Returns a config data object of the given type. This is used to provide an object-oriented construct for
     * accessing config properties for a specific usecase. By doing so all configuration values for a specific service
     * can for example be stored in an immutable record instance that is used to access the values.
     * <p>
     * The given type defines the {@link Record} that defines the config properties. The annotations {@link ConfigData}
     * and {@link ConfigProperty} can be used in the record definition to specify the names and default values of
     * properties.The result of this method is an instance of the given {@link Record} that provides the values for all
     * properties that are specified in the record definition.
     *
     * @param type type of the record
     * @param <T>  type of the record
     * @return instance of the record that contains the config values for all config properties that are defined in the
     * record definition.
     */
    @NonNull
    <T extends Record> T getConfigData(@NonNull Class<T> type);

    /**
     * Returns all types that are registered as config data types (see {@link #getConfigData(Class)}).
     *
     * @return all types that are registered as config data types
     */
    @NonNull
    Collection<Class<? extends Record>> getConfigDataTypes();
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api.source;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * This interface is used to define sources of configuration properties. Implementations of the interface can be added
 * to the configuration setup by calling * {@link com.swirlds.config.api.ConfigurationBuilder#withSource(ConfigSource)}
 */
public interface ConfigSource {

    /**
     * The default ordinal that is used if an implementation does not provide an ordinal by its own. See {@link
     * ConfigSource#getOrdinal()}.
     */
    int DEFAULT_ORDINAL = 100;

    /**
     * Returns a set that contains all names of the properties that are provided by this source.
     *
     * @return the set of property names
     */
    @NonNull
    Set<String> getPropertyNames();

    /**
     * Returns the String value of the property with the given name.
     *
     * @param propertyName the name of the property
     * @return the string value of the property
     * @throws NoSuchElementException if the property with the given name is not defined in the source OR is a list property
     */
    @Nullable
    String getValue(@NonNull String propertyName) throws NoSuchElementException;

    /**
     * Checks if the property with the given name is a list property.
     *
     * @param propertyName the name of the property
     * @return {@code true} if the property is a list property, {@code false} otherwise
     * @throws NoSuchElementException if the property with the given name is not defined in the source OR is not a list property
     */
    boolean isListProperty(@NonNull String propertyName) throws NoSuchElementException;

    /**
     * Returns the list value of the property with the given name.
     *
     * @param propertyName the name of the property
     * @return the list value of the property
     * @throws NoSuchElementException if the property with the given name is not defined in the source OR is not a list property
     */
    @NonNull
    List<String> getListValue(@NonNull String propertyName) throws NoSuchElementException;

    /**
     * Returns the ordinal. The ordinal is used to define a priority order of all config sources while the config source
     * with the highest ordinal has the highest priority. A config source will overwrite values of properties that are
     * already defined by a config source with a lower ordinal. If 2 instances have the same ordinal number the api does
     * not define what instance will have the higher priority.
     *
     * @return the ordinal
     */
    default int getOrdinal() {
        return DEFAULT_ORDINAL;
    }

    /**
     * Returns the name of the config source. A name must not be unique.
     *
     * @return the name
     */
    @NonNull
    default String getName() {
        return getClass().getName();
    }
}

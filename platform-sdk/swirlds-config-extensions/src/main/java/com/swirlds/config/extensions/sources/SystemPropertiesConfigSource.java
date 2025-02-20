// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.sources;

import com.swirlds.base.ArgumentUtils;
import com.swirlds.config.api.source.ConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * A {@link com.swirlds.config.api.source.ConfigSource} implementation that provides values from the system environment.
 * The class is defined as a singleton.
 */
public final class SystemPropertiesConfigSource implements ConfigSource {

    private static SystemPropertiesConfigSource instance;

    private SystemPropertiesConfigSource() {}

    /**
     * Returns the singleton.
     *
     * @return the singleton
     */
    public static SystemPropertiesConfigSource getInstance() {
        if (instance == null) {
            synchronized (SystemPropertiesConfigSource.class) {
                if (instance != null) {
                    return instance;
                }
                instance = new SystemPropertiesConfigSource();
            }
        }
        return instance;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Set<String> getPropertyNames() {
        return System.getProperties().stringPropertyNames();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getValue(@NonNull final String propertyName) {
        ArgumentUtils.throwArgBlank(propertyName, "propertyName");
        if (!getPropertyNames().contains(propertyName)) {
            throw new NoSuchElementException("Property " + propertyName + " is not defined");
        }
        return System.getProperty(propertyName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isListProperty(@NonNull final String propertyName) throws NoSuchElementException {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<String> getListValue(@NonNull final String propertyName) throws NoSuchElementException {
        return List.of();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrdinal() {
        return ConfigSourceOrdinalConstants.SYSTEM_PROPERTIES_ORDINAL;
    }
}

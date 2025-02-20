// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.sources;

import com.swirlds.base.ArgumentUtils;
import com.swirlds.config.api.source.ConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Abstract implementation of {@link ConfigSource}.
 */
public abstract class AbstractConfigSource implements ConfigSource {

    /**
     * Provides all properties of this config source as {@link Map} instance.
     *
     * @return all properties of this config
     */
    protected abstract Map<String, String> getInternalProperties();

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public final Set<String> getPropertyNames() {
        return getInternalProperties().keySet();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final String getValue(@NonNull final String propertyName) {
        ArgumentUtils.throwArgBlank(propertyName, "propertyName");
        if (!getInternalProperties().containsKey(propertyName)) {
            throw new NoSuchElementException("Property " + propertyName + " is not defined");
        }
        return getInternalProperties().get(propertyName);
    }

    /**
     * This implementation always returns {@code false}.
     */
    @Override
    public boolean isListProperty(@NonNull final String propertyName) throws NoSuchElementException {
        return false;
    }

    /**
     * This implementation always returns an empty list.
     */
    @NonNull
    @Override
    public List<String> getListValue(@NonNull final String propertyName) throws NoSuchElementException {
        return List.of();
    }
}

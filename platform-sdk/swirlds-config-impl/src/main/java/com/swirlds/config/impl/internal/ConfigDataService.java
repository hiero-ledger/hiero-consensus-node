// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * This service provides instances of config data objects (see {@link com.swirlds.config.api.ConfigData} for a detailed
 * description).
 */
class ConfigDataService implements ConfigLifecycle {

    /**
     * The factory that create data object instances.
     */
    private final ConfigDataFactory configDataFactory;

    /**
     * A set of all registered data object types.
     */
    private final Queue<Class<? extends Record>> registeredTypes;

    /**
     * A map that contains all created data objects.
     */
    private final Map<Class<? extends Record>, Record> configDataCache;

    /**
     * Defines if the service is initialized.
     */
    private boolean initialized = false;

    ConfigDataService(@NonNull final Configuration configuration, @NonNull final ConverterService converterService) {
        this.configDataFactory = new ConfigDataFactory(configuration, converterService);
        configDataCache = new HashMap<>();
        registeredTypes = new ConcurrentLinkedQueue<>();
    }

    /**
     * Adds the given type as a config data object type. This is only possible if the service is not initialized.
     *
     * @param type the type that should be added as a supported config data object
     * @param <T>  generic type of the config data object
     */
    <T extends Record> void addConfigDataType(@NonNull final Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        throwIfInitialized();
        registeredTypes.add(type);
    }

    @Override
    public void init() {
        throwIfInitialized();
        registeredTypes.stream().forEach(type -> {
            try {
                final Record dataInstance = configDataFactory.createConfigInstance(type);
                configDataCache.put(type, dataInstance);
            } catch (final Exception e) {
                throw new IllegalStateException("Can not create config data for record type '" + type + "'", e);
            }
        });
        initialized = true;
    }

    @Override
    public void dispose() {
        registeredTypes.clear();
        configDataCache.clear();
        initialized = false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns the instance of the given config data type. If the given type is not registered (see {@link
     * #addConfigDataType(Class)}) the method throws an {@link IllegalArgumentException}.
     *
     * @param type the config data type
     * @param <T>  the config data type
     * @return the instance of the given config data type
     */
    @NonNull
    <T extends Record> T getConfigData(@NonNull final Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        throwIfNotInitialized();
        if (!configDataCache.containsKey(type)) {
            throw new IllegalArgumentException("No config data record available of type '" + type + "'");
        }
        return (T) configDataCache.get(type);
    }

    /**
     * Returns all config data types that are registered.
     *
     * @return all config data types that are registered
     */
    @NonNull
    public Collection<Class<? extends Record>> getConfigDataTypes() {
        return registeredTypes;
    }
}

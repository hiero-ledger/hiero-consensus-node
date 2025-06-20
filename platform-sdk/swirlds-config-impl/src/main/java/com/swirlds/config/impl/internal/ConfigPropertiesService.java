// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.swirlds.base.ArgumentUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Service that provides all loaded config properties.
 */
class ConfigPropertiesService implements ConfigLifecycle {

    /**
     * stores all config properties.
     */
    private final Map<String, String> internalProperties;

    /**
     * stores all config properties.
     */
    private final Map<String, List<String>> internalListProperties;

    /**
     * The services that is used to load all properties in the correct order.
     */
    private final ConfigSourceService configSourceService;

    /**
     * Defines if this service is initialized.
     */
    private boolean initialized = false;

    ConfigPropertiesService(@NonNull final ConfigSourceService configSourceService) {
        this.configSourceService = Objects.requireNonNull(configSourceService, "configSourceService must not be null");
        internalProperties = new HashMap<>();
        internalListProperties = new HashMap<>();
    }

    @Override
    public void init() {
        throwIfInitialized();
        configSourceService.getSources().forEach(configSource -> {
            final Set<String> propertyNames = configSource.getPropertyNames();
            propertyNames.forEach(propertyName -> {
                ArgumentUtils.throwArgBlank(propertyName, "propertyName");
                if (configSource.isListProperty(propertyName)) {
                    internalListProperties.put(propertyName, configSource.getListValue(propertyName));
                } else {
                    internalProperties.put(propertyName, configSource.getValue(propertyName));
                }
            });
        });
        initialized = true;
    }

    @Override
    public void dispose() {
        internalProperties.clear();
        internalListProperties.clear();
        initialized = false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @NonNull
    Stream<String> getPropertyNames() {
        throwIfNotInitialized();
        return Stream.concat(internalProperties.keySet().stream(), internalListProperties.keySet().stream());
    }

    boolean containsKey(@NonNull final String propertyName) {
        throwIfNotInitialized();
        ArgumentUtils.throwArgBlank(propertyName, "propertyName");
        return internalProperties.containsKey(propertyName) || internalListProperties.containsKey(propertyName);
    }

    @Nullable
    String getProperty(@NonNull final String propertyName) {
        throwIfNotInitialized();
        ArgumentUtils.throwArgBlank(propertyName, "propertyName");
        return internalProperties.get(propertyName);
    }

    boolean isListProperty(@NonNull final String propertyName) {
        throwIfNotInitialized();
        ArgumentUtils.throwArgBlank(propertyName, "propertyName");
        return internalListProperties.containsKey(propertyName);
    }

    @NonNull
    List<String> getListProperty(@NonNull final String propertyName) {
        throwIfNotInitialized();
        ArgumentUtils.throwArgBlank(propertyName, "propertyName");
        return internalListProperties.get(propertyName);
    }
}

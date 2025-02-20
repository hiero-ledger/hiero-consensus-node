// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.swirlds.config.api.source.ConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Services that provides the config properties from sources (see {@link ConfigSource}).
 */
class ConfigSourceService implements ConfigLifecycle {

    /**
     * List of all sources that will be used to load config properties.
     */
    private final List<ConfigSource> sources;

    /**
     * Defines if this service is initialized.
     */
    private boolean initialized = false;

    ConfigSourceService() {
        this.sources = new CopyOnWriteArrayList<>();
    }

    void addConfigSource(@NonNull final ConfigSource configSource) {
        throwIfInitialized();
        Objects.requireNonNull(configSource, "configSource must not be null");
        sources.add(configSource);
    }

    @NonNull
    Stream<ConfigSource> getSources() {
        throwIfNotInitialized();
        return sources.stream();
    }

    @Override
    public void init() {
        throwIfInitialized();
        sources.sort(Comparator.comparingInt(ConfigSource::getOrdinal));
        initialized = true;
    }

    @Override
    public void dispose() {
        sources.clear();
        initialized = false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }
}

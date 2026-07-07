// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config.internal;

import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.config.extensions.sources.ConfigMapping;
import com.swirlds.config.extensions.sources.MappedConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Adds mappings for configuration parameters that have changed their names, so that the old names can still be used and
 * supported.
 *
 * @see ConfigMapping
 * @see MappedConfigSource
 */
public final class ConfigMappings {
    private ConfigMappings() {}

    static final List<ConfigMapping> MAPPINGS = List.of(
            new ConfigMapping("recycleBin.dirName", "fileSystemManager.recycleBinDir"),
            new ConfigMapping("recycleBin.maximumFileAge", "fileSystemManager.recycleBinMaximumFileAge"),
            new ConfigMapping("recycleBin.collectionPeriod", "fileSystemManager.recycleBinCollectionPeriod"),
            new ConfigMapping("gossip.connectionServerThreadPriority", "thread.threadPrioritySync"),
            new ConfigMapping("gossip.hangingThreadDuration", "hangingThreadDuration"));

    /**
     * Add all known aliases to the provided config source
     *
     * @param configSource the source to add aliases to
     * @return the original source with added aliases
     */
    @NonNull
    public static ConfigSource addConfigMapping(@NonNull final ConfigSource configSource) {
        PlatformConfigUtils.logAppliedMappedProperties(configSource.getPropertyNames());
        final MappedConfigSource withAliases = new MappedConfigSource(configSource);
        MAPPINGS.forEach(withAliases::addMapping);

        return withAliases;
    }
}

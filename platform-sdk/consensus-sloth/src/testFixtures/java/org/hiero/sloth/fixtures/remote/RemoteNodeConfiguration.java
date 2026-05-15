// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.remote;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.sloth.fixtures.NodeConfiguration;
import org.hiero.sloth.fixtures.internal.AbstractNode.LifeCycle;
import org.hiero.sloth.fixtures.internal.AbstractNodeConfiguration;
import org.hiero.sloth.fixtures.internal.OverrideProperties;

/**
 * An implementation of {@link NodeConfiguration} for a remote SSH environment.
 */
public class RemoteNodeConfiguration extends AbstractNodeConfiguration {

    /** The event stream directory name within the working directory. */
    private static final String EVENT_STREAM_DIRECTORY = "hgcapp";

    /**
     * Constructor for the {@link RemoteNodeConfiguration} class.
     *
     * @param lifecycleSupplier a supplier that provides the current lifecycle state of the node
     * @param overrideProperties override properties to initialize the configuration with
     * @param remoteWorkDir the working directory on the remote host for this node
     */
    public RemoteNodeConfiguration(
            @NonNull final Supplier<LifeCycle> lifecycleSupplier,
            @NonNull final OverrideProperties overrideProperties,
            @NonNull final String remoteWorkDir) {
        super(lifecycleSupplier, overrideProperties);
        requireNonNull(remoteWorkDir, "remoteWorkDir must not be null");
        this.overrideProperties.withConfigValue(
                EventConfig_.EVENTS_LOG_DIR, Path.of(remoteWorkDir, EVENT_STREAM_DIRECTORY));
    }

    /**
     * Returns the overridden properties for this node configuration.
     *
     * @return an unmodifiable map of overridden properties
     */
    @NonNull
    Map<String, String> overrideProperties() {
        return Collections.unmodifiableMap(overrideProperties.properties());
    }
}

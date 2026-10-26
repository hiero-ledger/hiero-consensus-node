// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.swirlds.merkledb.config.MerkleDbConfig_;
import com.swirlds.platform.builder.ModulesConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.hiero.consensus.BasicConfig_;
import org.hiero.consensus.PathsConfig_;
import org.hiero.consensus.event.stream.config.EventConfig_;
import org.hiero.consensus.metrics.config.MetricsConfig_;
import org.hiero.consensus.pces.config.PcesConfig_;
import org.hiero.consensus.pces.config.PcesFileWriterType;
import org.hiero.consensus.reconnect.config.ReconnectConfig_;
import org.hiero.consensus.state.config.StateConfig_;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle;
import org.hiero.otter.fixtures.internal.AbstractNodeConfiguration;
import org.hiero.otter.fixtures.internal.OverrideProperties;
import org.hiero.otter.fixtures.turtle.gossip.TurtleGossipModule;

/**
 * {@link NodeConfiguration} implementation for a Turtle node.
 */
public class TurtleNodeConfiguration extends AbstractNodeConfiguration {

    /**
     * Constructor for the {@link TurtleNodeConfiguration} class.
     *
     * @param lifeCycleSupplier a supplier that provides the current lifecycle state of the node
     * @param outputDirectory the directory where the node output will be stored, like saved state and so on
     */
    public TurtleNodeConfiguration(
            @NonNull final Supplier<LifeCycle> lifeCycleSupplier,
            @NonNull final OverrideProperties overrideProperties,
            @NonNull final Path outputDirectory) {
        super(lifeCycleSupplier, overrideProperties);

        this.defaultProperties.withConfigValue(MetricsConfig_.DISABLE_METRICS_OUTPUT, true);
        this.defaultProperties.withConfigValue(BasicConfig_.JVM_PAUSE_DETECTOR_SLEEP_MS, 0);
        this.defaultProperties.withConfigValue(MerkleDbConfig_.INITIAL_CAPACITY, 10_000L);
        this.defaultProperties.withConfigValue(MerkleDbConfig_.MAX_NUM_OF_KEYS, 100_000L);
        this.defaultProperties.withConfigValue(PcesConfig_.LIMIT_REPLAY_FREQUENCY, false);
        this.defaultProperties.withConfigValue(PcesConfig_.PCES_FILE_WRITER_TYPE, PcesFileWriterType.OUTPUT_STREAM);
        this.defaultProperties.withConfigValue(EventConfig_.EVENTS_LOG_DIR, outputDirectory.resolve("hgcapp"));
        this.defaultProperties.withConfigValue(PathsConfig_.SAVED_STATE_DIR, outputDirectory.resolve("data/saved"));
        this.defaultProperties.withConfigValue(PathsConfig_.SETTINGS_USED_DIR, outputDirectory);
        this.defaultProperties.withConfigValue(PathsConfig_.KEYS_DIR_PATH, outputDirectory.resolve("data/keys"));
        this.defaultProperties.withConfigValue(StateConfig_.SAVE_STATE_ASYNC, false);
        this.defaultProperties.withConfigValue(ReconnectConfig_.ACTIVE, false);
        this.defaultProperties.withConfigValue(
                ModulesConfig_.GOSSIP, TurtleGossipModule.class.getModule().getName());
    }
}

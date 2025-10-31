// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.swirlds.common.config.StateCommonConfig_;
import com.swirlds.common.io.config.FileSystemManagerConfig_;
import com.swirlds.platform.config.BasicConfig_;
import com.swirlds.platform.config.PathsConfig_;
import com.swirlds.platform.event.preconsensus.PcesConfig_;
import com.swirlds.platform.event.preconsensus.PcesFileWriterType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;
import org.hiero.otter.fixtures.internal.AbstractNodeConfiguration;
import org.hiero.otter.fixtures.internal.OverrideProperties;

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

        this.overrideProperties.withConfigValue(BasicConfig_.JVM_PAUSE_DETECTOR_SLEEP_MS, 0);
        this.overrideProperties.withConfigValue(PcesConfig_.LIMIT_REPLAY_FREQUENCY, false);
        this.overrideProperties.withConfigValue(PcesConfig_.PCES_FILE_WRITER_TYPE, PcesFileWriterType.OUTPUT_STREAM);
        this.overrideProperties.withConfigValue(EventConfig_.EVENTS_LOG_DIR, outputDirectory.resolve("hgcapp"));
        this.overrideProperties.withConfigValue(
                StateCommonConfig_.SAVED_STATE_DIRECTORY, outputDirectory.resolve("data/saved"));
        this.overrideProperties.withConfigValue(FileSystemManagerConfig_.ROOT_PATH, outputDirectory.resolve("data"));
        this.overrideProperties.withConfigValue(PathsConfig_.SETTINGS_USED_DIR, outputDirectory);
        this.overrideProperties.withConfigValue(PathsConfig_.KEYS_DIR_PATH, outputDirectory.resolve("data/keys"));
        this.overrideProperties.withConfigValue(PathsConfig_.APPS_DIR_PATH, outputDirectory.resolve("data/apps"));
        this.overrideProperties.withConfigValue(
                PathsConfig_.MARKER_FILES_DIR, outputDirectory.resolve("data/saved/marker_files"));
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.swirlds.common.config.StateCommonConfig_;
import com.swirlds.common.io.config.FileSystemManagerConfig_;
import com.swirlds.platform.config.BasicConfig_;
import com.swirlds.platform.config.PathsConfig_;
import com.swirlds.platform.event.preconsensus.PcesConfig_;
import com.swirlds.platform.event.preconsensus.PcesFileWriterType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;
import org.hiero.otter.fixtures.internal.AbstractNodeConfiguration;

/**
 * {@link NodeConfiguration} implementation for a Turtle node.
 */
public class TurtleNodeConfiguration extends AbstractNodeConfiguration {

    /**
     * Constructor for the {@link TurtleNodeConfiguration} class.
     * <p>If this constructor is used, the {@link #setOutputDirectory(Path)} must be called before the node is started.
     *
     * @param lifeCycleSupplier a supplier that provides the current lifecycle state of the node
     */
    public TurtleNodeConfiguration(@NonNull final Supplier<LifeCycle> lifeCycleSupplier) {
        this(lifeCycleSupplier, null);
    }

    /**
     * Constructor for the {@link TurtleNodeConfiguration} class.
     *
     * @param lifeCycleSupplier a supplier that provides the current lifecycle state of the node
     * @param outputDirectory the directory where the node output will be stored, like saved state and so on
     */
    public TurtleNodeConfiguration(
            @NonNull final Supplier<LifeCycle> lifeCycleSupplier, @Nullable final Path outputDirectory) {
        super(lifeCycleSupplier);
        overriddenProperties.put(BasicConfig_.JVM_PAUSE_DETECTOR_SLEEP_MS, "0");
        overriddenProperties.put(PcesConfig_.LIMIT_REPLAY_FREQUENCY, "false");
        overriddenProperties.put(PcesConfig_.PCES_FILE_WRITER_TYPE, PcesFileWriterType.OUTPUT_STREAM.toString());
        if (outputDirectory != null) {
            setOutputDirectory(outputDirectory);
        }
    }

    public void setOutputDirectory(@NonNull final Path outputDirectory) {
        overriddenProperties.put(
                EventConfig_.EVENTS_LOG_DIR, outputDirectory.resolve("hgcapp").toString());
        overriddenProperties.put(
                StateCommonConfig_.SAVED_STATE_DIRECTORY,
                outputDirectory.resolve("data/saved").toString());
        overriddenProperties.put(
                FileSystemManagerConfig_.ROOT_PATH,
                outputDirectory.resolve("data").toString());
        overriddenProperties.put(PathsConfig_.SETTINGS_USED_DIR, outputDirectory.toString());
        overriddenProperties.put(
                PathsConfig_.KEYS_DIR_PATH, outputDirectory.resolve("data/keys").toString());
        overriddenProperties.put(
                PathsConfig_.APPS_DIR_PATH, outputDirectory.resolve("data/apps").toString());
        overriddenProperties.put(
                PathsConfig_.MARKER_FILES_DIR,
                outputDirectory.resolve("data/saved/marker_files").toString());
    }

}

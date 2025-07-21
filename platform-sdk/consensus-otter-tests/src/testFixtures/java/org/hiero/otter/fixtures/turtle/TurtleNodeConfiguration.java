// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.swirlds.common.config.StateCommonConfig_;
import com.swirlds.common.io.config.FileSystemManagerConfig_;
import com.swirlds.platform.config.BasicConfig_;
import com.swirlds.platform.config.PathsConfig_;
import com.swirlds.platform.event.preconsensus.PcesConfig_;
import com.swirlds.platform.event.preconsensus.PcesFileWriterType;
import com.swirlds.platform.wiring.PlatformSchedulersConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;
import org.hiero.otter.fixtures.internal.AbstractNodeConfiguration;

/**
 * {@link NodeConfiguration} implementation for a Turtle node.
 */
public class TurtleNodeConfiguration extends AbstractNodeConfiguration<TurtleNodeConfiguration> {

    private final String outputDirectory;

    /**
     * Constructor for the {@link TurtleNodeConfiguration} class.
     *
     * @param lifeCycleSupplier a supplier that provides the current lifecycle state of the node
     * @param outputDirectory the directory where the node output will be stored, like saved state and so on
     */
    public TurtleNodeConfiguration(
            @NonNull final Supplier<LifeCycle> lifeCycleSupplier, @NonNull final Path outputDirectory) {
        super(lifeCycleSupplier);
        this.outputDirectory = outputDirectory.toString();
        setTurtleSpecificOverrides();
    }

    private void setTurtleSpecificOverrides() {
        overriddenProperties.put(PlatformSchedulersConfig_.CONSENSUS_EVENT_STREAM, "NO_OP");
        overriddenProperties.put(BasicConfig_.JVM_PAUSE_DETECTOR_SLEEP_MS, "0");
        overriddenProperties.put(StateCommonConfig_.SAVED_STATE_DIRECTORY, outputDirectory);
        overriddenProperties.put(FileSystemManagerConfig_.ROOT_PATH, outputDirectory);
        overriddenProperties.put(PathsConfig_.SETTINGS_USED_DIR, outputDirectory);
        overriddenProperties.put(PcesConfig_.LIMIT_REPLAY_FREQUENCY, "false");
        overriddenProperties.put(PcesConfig_.PCES_FILE_WRITER_TYPE, PcesFileWriterType.OUTPUT_STREAM.toString());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TurtleNodeConfiguration self() {
        return this;
    }

    /**
     * Gets the output directory for the Turtle node.
     *
     * @return the output directory as a string
     */
    public String getOutputDirectory() {
        return outputDirectory;
    }
}

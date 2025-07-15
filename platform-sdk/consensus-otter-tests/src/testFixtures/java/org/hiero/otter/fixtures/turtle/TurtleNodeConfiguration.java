// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static com.swirlds.config.extensions.export.ConfigExport.getPropertiesForConfigDataRecords;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.config.converter.SemanticVersionConverter;
import com.swirlds.common.config.StateCommonConfig_;
import com.swirlds.common.io.config.FileSystemManagerConfig_;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.config.BasicConfig_;
import com.swirlds.platform.config.PathsConfig_;
import com.swirlds.platform.event.preconsensus.PcesConfig_;
import com.swirlds.platform.event.preconsensus.PcesFileWriterType;
import com.swirlds.platform.wiring.PlatformSchedulersConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNodeConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * {@link NodeConfiguration} implementation for a Turtle node.
 */
public class TurtleNodeConfiguration extends AbstractNodeConfiguration<TurtleNodeConfiguration> {

    private final String outputDirectory;
    public Map<String, Object> nodeProperties = new HashMap<>();

    /**
     * Constructor for the {@link TurtleNodeConfiguration} class.
     *
     * @param outputDirectory the directory where the node output will be stored, like saved state and so on
     */
    public TurtleNodeConfiguration(@NonNull final Path outputDirectory) {
        this.outputDirectory = outputDirectory.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TurtleNodeConfiguration self() {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String get(@NotNull final String key) {
        if (overriddenProperties.containsKey(key)) {
            return overriddenProperties.get(key);
        }
        if (nodeProperties.containsKey(key)) {
            return nodeProperties.get(key).toString();
        }
        throw new IllegalArgumentException(String.format("Configuration key %s does not exist", key));
    }

    /**
     * Gets the output directory for the Turtle node.
     *
     * @return the output directory as a string
     */
    public String getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * Creates a configuration for the Turtle node using the overridden properties.
     *
     * @return the configuration for the Turtle node
     */
    @NonNull
    Configuration createConfiguration() {
        final Configuration nodeConfiguration = new TestConfigBuilder()
                .withConverter(SemanticVersion.class, new SemanticVersionConverter())
                .withValue(PlatformSchedulersConfig_.CONSENSUS_EVENT_STREAM, "NO_OP")
                .withValue(BasicConfig_.JVM_PAUSE_DETECTOR_SLEEP_MS, 0)
                .withValue(StateCommonConfig_.SAVED_STATE_DIRECTORY, outputDirectory)
                .withValue(FileSystemManagerConfig_.ROOT_PATH, outputDirectory)
                .withValue(PathsConfig_.SETTINGS_USED_DIR, outputDirectory)
                .withValue(PcesConfig_.LIMIT_REPLAY_FREQUENCY, false)
                .withValue(PcesConfig_.PCES_FILE_WRITER_TYPE, PcesFileWriterType.OUTPUT_STREAM.toString())
                .withSource(new SimpleConfigSource(overriddenProperties))
                .getOrCreateConfig();
        nodeProperties.clear();
        nodeProperties.putAll(getPropertiesForConfigDataRecords(nodeConfiguration));
        return nodeConfiguration;
    }
}

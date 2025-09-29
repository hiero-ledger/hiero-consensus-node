// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.logging;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.consensus.model.node.NodeId;

/**
 * Provides logging configurations and functionality for the Turtle framework.
 */
public class TurtleLogging {

    private final Path rootOutputDirectory;
    private final Map<NodeId, Path> nodeIdConfigurations = new ConcurrentHashMap<>();

    public TurtleLogging(@NonNull final Path rootOutputDirectory) {
        this.rootOutputDirectory = requireNonNull(rootOutputDirectory, "rootOutputDirectory must not be null");
        updateLogging();
    }

    public void addNodeLogging(@NonNull final NodeId nodeId, @NonNull final Path outputDirectory) {
        requireNonNull(nodeId, "nodeId cannot be null");
        requireNonNull(outputDirectory, "outputDirectory cannot be null");
        nodeIdConfigurations.put(nodeId, outputDirectory);
        updateLogging();
    }

    public void removeNodeLogging(@NonNull final NodeId nodeId) {
        nodeIdConfigurations.remove(nodeId);
        updateLogging();
    }

    private void updateLogging() {
        TurtleLogConfigBuilder.configure(rootOutputDirectory, nodeIdConfigurations);
    }
}

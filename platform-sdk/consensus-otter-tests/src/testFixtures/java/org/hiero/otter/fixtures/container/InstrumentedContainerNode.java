// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.internal.NetworkConfiguration;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * An implementation of {@link InstrumentedNode} for a containerized environment.
 */
public class InstrumentedContainerNode extends ContainerNode implements InstrumentedNode {

    private final Logger log = LogManager.getLogger();

    /**
     * Constructor for the {@link ContainerNode} class.
     *
     * @param selfId the unique identifier for this node
     * @param keysAndCerts the keys for the node
     * @param network the network this node is part of
     * @param dockerImage the Docker image to use for this node
     */
    public InstrumentedContainerNode(
            @NonNull final NodeId selfId,
            @NonNull final TimeManager timeManager,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final Network network,
            @NonNull final ImageFromDockerfile dockerImage,
            @NonNull final Path outputDirectory,
            @NonNull final NetworkConfiguration networkConfiguration) {
        super(selfId, timeManager, keysAndCerts, network, dockerImage, outputDirectory, networkConfiguration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void ping(@NonNull final String message) {
        log.warn("Pinging is not implemented yet.");
    }
}

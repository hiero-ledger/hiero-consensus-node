// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static org.hiero.otter.fixtures.internal.InstrumentedClasses.INSTRUMENTED_EVENT_CREATOR;

import com.swirlds.platform.config.ModuleConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.container.proto.PingRequest;
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
     * @param outputDirectory the directory where the node's output will be stored
     */
    public InstrumentedContainerNode(
            @NonNull final NodeId selfId,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final Network network,
            @NonNull final ImageFromDockerfile dockerImage,
            @NonNull final Path outputDirectory) {
        super(selfId, keysAndCerts, network, dockerImage, outputDirectory);
        configuration().set(ModuleConfig_.EVENT_CREATOR_MODULE, INSTRUMENTED_EVENT_CREATOR);
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    public void ping(@NonNull final String message) {
        throwIfNotIn(LifeCycle.RUNNING, "Cannot ping a node that is not running");
        log.info("Sending ping '{}' to node {}", message, selfId);
        final PingRequest request = PingRequest.newBuilder().setMessage(message).build();
        nodeCommBlockingStub.ping(request);
    }
}

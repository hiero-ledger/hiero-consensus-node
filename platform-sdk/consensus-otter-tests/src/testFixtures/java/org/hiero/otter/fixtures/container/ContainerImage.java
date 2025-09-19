// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static org.hiero.otter.fixtures.container.ContainerNetwork.NODE_IDENTIFIER_FORMAT;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_CONTROL_PORT;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.NODE_COMMUNICATION_PORT;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.getContainerControlDebugPort;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.getJavaToolOptions;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.getNodeCommunicationDebugPort;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * A small convenience wrapper around {@link GenericContainer} that applies common configuration for Otter test node
 * containers. It connects the container to the provided Docker {@link Network}.
 */
public class ContainerImage extends GenericContainer<ContainerImage> {

    /**
     * Constructs a new container instance and exposed the debug port as {@code 5005 + selfId}.
     *
     * @param dockerImage the Docker image to run
     * @param network the Docker network to attach the container to
     * @param selfId the selfId for the node
     * Note: Previously this class bind-mounted a local saved-state directory into the container. We now copy
     * files out of the container on demand instead of mounting a directory.
     */
    public ContainerImage(
            @NonNull final ImageFromDockerfile dockerImage,
            @NonNull final Network network,
            @NonNull final NodeId selfId) {
        super(dockerImage);

        final String alias = String.format(NODE_IDENTIFIER_FORMAT, selfId.id());
        final int containerControlDebugPort = getContainerControlDebugPort(selfId);
        final int nodeCommunicationDebugPort = getNodeCommunicationDebugPort(selfId);

        // Apply the common configuration expected by tests.
        // By default, the container wait for all ports listed, but we only want it to wait for the
        // container control port, because the node communication service is established later
        // by the test code with the init request.
        withNetwork(network)
                .withNetworkAliases(alias)
                .withExposedPorts(CONTAINER_CONTROL_PORT, NODE_COMMUNICATION_PORT)
                .waitingFor(Wait.forListeningPorts(CONTAINER_CONTROL_PORT, containerControlDebugPort));

        withEnv("JAVA_TOOL_OPTIONS", getJavaToolOptions(containerControlDebugPort));
        addFixedExposedPort(containerControlDebugPort, containerControlDebugPort);
        addFixedExposedPort(nodeCommunicationDebugPort, nodeCommunicationDebugPort);

        withWorkingDirectory(CONTAINER_APP_WORKING_DIR);
    }
}

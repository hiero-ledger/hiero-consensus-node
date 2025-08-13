// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import static org.hiero.otter.fixtures.container.ContainerNetwork.NODE_IDENTIFIER_FORMAT;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_CONTROL_PORT;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.NODE_COMMUNICATION_PORT;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.getContainerControlDebugPort;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.getJavaToolOptions;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.getNodeCommunicationDebugPort;

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
     * @param outputDirectory the local directory to bind to the container's saved state directory
     * @param savedStateDirectory the name of the directory in the container where saved state will be stored
     */
    public ContainerImage(
            @NonNull final ImageFromDockerfile dockerImage,
            @NonNull final Network network,
            @NonNull final NodeId selfId,
            @NonNull final Path outputDirectory,
            @NonNull final String savedStateDirectory) {
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

        // Create a local directory for saved state directory contents and
        // bind it to the saved state directory for the node in the container
        final Path localSavedStateDirectory = outputDirectory.resolve(savedStateDirectory);
        try {
            Files.createDirectories(localSavedStateDirectory);
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to create directory " + localSavedStateDirectory, e);
        }
        withFileSystemBind(localSavedStateDirectory.toAbsolutePath().toString(), "/" + savedStateDirectory);
        withEnv("JAVA_TOOL_OPTIONS", getJavaToolOptions(containerControlDebugPort));
        addFixedExposedPort(containerControlDebugPort, containerControlDebugPort);
        addFixedExposedPort(nodeCommunicationDebugPort, nodeCommunicationDebugPort);
    }
}

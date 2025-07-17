// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static org.hiero.otter.fixtures.container.ContainerNetwork.NODE_IDENTIFIER_FORMAT;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * A small convenience wrapper around {@link GenericContainer} that applies common configuration for Otter test node
 * containers. It connects the container to the provided Docker {@link Network}.
 */
public class ContainerImage extends GenericContainer<ContainerImage> {
    public static final int CONTROL_PORT = 8080;
    private static final int BASE_DEBUG_PORT = 5005;

    /**
     * Constructs a new container instance and exposed the debug port as {@code 5005 + selfId}.
     *
     * @param dockerImage the Docker image to run
     * @param network the Docker network to attach the container to
     * @param selfId the selfId for the node
     */
    public ContainerImage(
            @NonNull final ImageFromDockerfile dockerImage,
            @NonNull final Network network,
            @NonNull final NodeId selfId,
            @NonNull final Path outputDirectory,
            @NonNull final String savedStateDirectory) {
        super(dockerImage);

        final String alias = String.format(NODE_IDENTIFIER_FORMAT, selfId.id());
        final int debugPort = BASE_DEBUG_PORT + (int) selfId.id();

        // Apply the common configuration expected by tests
        withNetwork(network).withNetworkAliases(alias).withExposedPorts(CONTROL_PORT);

        // Create a local directory for saved state directory contents and
        // bind it to the saved state directory for the node in the container
        final Path localSavedStateDirectory = outputDirectory.resolve(savedStateDirectory);
        try {
            Files.createDirectories(localSavedStateDirectory);
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to create directory " + localSavedStateDirectory, e);
        }
        withFileSystemBind(localSavedStateDirectory.toAbsolutePath().toString(), "/" + savedStateDirectory);
        withEnv("JAVA_TOOL_OPTIONS", getJavaToolOptions(debugPort));
        addFixedExposedPort(debugPort, debugPort);
    }

    private static String getJavaToolOptions(final int debugPort) {
        return String.format(
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:%s -Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener",
                debugPort);
    }
}

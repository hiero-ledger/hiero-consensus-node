// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_CONTROL_PORT;

import com.hedera.pbj.grpc.helidon.PbjRouting;
import com.hedera.pbj.grpc.helidon.config.PbjConfig;
import io.helidon.webserver.WebServer;
import java.nio.file.Path;
import org.hiero.consensus.otter.docker.app.logging.DockerLogConfigBuilder;

/**
 * Main entry point for the Docker container application.
 * <p>
 * This class initializes and starts a Helidon {@link WebServer} that provides services via the {@link DockerManager}.
 * </p>
 */
public final class DockerMain {

    /** The underlying Helidon WebServer instance. */
    private final WebServer webServer;

    /**
     * Constructs a {@link DockerMain} instance.
     */
    public DockerMain() {
        final DockerManager dockerManager = new DockerManager();

        final PbjConfig pbjConfig =
                PbjConfig.builder().name("container-control").build();

        webServer = WebServer.builder()
                .port(CONTAINER_CONTROL_PORT)
                .addRouting(PbjRouting.builder().service(dockerManager))
                .addProtocol(pbjConfig)
                .build();
    }

    /**
     * Main method to start the WebServer.
     * <p>
     * This method initializes a {@link DockerMain} instance and starts the server, blocking until the server is
     * terminated.
     * </p>
     *
     * @param args command-line arguments (not used)
     */
    public static void main(final String[] args) {
        DockerLogConfigBuilder.configure(Path.of(CONTAINER_APP_WORKING_DIR), null);
        new DockerMain().startServer();
    }

    /**
     * Starts the WebServer and waits for its termination.
     * <p>
     * This method blocks the current thread until the server is terminated.
     * </p>
     */
    private void startServer() {
        webServer.start();

        // Block until the server is shut down
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_CONTROL_PORT;

import io.helidon.webserver.WebServer;
import java.nio.file.Path;
import org.hiero.consensus.otter.docker.app.logging.ControlProcessLogConfigBuilder;

/**
 * Main entry point for the Docker container application.
 * <p>
 * This class initializes and starts a gRPC server that provides services via the {@link DockerManager}.
 * </p>
 */
public final class DockerMain {

    /** The web server that serves the gRPC service. */
    private final WebServer grpcServer;

    /**
     * Constructs a {@link DockerMain} instance.
     */
    public DockerMain() {
        grpcServer = GrpcServerFactory.create(CONTAINER_CONTROL_PORT, new DockerManager());
    }

    /**
     * Main method to start the gRPC server.
     * <p>
     * This method initializes a {@link DockerMain} instance and starts the gRPC server. The server keeps the process
     * alive after this method has returned.
     * </p>
     *
     * @param args command-line arguments (not used)
     */
    public static void main(final String[] args) {
        ControlProcessLogConfigBuilder.configure(Path.of(CONTAINER_APP_WORKING_DIR));
        new DockerMain().startGrpcServer();
    }

    /**
     * Starts the gRPC server.
     * <p>
     * This method returns once the server accepts connections. The server runs on its own threads, which keep the
     * process alive.
     * </p>
     */
    private void startGrpcServer() {
        grpcServer.start();
    }
}

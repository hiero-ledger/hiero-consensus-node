// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.container.docker;

import static org.hiero.sloth.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;
import static org.hiero.sloth.fixtures.container.utils.ContainerConstants.CONTAINER_CONTROL_PORT;
import static org.hiero.sloth.fixtures.container.utils.ContainerConstants.ENV_SLOTH_WORKDIR;

import io.helidon.webserver.WebServer;
import java.nio.file.Path;
import org.hiero.sloth.fixtures.container.docker.logging.ControlProcessLogConfigBuilder;

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
        final int port = Integer.getInteger("sloth.control.port", CONTAINER_CONTROL_PORT);
        grpcServer = GrpcServerFactory.create(port, new DockerManager());
    }

    /**
     * Main method to start the gRPC server. The server keeps the process alive after this method has returned.
     *
     * @param args command-line arguments (not used)
     */
    public static void main(final String[] args) {
        final String workDir = System.getProperty(ENV_SLOTH_WORKDIR, CONTAINER_APP_WORKING_DIR);
        ControlProcessLogConfigBuilder.configure(Path.of(workDir));
        new DockerMain().startGrpcServer();
    }

    /**
     * Starts the gRPC server. This method returns once the server accepts connections. The server runs on its own
     * threads, which keep the process alive.
     */
    private void startGrpcServer() {
        grpcServer.start();
    }
}

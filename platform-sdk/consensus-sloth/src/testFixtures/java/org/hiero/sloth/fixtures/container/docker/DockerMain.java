// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.container.docker;

import static org.hiero.sloth.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;
import static org.hiero.sloth.fixtures.container.utils.ContainerConstants.CONTAINER_CONTROL_PORT;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.sloth.fixtures.container.docker.logging.ControlProcessLogConfigBuilder;

/**
 * Main entry point for the Docker container application.
 * <p>
 * This class initializes and starts a gRPC {@link Server} that provides services via the {@link DockerManager}.
 * </p>
 */
public final class DockerMain {

    /** The underlying gRPC server instance. */
    private final Server grpcServer;

    /**
     * Constructs a {@link DockerMain} instance.
     */
    public DockerMain() {
        final int port = Integer.getInteger("sloth.control.port", CONTAINER_CONTROL_PORT);
        grpcServer = ServerBuilder.forPort(port).addService(new DockerManager()).build();
    }

    /**
     * Main method to start the gRPC server.
     *
     * @param args command-line arguments (not used)
     * @throws IOException if an I/O error occurs while starting the server
     * @throws InterruptedException if the server is interrupted while waiting for termination
     */
    public static void main(final String[] args) throws IOException, InterruptedException {
        final String workDir = System.getProperty("sloth.workdir", CONTAINER_APP_WORKING_DIR);
        ControlProcessLogConfigBuilder.configure(Path.of(workDir));
        new DockerMain().startGrpcServer();
    }

    /**
     * Starts the gRPC server and waits for its termination.
     *
     * @throws IOException if an I/O error occurs while starting the server
     * @throws InterruptedException if the server is interrupted while waiting for termination
     */
    private void startGrpcServer() throws IOException, InterruptedException {
        grpcServer.start();
        grpcServer.awaitTermination();
    }
}

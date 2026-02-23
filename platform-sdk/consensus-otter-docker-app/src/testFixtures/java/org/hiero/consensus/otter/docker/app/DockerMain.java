// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_CONTROL_PORT;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import org.hiero.consensus.otter.docker.app.logging.ControlProcessLogConfigBuilder;

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
     * Constructs a {@link DockerMain} instance with a custom {@link ExecutorService}.
     */
    public DockerMain() {
        grpcServer = ServerBuilder.forPort(CONTAINER_CONTROL_PORT)
                .addService(new DockerManager())
                .build();
    }

    /**
     * Main method to start the gRPC server.
     * <p>
     * This method initializes a {@link DockerMain} instance and starts the gRPC server, blocking until the server is
     * terminated.
     * </p>
     *
     * @param args command-line arguments (not used)
     * @throws IOException if an I/O error occurs while starting the server
     * @throws InterruptedException if the server is interrupted while waiting for termination
     */
    public static void main(final String[] args) throws IOException, InterruptedException {
        ControlProcessLogConfigBuilder.configure(Path.of(CONTAINER_APP_WORKING_DIR));
        new DockerMain().startGrpcServer();
    }

    /**
     * Starts the gRPC server and waits for its termination.
     * <p>
     * This method blocks the current thread until the server is terminated.
     * </p>
     *
     * @throws IOException if an I/O error occurs while starting the server
     * @throws InterruptedException if the server is interrupted while waiting for termination
     */
    private void startGrpcServer() throws IOException, InterruptedException {
        grpcServer.start();
        grpcServer.awaitTermination();
    }
}

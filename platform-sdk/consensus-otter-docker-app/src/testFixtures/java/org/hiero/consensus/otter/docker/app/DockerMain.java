// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.hiero.consensus.otter.docker.app.logging.DockerLogConfigBuilder;

/**
 * Main entry point for the Docker container application.
 * <p>
 * This class initializes and starts a gRPC {@link Server} that provides services via the {@link DockerManager}.
 * </p>
 */
public final class DockerMain {

    /** Port on which the {@link org.hiero.otter.fixtures.container.proto.ContainerControlServiceGrpc} listens. */
    private static final int CONTAINER_CONTROL_SERVICE_PORT = 8080;

    /** The underlying gRPC server instance. */
    private final Server grpcServer;

    /**
     * Constructs a {@link DockerMain} instance with a custom {@link ExecutorService}.
     *
     * @param dispatchExecutor the {@link ExecutorService} to use for managing threads in the gRPC server
     * @param backgroundExecutor the {@link Executor} to use for background tasks
     * @throws NullPointerException if {@code dispatchExecutor} is {@code null}
     */
    public DockerMain() {
        grpcServer = ServerBuilder.forPort(CONTAINER_CONTROL_SERVICE_PORT)
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
        DockerLogConfigBuilder.configure(Path.of(""), null);
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

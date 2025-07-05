// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Main entry point for the Docker container application.
 * <p>
 * This class initializes and starts a gRPC {@link Server} that provides services via the {@link DockerManager}.
 * </p>
 */
public final class DockerMain {

    /** Port on which the gRPC service listens. */
    private static final int GRPC_PORT = 8080;

    /** Default thread name for the gRCP service */
    private static final String DEFAULT_GRPC_THREAD_NAME = "grpc-outbound-dispatcher";

    /** The underlying gRPC server instance. */
    private final Server grpcServer;

    /**
     * Constructs a {@link DockerMain} instance using the default single-threaded executor.
     */
    private DockerMain() {
        this(createDefaultExecutor());
    }

    /**
     * Constructs a {@link DockerMain} instance with a custom {@link ExecutorService}.
     *
     * @param executor the {@link ExecutorService} to use for managing threads in the gRPC server
     * @throws NullPointerException if {@code executor} is {@code null}
     */
    public DockerMain(@NonNull final ExecutorService executor) {
        grpcServer = ServerBuilder.forPort(GRPC_PORT)
                .addService(new DockerManager(executor))
                .build();
    }

    /**
     * Creates the default {@link ExecutorService} for the gRPC server.
     * <p>
     * The default executor is a single-threaded executor
     * </p>
     *
     * @return a single-threaded {@link ExecutorService} with custom thread factory
     */
    private static ExecutorService createDefaultExecutor() {
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, DEFAULT_GRPC_THREAD_NAME);
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    /**
     * Main method to start the gRPC server.
     * <p>
     * This method initializes a {@link DockerMain} instance and starts the gRPC server,
     * blocking until the server is terminated.
     * </p>
     *
     * @param args command-line arguments (not used)
     * @throws IOException if an I/O error occurs while starting the server
     * @throws InterruptedException if the server is interrupted while waiting for termination
     */
    public static void main(final String[] args) throws IOException, InterruptedException {
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

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
 * Boots a {@link DockerApp} inside a minimal gRPC wrapper.
 * <p>
 * The class is a tiny self-contained launcher that exposes only a single
 * {@code start()} RPC through which a test harness can supply the information
 * required to bootstrap a {@link DockerApp}. All mutable state is kept inside
 * the singleton instance and never published outside this class.
 */
public final class DockerInit {

    /** Singleton instance exposed for the gRPC service. */
    static final DockerInit INSTANCE = new DockerInit();

    /** Port on which the gRPC service listens. */
    private static final int GRPC_PORT = 8080;

    /** Underlying gRPC server. */
    private final Server grpcServer;

    private DockerInit() {
        this(createDefaultExecutor());
    }

    public DockerInit(@NonNull final ExecutorService executor) {
        grpcServer = ServerBuilder.forPort(GRPC_PORT)
                .addService(new DockerService(executor))
                .build();
    }

    private static ExecutorService createDefaultExecutor() {
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, "grpc-outbound-dispatcher");
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    public static void main(final String[] args) throws IOException, InterruptedException {
        INSTANCE.startGrpcServer();
    }

    private void startGrpcServer() throws IOException, InterruptedException {
        grpcServer.start();
        grpcServer.awaitTermination();
    }
}

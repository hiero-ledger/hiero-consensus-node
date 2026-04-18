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
        grpcServer = ServerBuilder.forPort(CONTAINER_CONTROL_PORT)
                .addService(new DockerManager())
                .build();
    }

    /**
     * Main method to start the gRPC server.
     *
     * @param args command-line arguments (not used)
     * @throws IOException if an I/O error occurs while starting the server
     * @throws InterruptedException if the server is interrupted while waiting for termination
     */
    public static void main(final String[] args) throws IOException, InterruptedException {
        registerAccpIfAvailable();
        ControlProcessLogConfigBuilder.configure(Path.of(CONTAINER_APP_WORKING_DIR));
        new DockerMain().startGrpcServer();
    }

    /**
     * Registers the Amazon Corretto Crypto Provider (ACCP) at position 1 in the JCA provider chain,
     * if available on the classpath. ACCP accelerates classical crypto operations (RSA, ECDH, AES-GCM)
     * by delegating to AWS-LC native implementations. If ACCP is not on the classpath, this is a no-op.
     */
    private static void registerAccpIfAvailable() {
        try {
            final Class<?> accpClass =
                    Class.forName("com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider");
            final java.security.Provider accp =
                    (java.security.Provider) accpClass.getField("INSTANCE").get(null);
            java.security.Security.insertProviderAt(accp, 1);
            System.out.println("ACCP registered: " + accp.getName() + " " + accp.getVersionStr());

            // Override default signing implementations to use ACCP instead of BC/SunEC/Sodium
            org.hiero.consensus.crypto.SigningFactory.setDefaultImplementation(
                    org.hiero.consensus.crypto.SigningSchema.RSA,
                    org.hiero.consensus.crypto.SigningImplementation.RSA_ACCP);
            org.hiero.consensus.crypto.SigningFactory.setDefaultImplementation(
                    org.hiero.consensus.crypto.SigningSchema.ED25519,
                    org.hiero.consensus.crypto.SigningImplementation.ED25519_ACCP);
            System.out.println("Signing implementations overridden to ACCP");
        } catch (final Throwable t) {
            // ACCP not on classpath — skip
        }
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

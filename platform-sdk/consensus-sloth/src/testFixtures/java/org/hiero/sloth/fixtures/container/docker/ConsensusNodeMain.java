// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.container.docker;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static org.hiero.sloth.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.sloth.fixtures.container.docker.logging.DockerLogConfigBuilder;
import org.hiero.sloth.fixtures.container.docker.platform.NodeCommunicationService;

/**
 * Main entry point for the Consensus Node application. This application provides the
 * {@link org.hiero.sloth.fixtures.container.proto.NodeCommunicationServiceGrpc} which runs the consensus node.
 */
public class ConsensusNodeMain {

    /**
     * The name of the marker file to write when the
     * {@link org.hiero.sloth.fixtures.container.proto.NodeCommunicationServiceGrpc} is ready to accept requests.
     */
    public static final String STARTED_MARKER_FILE_NAME = "consensus-node-started.marker";

    /**
     * The marker file to write when the {@link org.hiero.sloth.fixtures.container.proto.NodeCommunicationServiceGrpc}
     * is ready to accept requests.
     */
    public static final Path STARTED_MARKER_FILE =
            Path.of(CONTAINER_APP_WORKING_DIR).resolve(STARTED_MARKER_FILE_NAME);

    /** Port on which the {@link org.hiero.sloth.fixtures.container.proto.NodeCommunicationServiceGrpc} listens. */
    private static final int NODE_COMM_SERVICE_PORT = 8081;

    /** Logger */
    private static final Logger log = LogManager.getLogger(ConsensusNodeMain.class);

    /**
     * Main method to start the Consensus Node application.
     *
     * @param args command line arguments; expects a single argument representing the node's ID
     */
    public static void main(final String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ConsensusNodeMain <selfId>");
        }
        final long id = Long.parseLong(args[0]);
        final NodeId selfId = NodeId.of(id);

        DockerLogConfigBuilder.configure(Path.of(CONTAINER_APP_WORKING_DIR), selfId);
        registerAccpIfAvailable();

        final NodeCommunicationService nodeCommunicationService = new NodeCommunicationService(selfId);

        log.info(STARTUP.getMarker(), "Starting ConsensusNodeMain");
        // Start the consensus node manager gRPC server
        final Server nodeGrpcServer = ServerBuilder.forPort(NODE_COMM_SERVICE_PORT)
                .addService(nodeCommunicationService)
                .build();
        try {
            nodeGrpcServer.start();
            writeStartedMarkerFile();
            nodeGrpcServer.awaitTermination();
        } catch (final IOException ie) {
            log.error(STARTUP.getMarker(), "Failed to start the gRPC server for the consensus node manager", ie);
            System.exit(-1);
        } catch (final InterruptedException e) {
            // Only warn, because we expect this exception when we interrupt the thread on a kill request
            log.warn(STARTUP.getMarker(), "Interrupted while running the consensus node manager gRPC server", e);
            Thread.currentThread().interrupt();
            System.exit(-1);
        }
    }

    /**
     * Registers the Amazon Corretto Crypto Provider (ACCP) at position 1 in the JCA provider chain,
     * if available on the classpath. ACCP accelerates classical crypto operations (RSA, ECDH, AES-GCM,
     * HMAC, SHA) by delegating to AWS-LC native implementations. At position 1, SunJSSE picks up ACCP
     * for the symmetric crypto inside TLS automatically. Signing defaults are also overridden so event
     * signatures route through ACCP. No-op if ACCP is not on the classpath.
     */
    private static void registerAccpIfAvailable() {
        try {
            final Class<?> accpClass =
                    Class.forName("com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider");
            final java.security.Provider accp =
                    (java.security.Provider) accpClass.getField("INSTANCE").get(null);
            java.security.Security.insertProviderAt(accp, 1);
            log.info(STARTUP.getMarker(), "ACCP registered: {} {}", accp.getName(), accp.getVersionStr());

            // Diagnostic: print JVM version, total service count, all Signature services
            // (unfiltered), and probe Ed25519 / EdDSA directly. ACCP gates Ed25519 on
            // Utils.getJavaVersion() >= 15, but the registration may also be conditioned on
            // self-test results or AWS-LC native capabilities.
            log.info(
                    STARTUP.getMarker(),
                    "JVM java.specification.version='{}' java.version='{}'",
                    System.getProperty("java.specification.version"),
                    System.getProperty("java.version"));
            final java.util.Set<java.security.Provider.Service> services = accp.getServices();
            log.info(STARTUP.getMarker(), "ACCP getServices().size() = {}", services.size());
            services.stream()
                    .sorted(java.util.Comparator.comparing(
                            (java.security.Provider.Service s) -> s.getType() + "." + s.getAlgorithm()))
                    .forEach(s -> log.info(
                            STARTUP.getMarker(), "ACCP service: {}.{}", s.getType(), s.getAlgorithm()));
            for (final String[] probe : new String[][] {
                    {"Signature", "Ed25519"},
                    {"Signature", "EdDSA"},
                    {"Signature", "Ed25519ph"},
                    {"Signature", "RSASSA-PSS"},
                    {"KeyFactory", "Ed25519"},
                    {"KeyFactory", "EdDSA"},
                    {"KeyPairGenerator", "Ed25519"},
                    {"KeyPairGenerator", "EdDSA"}
            }) {
                final java.security.Provider.Service svc = accp.getService(probe[0], probe[1]);
                log.info(STARTUP.getMarker(), "ACCP probe {}.{} -> {}", probe[0], probe[1], svc);
            }

            org.hiero.consensus.crypto.SigningFactory.setDefaultImplementation(
                    org.hiero.consensus.crypto.SigningSchema.RSA,
                    org.hiero.consensus.crypto.SigningImplementation.RSA_ACCP);
            org.hiero.consensus.crypto.SigningFactory.setDefaultImplementation(
                    org.hiero.consensus.crypto.SigningSchema.ED25519,
                    org.hiero.consensus.crypto.SigningImplementation.ED25519_ACCP);
            log.info(
                    STARTUP.getMarker(),
                    "Signing override applied: RSA -> RSA_ACCP, ED25519 -> ED25519_ACCP");
        } catch (final Throwable t) {
            log.warn(
                    STARTUP.getMarker(),
                    "ACCP registration skipped: {}: {}",
                    t.getClass().getName(),
                    t.getMessage());
        }
    }

    /**
     * Writes a marker file to indicate that the service has started and can now accept requests.
     */
    private static void writeStartedMarkerFile() {
        try {
            if (new File(STARTED_MARKER_FILE.toString()).createNewFile()) {
                log.info(
                        STARTUP.getMarker(),
                        "Node Communication Service marker file written to {}",
                        STARTED_MARKER_FILE);
            } else {
                log.info(
                        STARTUP.getMarker(),
                        "Node Communication Service marker file already exists at {}",
                        STARTED_MARKER_FILE);
            }
        } catch (final IOException e) {
            log.error(STARTUP.getMarker(), "Failed to write Node Communication Service marker file", e);
            throw new RuntimeException("Failed to write Node Communication Service marker file", e);
        }
    }
}

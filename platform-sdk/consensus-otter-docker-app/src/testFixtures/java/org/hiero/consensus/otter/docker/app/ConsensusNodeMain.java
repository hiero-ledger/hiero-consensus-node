// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;

import com.hedera.hapi.platform.state.NodeId;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.otter.docker.app.logging.DockerLogConfigBuilder;
import org.hiero.consensus.otter.docker.app.platform.NodeCommunicationService;

/**
 * Main entry point for the Consensus Node application. This application provides the
 * {@link org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc} which runs the consensus node.
 */
public class ConsensusNodeMain {

    /**
     * The name of the marker file to write when the
     * {@link org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc} is ready to accept requests.
     */
    public static final String STARTED_MARKER_FILE_NAME = "consensus-node-started.marker";

    /**
     * The marker file to write when the {@link org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc}
     * is ready to accept requests.
     */
    public static final Path STARTED_MARKER_FILE =
            Path.of(CONTAINER_APP_WORKING_DIR).resolve(STARTED_MARKER_FILE_NAME);

    /** Port on which the {@link org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc} listens. */
    private static final int NODE_COMM_SERVICE_PORT = 8081;

    /** Logger */
    private static final Logger log = LogManager.getLogger(ConsensusNodeMain.class);

    public static void main(final String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ConsensusNodeMain <selfId>");
        }
        final long id = Long.parseLong(args[0]);
        final NodeId selfId = NodeId.newBuilder().id(id).build();

        DockerLogConfigBuilder.configure(Path.of(CONTAINER_APP_WORKING_DIR), selfId);

        final NodeCommunicationService nodeCommunicationService = new NodeCommunicationService(selfId);

        log.info("Starting ConsensusNodeMain");
        // Start the consensus node manager gRPC server
        final Server nodeGrpcServer = ServerBuilder.forPort(NODE_COMM_SERVICE_PORT)
                .addService(nodeCommunicationService)
                .build();
        try {
            nodeGrpcServer.start();
            writeStartedMarkerFile();
            nodeGrpcServer.awaitTermination();
        } catch (final IOException ie) {
            log.error("Failed to start the gRPC server for the consensus node manager", ie);
            System.exit(-1);
        } catch (final InterruptedException e) {
            // Only warn, because we expect this exception when we interrupt the thread on a kill request
            log.warn("Interrupted while running the consensus node manager gRPC server", e);
            Thread.currentThread().interrupt();
            System.exit(-1);
        }
    }

    /**
     * Writes a marker file to indicate that the service has started and can now accept requests.
     */
    private static void writeStartedMarkerFile() {
        try {
            if (new File(STARTED_MARKER_FILE.toString()).createNewFile()) {
                log.info("Node Communication Service marker file written to {}", STARTED_MARKER_FILE);
            } else {
                log.info("Node Communication Service marker file already exists at {}", STARTED_MARKER_FILE);
            }
        } catch (final IOException e) {
            log.error("Failed to write Node Communication Service marker file", e);
            throw new RuntimeException("Failed to write Node Communication Service marker file", e);
        }
    }
}

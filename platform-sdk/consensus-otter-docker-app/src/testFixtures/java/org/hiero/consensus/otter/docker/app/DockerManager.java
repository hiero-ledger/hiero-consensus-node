// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import com.google.protobuf.Empty;
import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.otter.docker.app.logging.DockerLogConfigBuilder;
import org.hiero.consensus.otter.docker.app.platform.NodeCommunicationService;
import org.hiero.otter.fixtures.ProtobufConverter;
import org.hiero.otter.fixtures.container.proto.ContainerControlServiceGrpc;
import org.hiero.otter.fixtures.container.proto.InitRequest;
import org.hiero.otter.fixtures.container.proto.KillImmediatelyRequest;

/**
 * gRPC service implementation for communication between the test framework and the container to start and stop the
 * consensus node.
 * <p>
 * This service handles incoming messages to initialize the {@link NodeCommunicationService} which handles communication
 * with the consensus node itself.
 */
public final class DockerManager extends ContainerControlServiceGrpc.ContainerControlServiceImplBase {

    /** Logger */
    private static final Logger log = LogManager.getLogger(DockerManager.class);

    /** Port on which the {@link org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc} listens. */
    private static final int NODE_COMM_SERVICE_PORT = 8081;

    /**
     * The ID of the consensus node in this container. The ID must not be changed even between restarts. In the future,
     * successive init calls should verify that the self ID is the same.
     */
    private NodeId selfId;

    /** Manages the consensus node and platform lifecycle */
    @Nullable
    private NodeCommunicationService nodeCommunicationService;

    /**
     * Thread that runs the {@link org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc}. {@code null}
     * when the service is not running.
     */
    @Nullable
    private Thread nodeManagerThread;

    /**
     * Initializes the consensus node manager and starts its gRPC server. Once this request has completed, the consensus
     * node manager gRPC service is available to receive requests from the test framework.
     *
     * @param request the initialization request containing the self node ID
     * @param responseObserver The observer used to confirm termination.
     */
    @Override
    public synchronized void init(
            @NonNull final InitRequest request, @NonNull final StreamObserver<Empty> responseObserver) {
        this.selfId = ProtobufConverter.toPbj(request.getSelfId());
        DockerLogConfigBuilder.configure(Path.of(""), selfId);

        if (nodeManagerThread != null) {
            log.error(
                    "Node manager thread is already running. The node must be shutdown with a kill request before starting it again.");
            responseObserver.onError(new IllegalStateException("Node manager thread is already running."));
            return;
        }

        nodeCommunicationService = new NodeCommunicationService(selfId);
        nodeManagerThread = new Thread(this::startAndRunConsensusNodeService, "ConsensusNodeGrpcServiceThread");
        nodeManagerThread.start();

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    private void startAndRunConsensusNodeService() {
        // Start the consensus node manager gRPC server
        final Server nodeGrpcServer = ServerBuilder.forPort(NODE_COMM_SERVICE_PORT)
                .addService(nodeCommunicationService)
                .build();
        try {
            nodeGrpcServer.start();
            nodeGrpcServer.awaitTermination();
        } catch (final IOException ie) {
            log.error("Failed to start the gRPC server for the consensus node manager", ie);
            throw new RuntimeException("Failed to start the gRPC server", ie);
        } catch (final InterruptedException e) {
            // Only warn, because we expect this exception when we interrupt the thread on a kill request
            log.warn("Interrupted while running the consensus node manager gRPC server", e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while running the consensus node manager gRPC server", e);
        }
    }

    /**
     * Immediately terminates the platform. The container and dispatcher are left intact to allow data to be gathered
     * for verification.
     *
     * @param request The request to terminate the platform.
     * @param responseObserver The observer used to confirm termination.
     */
    @Override
    public synchronized void killImmediately(
            @NonNull final KillImmediatelyRequest request, @NonNull final StreamObserver<Empty> responseObserver) {
        log.info("Received kill request: {}", request);
        try {
            if (nodeCommunicationService != null) {
                nodeCommunicationService.destroy();
            }

            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (final InterruptedException e) {
            log.error("Failed to kill the gRPC server for the consensus node manager", e);
            responseObserver.onError(e);
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}

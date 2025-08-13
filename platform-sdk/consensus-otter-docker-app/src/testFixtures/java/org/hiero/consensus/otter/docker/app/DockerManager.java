// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import com.google.protobuf.Empty;
import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
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

import static org.hiero.otter.fixtures.container.utils.ContainerConstants.getJavaToolOptions;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.getNodeCommunicationDebugPort;

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

    private static final String DOCKER_APP_JAR = "/opt/DockerApp/apps/DockerApp.jar";
    private static final String DOCKER_APP_LIBS = "/opt/DockerApp/lib/*";
    private static final String CONSENSUS_NODE_MAIN_CLASS =
            "org.hiero.consensus.otter.docker.app.ConsensusNodeMain";

    /**
     * The ID of the consensus node in this container. The ID must not be changed even between restarts. In the future,
     * successive init calls should verify that the self ID is the same.
     */
    private NodeId selfId;

    private Process process;

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
        final NodeId requestSelfId = ProtobufConverter.toPbj(request.getSelfId());
        if (attemptingToChangeSelfId(requestSelfId)) {
            log.error("Node ID cannot be changed after initialization. Current ID: {}, requested ID: {}",
                    selfId.id(), requestSelfId.id());
            responseObserver.onError(new IllegalStateException("Node ID cannot be changed after initialization."));
            return;
        }

        this.selfId = requestSelfId;
        DockerLogConfigBuilder.configure(Path.of(""), selfId);

        final ProcessBuilder processBuilder = new ProcessBuilder("java", "-cp", DOCKER_APP_JAR + ":" + DOCKER_APP_LIBS,
                CONSENSUS_NODE_MAIN_CLASS, String.valueOf(selfId.id()));

        // Set the debug port for the node communication service in the java environment variable.
        final int debugPort = getNodeCommunicationDebugPort(selfId);
        processBuilder.environment().put("JAVA_TOOL_OPTIONS", getJavaToolOptions(debugPort));
        processBuilder.inheritIO();

        try {
            process = processBuilder.start();
        } catch (final IOException e) {
            log.error("Failed to start the consensus node process", e);
            responseObserver.onError(e);
            return;
        }

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    private boolean attemptingToChangeSelfId(@NonNull final NodeId requestedSelfId) {
        return this.selfId != null && selfId.id() != requestedSelfId.id();
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
        if (process != null) {
            process.destroyForcibly();
        }
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }
}

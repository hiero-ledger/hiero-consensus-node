// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.platform;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.ERROR;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.helpers.Utils.createConfiguration;

import com.google.protobuf.Empty;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.otter.docker.app.EventMessageFactory;
import org.hiero.consensus.otter.docker.app.OutboundDispatcher;
import org.hiero.otter.fixtures.KeysAndCertsConverter;
import org.hiero.otter.fixtures.ProtobufConverter;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc.NodeCommunicationServiceImplBase;
import org.hiero.otter.fixtures.container.proto.QuiescenceRequest;
import org.hiero.otter.fixtures.container.proto.StartRequest;
import org.hiero.otter.fixtures.container.proto.SyntheticBottleneckRequest;
import org.hiero.otter.fixtures.container.proto.TransactionRequest;
import org.hiero.otter.fixtures.container.proto.TransactionRequestAnswer;
import org.hiero.otter.fixtures.logging.internal.InMemorySubscriptionManager;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Responsible for all gRPC communication between the test framework and the consensus node. This class acts as an
 * intermediary between the test framework and the consensus node.
 */
public class NodeCommunicationService extends NodeCommunicationServiceImplBase {

    /** Default thread name for the consensus node manager gRCP service */
    private static final String NODE_COMMUNICATION_THREAD_NAME = "grpc-outbound-dispatcher";

    /** Logger */
    private static final Logger log = LogManager.getLogger(NodeCommunicationService.class);

    /**
     * The ID of the consensus node in this container. The ID must not be changed even between restarts.
     */
    private final NodeId selfId;

    /** Executor service for handling the dispatched messages */
    private final ExecutorService dispatchExecutor;

    /** Executor for background tasks, such as monitoring the file system */
    private final Executor backgroundExecutor;

    /** Handles outgoing messages, may get called from different threads/callbacks */
    private volatile OutboundDispatcher dispatcher;

    /** Manages the consensus node, including setup, tear down, and all interactions in between. */
    private ConsensusNodeManager consensusNodeManager;

    /**
     * Constructs a {@link NodeCommunicationService} with the specified self ID.
     *
     * @param selfId the ID of this node, which must not change between restarts
     */
    public NodeCommunicationService(@NonNull final NodeId selfId) {
        this.selfId = requireNonNull(selfId);
        this.dispatchExecutor = createDispatchExecutor();
        this.backgroundExecutor = Executors.newCachedThreadPool();
    }

    /**
     * Creates the default {@link ExecutorService} for the node communication gRPC server.
     * <p>
     * The default executor is a single-threaded executor
     * </p>
     *
     * @return a single-threaded {@link ExecutorService} with custom thread factory
     */
    private static ExecutorService createDispatchExecutor() {
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, NODE_COMMUNICATION_THREAD_NAME);
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    /**
     * Starts the communication channel with the platform using the provided {@link StartRequest}.
     * <p>
     * This method initializes the {@link ConsensusNodeManager} and sets up listeners for platform events. Results are
     * sent back to the test framework via the {@link StreamObserver}.
     *
     * @param request The request containing details required to construct the platform.
     * @param responseObserver The observer used to send messages back to the test framework.
     * @throws StatusRuntimeException if the platform is already started, or if the request contains invalid arguments.
     */
    @Override
    public synchronized void start(
            @NonNull final StartRequest request, @NonNull final StreamObserver<EventMessage> responseObserver) {
        log.info(STARTUP.getMarker(), "Received start request: {}", request);

        if (isInvalidRequest(request, responseObserver)) {
            return;
        }

        if (consensusNodeManager != null) {
            responseObserver.onError(Status.ALREADY_EXISTS.asRuntimeException());
            log.info(ERROR.getMarker(), "Invalid request, platform already started: {}", request);
            return;
        }

        final Configuration platformConfig = createConfiguration(request.getOverriddenPropertiesMap());
        final Roster genesisRoster = ProtobufConverter.toPbj(request.getRoster());
        final SemanticVersion version = ProtobufConverter.toPbj(request.getVersion());
        final KeysAndCerts keysAndCerts = KeysAndCertsConverter.fromProto(request.getKeysAndCerts());

        consensusNodeManager = new ConsensusNodeManager(
                selfId, platformConfig, genesisRoster, version, keysAndCerts, backgroundExecutor);

        setupStreamingEventDispatcher(responseObserver);

        consensusNodeManager.start();
    }

    /**
     * Sets up all the streaming event dispatchers for the platform.
     *
     * @param responseObserver the observer to register for streaming events
     */
    private void setupStreamingEventDispatcher(@NonNull final StreamObserver<EventMessage> responseObserver) {
        dispatcher = new OutboundDispatcher(dispatchExecutor, responseObserver);

        // Capture the dispatcher in a final variable so the lambda remains valid
        final OutboundDispatcher currentDispatcher = dispatcher;

        consensusNodeManager.registerPlatformStatusChangeListener(
                notification -> dispatcher.enqueue(EventMessageFactory.fromPlatformStatusChange(notification)));

        consensusNodeManager.registerConsensusRoundListener(
                rounds -> dispatcher.enqueue(EventMessageFactory.fromConsensusRounds(rounds)));

        consensusNodeManager.registerMarkerFileListener(
                markerFiles -> dispatcher.enqueue(EventMessageFactory.fromMarkerFiles(markerFiles)));

        InMemorySubscriptionManager.INSTANCE.subscribe(logEntry -> {
            dispatcher.enqueue(EventMessageFactory.fromStructuredLog(logEntry));
            return currentDispatcher.isCancelled() ? SubscriberAction.UNSUBSCRIBE : SubscriberAction.CONTINUE;
        });
    }

    /**
     * Checks if the provided {@link StartRequest} is invalid and sends an error response if necessary.
     * <p>
     * This method validates the fields of the {@link StartRequest}. If any of the conditions are not met, an
     * appropriate error is sent to the {@link StreamObserver}.
     *
     * @param request The {@link StartRequest} containing the details for starting the platform.
     * @param responseObserver The observer used to send error messages back to the test framework.
     * @return {@code true} if the request is invalid; {@code false} otherwise.
     */
    private static boolean isInvalidRequest(
            final StartRequest request, final StreamObserver<EventMessage> responseObserver) {
        if (!request.hasVersion()) {
            log.info(ERROR.getMarker(), "Invalid request - version must be specified: {}", request);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("version has to be specified")
                    .asRuntimeException());
            return true;
        }
        if (!request.hasRoster()) {
            log.info(ERROR.getMarker(), "Invalid request - roster must be specified: {}", request);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("roster has to be specified")
                    .asRuntimeException());
            return true;
        }
        return false;
    }

    /**
     * Submits a transaction to the platform.
     * <p>
     * This method sends the transaction payload to the platform for processing.
     *
     * @param request The transaction request containing the payload.
     * @param responseObserver The observer used to confirm transaction submission.
     * @throws StatusRuntimeException if the platform is not started or if an internal error occurs.
     */
    @Override
    public synchronized void submitTransaction(
            @NonNull final TransactionRequest request,
            @NonNull final StreamObserver<TransactionRequestAnswer> responseObserver) {
        log.debug(DEMO_INFO.getMarker(), "Received submit transaction request: {}", request);
        if (consensusNodeManager == null) {
            setPlatformNotStartedResponse(responseObserver);
            return;
        }

        wrapWithErrorHandling(responseObserver, () -> {
            final boolean result =
                    consensusNodeManager.submitTransaction(request.getPayload().toByteArray());
            responseObserver.onNext(
                    TransactionRequestAnswer.newBuilder().setResult(result).build());
            responseObserver.onCompleted();
        });
    }

    /**
     * Updates the synthetic bottleneck settings for the platform.
     * <p>
     * This method allows the test framework to control the synthetic bottleneck behavior of the platform.
     *
     * @param request The request containing the sleep duration per round.
     * @param responseObserver The observer used to confirm the update.
     */
    @Override
    public synchronized void syntheticBottleneckUpdate(
            @NonNull final SyntheticBottleneckRequest request, @NonNull final StreamObserver<Empty> responseObserver) {
        log.info(
                DEMO_INFO.getMarker(),
                "Received synthetic bottleneck request: {} ms",
                request.getSleepMillisPerRound());
        if (consensusNodeManager == null) {
            setPlatformNotStartedResponse(responseObserver);
            return;
        }
        wrapWithErrorHandling(responseObserver, () -> {
            consensusNodeManager.updateSyntheticBottleneck(request.getSleepMillisPerRound());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        });
    }

    @Override
    public void quiescenceCommandUpdate(
            @NonNull final QuiescenceRequest request, @NonNull final StreamObserver<Empty> responseObserver) {
        log.info(DEMO_INFO.getMarker(), "Received quiescence request: {}", request.getCommand());
        if (consensusNodeManager == null) {
            setPlatformNotStartedResponse(responseObserver);
            return;
        }

        wrapWithErrorHandling(responseObserver, () -> {
            final QuiescenceCommand command =
                    switch (request.getCommand()) {
                        case QUIESCE -> QuiescenceCommand.QUIESCE;
                        case BREAK_QUIESCENCE -> QuiescenceCommand.BREAK_QUIESCENCE;
                        default -> QuiescenceCommand.DONT_QUIESCE;
                    };

            consensusNodeManager.sendQuiescenceCommand(command);
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        });
    }

    private void setPlatformNotStartedResponse(@NonNull final StreamObserver<?> responseObserver) {
        responseObserver.onError(Status.FAILED_PRECONDITION
                .withDescription("Platform not started yet")
                .asRuntimeException());
    }

    private static void wrapWithErrorHandling(
            @NonNull final StreamObserver<?> responseObserver, @NonNull final Runnable action) {
        try {
            action.run();
        } catch (final IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withCause(e).asRuntimeException());
        } catch (final UnsupportedOperationException e) {
            responseObserver.onError(Status.UNIMPLEMENTED.withCause(e).asRuntimeException());
        } catch (final Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }
}

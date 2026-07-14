// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.platform;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.ERROR;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.helpers.Utils.createConfiguration;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.otter.docker.app.EventMessageFactory;
import org.hiero.consensus.otter.docker.app.EventStreamManager;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc.NodeCommunicationServiceImplBase;
import org.hiero.otter.fixtures.container.proto.QuiescenceRequest;
import org.hiero.otter.fixtures.container.proto.StartRequest;
import org.hiero.otter.fixtures.container.proto.SubscribeRequest;
import org.hiero.otter.fixtures.container.proto.SyntheticBottleneckRequest;
import org.hiero.otter.fixtures.container.proto.TransactionRequest;
import org.hiero.otter.fixtures.container.proto.TransactionRequestAnswer;
import org.hiero.otter.fixtures.internal.KeysAndCertsConverter;
import org.hiero.otter.fixtures.internal.ProtobufConverter;
import org.hiero.otter.fixtures.logging.internal.InMemorySubscriptionManager;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Responsible for all gRPC communication between the test framework and the consensus node. This class acts as an
 * intermediary between the test framework and the consensus node.
 */
public class NodeCommunicationService extends NodeCommunicationServiceImplBase {

    /** Logger */
    private static final Logger log = LogManager.getLogger(NodeCommunicationService.class);

    /**
     * The ID of the consensus node in this container. The ID must not be changed even between restarts.
     */
    private final NodeId selfId;

    /**
     * Buffers and delivers all event messages (log entries, status changes, consensus rounds). Owned by this service so
     * that a client whose event stream died can re-subscribe and resume without losing events.
     */
    private final EventStreamManager eventStreamManager = new EventStreamManager();

    /** Manages the consensus node, including setup, tear down, and all interactions in between. */
    private ConsensusNodeManager consensusNodeManager;

    /**
     * Constructs a {@link NodeCommunicationService} with the specified self ID.
     *
     * @param selfId the ID of this node, which must not change between restarts
     */
    public NodeCommunicationService(@NonNull final NodeId selfId) {
        this.selfId = requireNonNull(selfId);

        // Subscribe the log listener here, not in start(), so that log messages are captured across
        // reconnects (and even before the first subscription) and never self-unsubscribe.
        InMemorySubscriptionManager.INSTANCE.subscribe(logEntry -> {
            eventStreamManager.publish(EventMessageFactory.fromStructuredLog(logEntry));
            return SubscriberAction.CONTINUE;
        });
    }

    /**
     * Starts the platform using the provided {@link StartRequest}.
     * <p>
     * The request is validated and acknowledged synchronously, but the {@link ConsensusNodeManager} is constructed and
     * started afterwards, so this call returns as soon as the request has been accepted rather than once the platform is
     * fully up. Listeners that publish platform events into the {@link EventStreamManager} are registered as part of that
     * construction; the event messages themselves are delivered over the separate {@code subscribe} stream rather than
     * through this call's response.
     *
     * @param request The request containing details required to construct the platform.
     * @param responseObserver The observer used to acknowledge that the start request has been accepted.
     * @throws StatusRuntimeException if the platform is already started, or if the request contains invalid arguments.
     */
    @Override
    public synchronized void start(
            @NonNull final StartRequest request, @NonNull final StreamObserver<Empty> responseObserver) {
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

        // Acknowledge the request *before* constructing and starting the platform. Platform construction is
        // expensive, and the test harness starts nodes sequentially with a blocking call; waiting for
        // construction to finish here would serialize startup across the whole network and make the
        // last-started nodes fall so far behind that they have to reconnect. Replying first lets every node
        // construct its platform in parallel. Construction failures can no longer be returned to the caller,
        // so they are logged instead.
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();

        try {
            consensusNodeManager =
                    new ConsensusNodeManager(selfId, platformConfig, genesisRoster, version, keysAndCerts);
            setupStreamingEventDispatcher();
            consensusNodeManager.start();
        } catch (final Exception e) {
            log.error(ERROR.getMarker(), "Failed to construct and start the platform", e);
        }
    }

    /**
     * Subscribes the caller to the stream of event messages produced by the platform. Delivery begins with a sync point,
     * followed by a replay of every buffered message after the requested sequence, and then live messages.
     *
     * @param request the subscription request carrying the last sequence number the caller has already seen
     * @param responseObserver the observer to which event messages are delivered
     */
    @Override
    public void subscribe(
            @NonNull final SubscribeRequest request, @NonNull final StreamObserver<EventMessage> responseObserver) {
        log.info(STARTUP.getMarker(), "Received subscribe request after sequence {}", request.getAfterSequence());
        eventStreamManager.subscribe(request.getAfterSequence(), responseObserver);
    }

    /**
     * Sets up all the streaming event dispatchers for the platform.
     */
    private void setupStreamingEventDispatcher() {
        consensusNodeManager.registerPlatformStatusChangeListener(
                notification -> eventStreamManager.publish(EventMessageFactory.fromPlatformStatusChange(notification)));

        consensusNodeManager.registerConsensusRoundListener(
                round -> eventStreamManager.publish(EventMessageFactory.fromConsensusRound(round)));
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
    private static boolean isInvalidRequest(final StartRequest request, final StreamObserver<Empty> responseObserver) {
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
            int numFailed = 0;
            for (final ByteString payload : request.getPayloadList()) {
                if (!consensusNodeManager.submitTransaction(payload.toByteArray())) {
                    numFailed++;
                }
            }
            responseObserver.onNext(TransactionRequestAnswer.newBuilder()
                    .setNumFailed(numFailed)
                    .build());
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
            log.error(DEMO_INFO.getMarker(), "Error processing gRPC request", e);
            responseObserver.onError(Status.INVALID_ARGUMENT.withCause(e).asRuntimeException());
        } catch (final UnsupportedOperationException e) {
            log.error(DEMO_INFO.getMarker(), "Error processing gRPC request", e);
            responseObserver.onError(Status.UNIMPLEMENTED.withCause(e).asRuntimeException());
        } catch (final Exception e) {
            log.error(DEMO_INFO.getMarker(), "Error processing gRPC request", e);
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }
}

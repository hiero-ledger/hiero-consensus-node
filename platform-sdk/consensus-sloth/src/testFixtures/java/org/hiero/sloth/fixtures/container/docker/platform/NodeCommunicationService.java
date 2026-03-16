// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.container.docker.platform;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.ERROR;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;
import static org.hiero.sloth.fixtures.internal.helpers.Utils.createConfiguration;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.sloth.fixtures.container.docker.EventMessageFactory;
import org.hiero.sloth.fixtures.container.docker.OutboundDispatcher;
import org.hiero.sloth.fixtures.container.proto.EventMessage;
import org.hiero.sloth.fixtures.container.proto.NodeCommunicationServiceGrpc.NodeCommunicationServiceImplBase;
import org.hiero.sloth.fixtures.container.proto.QuiescenceRequest;
import org.hiero.sloth.fixtures.container.proto.StartRequest;
import org.hiero.sloth.fixtures.container.proto.SyntheticBottleneckRequest;
import org.hiero.sloth.fixtures.container.proto.TransactionRequest;
import org.hiero.sloth.fixtures.container.proto.TransactionRequestAnswer;
import org.hiero.sloth.fixtures.internal.KeysAndCertsConverter;
import org.hiero.sloth.fixtures.internal.ProtobufConverter;
import org.hiero.sloth.fixtures.logging.internal.InMemorySubscriptionManager;
import org.hiero.sloth.fixtures.result.SubscriberAction;

/**
 * Responsible for all gRPC communication between the test framework and the consensus node.
 */
public class NodeCommunicationService extends NodeCommunicationServiceImplBase {

    /** Default thread name for the consensus node manager gRPC service */
    private static final String NODE_COMMUNICATION_THREAD_NAME = "grpc-outbound-dispatcher";

    /** Logger */
    private static final Logger log = LogManager.getLogger(NodeCommunicationService.class);

    /**
     * The ID of the consensus node in this container.
     */
    private final NodeId selfId;

    /** Executor service for handling the dispatched messages */
    private final ExecutorService dispatchExecutor;

    /** Executor for background tasks */
    private final Executor backgroundExecutor;

    /** Handles outgoing messages, may get called from different threads/callbacks */
    private volatile OutboundDispatcher dispatcher;

    /** Manages the consensus node */
    private ConsensusNodeManager consensusNodeManager;

    /**
     * Constructs a {@link NodeCommunicationService} with the specified self ID.
     *
     * @param selfId the ID of this node
     */
    public NodeCommunicationService(@NonNull final NodeId selfId) {
        this.selfId = requireNonNull(selfId);
        this.dispatchExecutor = createDispatchExecutor();
        this.backgroundExecutor = Executors.newCachedThreadPool();
    }

    private static ExecutorService createDispatchExecutor() {
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, NODE_COMMUNICATION_THREAD_NAME);
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    /**
     * Starts the communication channel with the platform.
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

        dispatcher = new OutboundDispatcher(dispatchExecutor, responseObserver);

        InMemorySubscriptionManager.INSTANCE.subscribe(logEntry -> {
            dispatcher.enqueue(EventMessageFactory.fromStructuredLog(logEntry));
            return dispatcher.isCancelled() ? SubscriberAction.UNSUBSCRIBE : SubscriberAction.CONTINUE;
        });

        if (consensusNodeManager != null) {
            responseObserver.onError(Status.ALREADY_EXISTS.asRuntimeException());
            log.info(ERROR.getMarker(), "Invalid request, platform already started: {}", request);
            return;
        }

        final Map<String, String> overriddenProperties = request.getOverriddenPropertiesMap();
        validateOverriddenProperties(overriddenProperties);
        final Configuration platformConfig = createConfiguration(overriddenProperties);
        final Roster genesisRoster = ProtobufConverter.toPbj(request.getRoster());
        final SemanticVersion version = ProtobufConverter.toPbj(request.getVersion());
        final KeysAndCerts keysAndCerts = KeysAndCertsConverter.fromProto(request.getKeysAndCerts());

        wrapWithErrorHandling(responseObserver, () -> {
            consensusNodeManager =
                    new ConsensusNodeManager(selfId, platformConfig, genesisRoster, version, keysAndCerts);

            setupStreamingEventDispatcher();

            consensusNodeManager.start();
        });
    }

    private void setupStreamingEventDispatcher() {
        consensusNodeManager.registerPlatformStatusChangeListener(
                notification -> dispatcher.enqueue(EventMessageFactory.fromPlatformStatusChange(notification)));
    }

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

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
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

    /**
     * Validates that overridden properties do not contain path traversal sequences.
     *
     * @param properties the properties to validate
     * @throws IllegalArgumentException if any property contains a path traversal sequence
     */
    private static void validateOverriddenProperties(@NonNull final Map<String, String> properties) {
        for (final Map.Entry<String, String> entry : properties.entrySet()) {
            final String key = entry.getKey();
            final String value = entry.getValue();
            if (containsPathTraversal(key) || containsPathTraversal(value)) {
                throw new IllegalArgumentException("Overridden property contains path traversal sequence: " + key);
            }
        }
    }

    private static boolean containsPathTraversal(@NonNull final String input) {
        return input.contains("..") || input.contains("\0");
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

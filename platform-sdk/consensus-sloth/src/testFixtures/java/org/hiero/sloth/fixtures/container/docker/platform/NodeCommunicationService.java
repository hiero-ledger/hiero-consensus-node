// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.container.docker.platform;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.ERROR;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;
import static org.hiero.sloth.fixtures.internal.helpers.Utils.createConfiguration;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.GrpcStatus;
import com.hedera.pbj.runtime.grpc.Pipeline;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.sloth.fixtures.SlothTransactionType;
import org.hiero.sloth.fixtures.container.docker.EventMessageFactory;
import org.hiero.sloth.fixtures.container.docker.OutboundDispatcher;
import org.hiero.sloth.fixtures.container.proto.Empty;
import org.hiero.sloth.fixtures.container.proto.EventMessage;
import org.hiero.sloth.fixtures.container.proto.NodeCommunicationServiceInterface;
import org.hiero.sloth.fixtures.container.proto.ProtoKeysAndCerts;
import org.hiero.sloth.fixtures.container.proto.QuiescenceRequest;
import org.hiero.sloth.fixtures.container.proto.StartRequest;
import org.hiero.sloth.fixtures.container.proto.StartTransactionGenerationRequest;
import org.hiero.sloth.fixtures.container.proto.StopTransactionGenerationResponse;
import org.hiero.sloth.fixtures.container.proto.SyntheticBottleneckRequest;
import org.hiero.sloth.fixtures.container.proto.TransactionRequest;
import org.hiero.sloth.fixtures.container.proto.TransactionRequestAnswer;
import org.hiero.sloth.fixtures.container.proto.TransactionType;
import org.hiero.sloth.fixtures.internal.KeysAndCertsConverter;
import org.hiero.sloth.fixtures.logging.internal.InMemorySubscriptionManager;
import org.hiero.sloth.fixtures.result.SubscriberAction;

/**
 * Responsible for all gRPC communication between the test framework and the consensus node.
 */
public class NodeCommunicationService implements NodeCommunicationServiceInterface {

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
     * @param replies The pipeline used to send messages back to the test framework.
     * @throws GrpcException if the platform is already started, or if the request contains invalid arguments.
     */
    @Override
    public synchronized void start(
            @NonNull final StartRequest request, @NonNull final Pipeline<? super EventMessage> replies) {
        log.info(STARTUP.getMarker(), "Received start request: {}", request);

        validateStartRequest(request);

        dispatcher = new OutboundDispatcher(dispatchExecutor, replies);

        InMemorySubscriptionManager.INSTANCE.subscribe(logEntry -> {
            dispatcher.enqueue(EventMessageFactory.fromStructuredLog(logEntry));
            return dispatcher.isCancelled() ? SubscriberAction.UNSUBSCRIBE : SubscriberAction.CONTINUE;
        });

        if (consensusNodeManager != null) {
            log.info(ERROR.getMarker(), "Invalid request, platform already started: {}", request);
            throw new GrpcException(GrpcStatus.ALREADY_EXISTS);
        }

        final Map<String, String> overriddenProperties = request.overriddenProperties();
        validateOverriddenProperties(overriddenProperties);
        final Configuration platformConfig = createConfiguration(overriddenProperties);
        final Roster genesisRoster = request.rosterOrThrow();
        final SemanticVersion version = request.versionOrThrow();
        final KeysAndCerts keysAndCerts =
                KeysAndCertsConverter.fromProto(request.keysAndCertsOrElse(ProtoKeysAndCerts.DEFAULT));

        try {
            runOnPlatformThread(() -> {
                consensusNodeManager =
                        new ConsensusNodeManager(selfId, platformConfig, genesisRoster, version, keysAndCerts);

                setupStreamingEventDispatcher();

                consensusNodeManager.start();
            });
        } catch (final Exception e) {
            throw toGrpcException(e);
        }
    }

    /**
     * Runs the given task on a new daemon platform thread and waits until it has finished. The platform creates its
     * threads while it is constructed and started, and threads inherit their thread group, priority, and daemon flag
     * from the thread that creates them. A request is handled on a virtual thread, so the platform is constructed and
     * started on a platform thread instead.
     *
     * @param task the task to run
     */
    private static void runOnPlatformThread(@NonNull final Runnable task) {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        final Thread thread = new Thread(
                () -> {
                    try {
                        task.run();
                        result.complete(null);
                    } catch (final Throwable t) {
                        result.completeExceptionally(t);
                    }
                },
                "platform-startup");
        thread.setDaemon(true);
        thread.start();
        try {
            result.join();
        } catch (final CompletionException e) {
            if (e.getCause() instanceof final RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    private void setupStreamingEventDispatcher() {
        consensusNodeManager.registerPlatformStatusChangeListener(
                notification -> dispatcher.enqueue(EventMessageFactory.fromPlatformStatusChange(notification)));
    }

    private static void validateStartRequest(@NonNull final StartRequest request) {
        if (!request.hasVersion()) {
            log.info(ERROR.getMarker(), "Invalid request - version must be specified: {}", request);
            throw new GrpcException(GrpcStatus.INVALID_ARGUMENT, "version has to be specified");
        }
        if (!request.hasRoster()) {
            log.info(ERROR.getMarker(), "Invalid request - roster must be specified: {}", request);
            throw new GrpcException(GrpcStatus.INVALID_ARGUMENT, "roster has to be specified");
        }
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public synchronized TransactionRequestAnswer submitTransaction(@NonNull final TransactionRequest request) {
        log.debug(DEMO_INFO.getMarker(), "Received submit transaction request: {}", request);
        requirePlatformStarted();

        return wrapWithErrorHandling(() -> {
            int numFailed = 0;
            for (final Bytes payload : request.payload()) {
                if (!consensusNodeManager.submitTransaction(payload.toByteArray())) {
                    numFailed++;
                }
            }
            return TransactionRequestAnswer.newBuilder().numFailed(numFailed).build();
        });
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public synchronized Empty syntheticBottleneckUpdate(@NonNull final SyntheticBottleneckRequest request) {
        log.info(DEMO_INFO.getMarker(), "Received synthetic bottleneck request: {} ms", request.sleepMillisPerRound());
        requirePlatformStarted();

        return wrapWithErrorHandling(() -> {
            consensusNodeManager.updateSyntheticBottleneck(request.sleepMillisPerRound());
            return Empty.DEFAULT;
        });
    }

    @Override
    @NonNull
    public Empty quiescenceCommandUpdate(@NonNull final QuiescenceRequest request) {
        log.info(DEMO_INFO.getMarker(), "Received quiescence request: {}", request.command());
        requirePlatformStarted();

        return wrapWithErrorHandling(() -> {
            final QuiescenceCommand command = switch (request.command()) {
                case QUIESCE -> QuiescenceCommand.QUIESCE;
                case BREAK_QUIESCENCE -> QuiescenceCommand.BREAK_QUIESCENCE;
                default -> QuiescenceCommand.DONT_QUIESCE;
            };

            consensusNodeManager.sendQuiescenceCommand(command);
            return Empty.DEFAULT;
        });
    }

    @Override
    @NonNull
    public synchronized Empty startTransactionGeneration(@NonNull final StartTransactionGenerationRequest request) {
        log.info(
                DEMO_INFO.getMarker(),
                "Received start transaction generation request: {} TPS, type={}",
                request.tps(),
                request.type());
        requirePlatformStarted();

        return wrapWithErrorHandling(() -> {
            final SlothTransactionType type = request.type() == TransactionType.BENCHMARK_TRANSACTION
                    ? SlothTransactionType.BENCHMARK
                    : SlothTransactionType.EMPTY;
            consensusNodeManager.startTransactionGeneration(request.tps(), type);
            return Empty.DEFAULT;
        });
    }

    @Override
    @NonNull
    public synchronized StopTransactionGenerationResponse stopTransactionGeneration(@NonNull final Empty request) {
        log.info(DEMO_INFO.getMarker(), "Received stop transaction generation request");
        requirePlatformStarted();

        return wrapWithErrorHandling(() -> {
            final long count = consensusNodeManager.stopTransactionGeneration();
            return StopTransactionGenerationResponse.newBuilder()
                    .generatedCount(count)
                    .build();
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

    private void requirePlatformStarted() {
        if (consensusNodeManager == null) {
            throw new GrpcException(GrpcStatus.FAILED_PRECONDITION, "Platform not started yet");
        }
    }

    private static <T> T wrapWithErrorHandling(@NonNull final Supplier<T> action) {
        try {
            return action.get();
        } catch (final Exception e) {
            throw toGrpcException(e);
        }
    }

    /**
     * Logs an error that occurred while a request was processed and converts it into the exception that reports it
     * to the test framework.
     *
     * @param error the error that occurred
     * @return the exception to throw from the request handler
     */
    @NonNull
    private static GrpcException toGrpcException(@NonNull final Exception error) {
        log.error(DEMO_INFO.getMarker(), "Error processing gRPC request", error);
        if (error instanceof IllegalArgumentException) {
            return new GrpcException(GrpcStatus.INVALID_ARGUMENT, error);
        }
        if (error instanceof UnsupportedOperationException) {
            return new GrpcException(GrpcStatus.UNIMPLEMENTED, error);
        }
        return new GrpcException(GrpcStatus.INTERNAL, error);
    }
}

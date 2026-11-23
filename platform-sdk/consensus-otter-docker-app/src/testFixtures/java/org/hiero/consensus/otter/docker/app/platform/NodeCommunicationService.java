// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.platform;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.ERROR;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.helpers.Utils.createConfiguration;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.GrpcStatus;
import com.hedera.pbj.runtime.grpc.Pipeline;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.otter.docker.app.EventMessageFactory;
import org.hiero.consensus.otter.docker.app.EventStreamManager;
import org.hiero.consensus.test.fixtures.eventbus.EventBus;
import org.hiero.otter.fixtures.container.proto.Empty;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.InstrumentationPingRequest;
import org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceInterface;
import org.hiero.otter.fixtures.container.proto.ProtoKeysAndCerts;
import org.hiero.otter.fixtures.container.proto.QuiescenceRequest;
import org.hiero.otter.fixtures.container.proto.StartRequest;
import org.hiero.otter.fixtures.container.proto.SubscribeRequest;
import org.hiero.otter.fixtures.container.proto.SyntheticBottleneckRequest;
import org.hiero.otter.fixtures.container.proto.TransactionRequest;
import org.hiero.otter.fixtures.container.proto.TransactionRequestAnswer;
import org.hiero.otter.fixtures.internal.InstrumentedModules;
import org.hiero.otter.fixtures.internal.KeysAndCertsConverter;
import org.hiero.otter.fixtures.logging.internal.InMemorySubscriptionManager;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Responsible for all gRPC communication between the test framework and the consensus node. This class acts as an
 * intermediary between the test framework and the consensus node.
 */
public class NodeCommunicationService implements NodeCommunicationServiceInterface {

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

    /**
     * Manages the consensus node, including setup, tear down, and all interactions in between. It is set by the thread
     * that constructs the platform, see {@link #start(StartRequest)}.
     */
    private volatile ConsensusNodeManager consensusNodeManager;

    /**
     * Completes when the platform requested by the last accepted start request has been constructed and started, or
     * has failed to. Guarded by the monitor of this service.
     */
    private CompletableFuture<Void> platformStartup = CompletableFuture.completedFuture(null);

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
     * @return an empty response acknowledging that the start request has been accepted
     * @throws GrpcException if the platform is already started, or if the request contains invalid arguments.
     */
    @Override
    @NonNull
    public synchronized Empty start(@NonNull final StartRequest request) {
        log.info(STARTUP.getMarker(), "Received start request: {}", request);

        validateStartRequest(request);

        // An earlier start request may still be constructing the platform.
        platformStartup.join();
        if (consensusNodeManager != null) {
            log.info(ERROR.getMarker(), "Invalid request, platform already started: {}", request);
            throw new GrpcException(GrpcStatus.ALREADY_EXISTS);
        }

        final Configuration platformConfig =
                wrapWithErrorHandling(() -> createConfiguration(request.overriddenProperties()));
        final Roster genesisRoster = request.rosterOrThrow();
        final SemanticVersion version = request.versionOrThrow();
        final KeysAndCerts keysAndCerts = wrapWithErrorHandling(
                () -> KeysAndCertsConverter.fromProto(request.keysAndCertsOrElse(ProtoKeysAndCerts.DEFAULT)));

        // The request is acknowledged by returning from this method, so the platform is constructed and started
        // on a separate thread. Platform construction is expensive, and the test harness starts nodes
        // sequentially with a blocking call; waiting for construction to finish here would serialize startup
        // across the whole network and make the last-started nodes fall so far behind that they have to
        // reconnect. Replying first lets every node construct its platform in parallel. Construction failures
        // can no longer be returned to the caller, so they are logged instead.
        platformStartup = CompletableFuture.runAsync(
                () -> constructAndStartPlatform(platformConfig, genesisRoster, version, keysAndCerts),
                NodeCommunicationService::runOnPlatformStartupThread);
        return Empty.DEFAULT;
    }

    /**
     * Constructs and starts the platform. Failures are logged, because the start request that triggered the
     * construction has already been acknowledged.
     *
     * @param platformConfig the configuration of the platform
     * @param genesisRoster the roster the platform starts with
     * @param version the software version of the platform
     * @param keysAndCerts the keys and certificates of this node
     */
    private void constructAndStartPlatform(
            @NonNull final Configuration platformConfig,
            @NonNull final Roster genesisRoster,
            @NonNull final SemanticVersion version,
            @NonNull final KeysAndCerts keysAndCerts) {
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
     * Runs the given task on a new daemon thread that is dedicated to constructing and starting the platform.
     *
     * @param task the task that constructs and starts the platform
     */
    private static void runOnPlatformStartupThread(@NonNull final Runnable task) {
        final Thread thread = new Thread(task, "platform-startup");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Subscribes the caller to the stream of event messages produced by the platform. Delivery begins with a sync point,
     * followed by a replay of every buffered message after the requested sequence, and then live messages.
     *
     * @param request the subscription request carrying the last sequence number the caller has already seen
     * @param replies the pipeline to which event messages are delivered
     */
    @Override
    public void subscribe(
            @NonNull final SubscribeRequest request, @NonNull final Pipeline<? super EventMessage> replies) {
        log.info(STARTUP.getMarker(), "Received subscribe request after sequence {}", request.afterSequence());
        eventStreamManager.subscribe(request.afterSequence(), replies);
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
     * Validates the fields of the provided {@link StartRequest}.
     *
     * @param request The {@link StartRequest} containing the details for starting the platform.
     * @throws GrpcException if the request does not specify a version or a roster
     */
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

    /**
     * Submits a transaction to the platform.
     * <p>
     * This method sends the transaction payload to the platform for processing.
     *
     * @param request The transaction request containing the payload.
     * @return the answer stating how many transactions could not be submitted
     * @throws GrpcException if the platform is not started or if an internal error occurs.
     */
    @Override
    @NonNull
    public synchronized TransactionRequestAnswer submitTransaction(@NonNull final TransactionRequest request) {
        log.debug(DEMO_INFO.getMarker(), "Received submit transaction request: {}", request);
        platformStartup.join();
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

    /**
     * Updates the synthetic bottleneck settings for the platform.
     * <p>
     * This method allows the test framework to control the synthetic bottleneck behavior of the platform.
     *
     * @param request The request containing the sleep duration per round.
     * @return an empty response confirming the update
     * @throws GrpcException if the platform is not started or if an internal error occurs.
     */
    @Override
    @NonNull
    public synchronized Empty syntheticBottleneckUpdate(@NonNull final SyntheticBottleneckRequest request) {
        log.info(DEMO_INFO.getMarker(), "Received synthetic bottleneck request: {} ms", request.sleepMillisPerRound());
        platformStartup.join();
        requirePlatformStarted();

        return wrapWithErrorHandling(() -> {
            consensusNodeManager.updateSyntheticBottleneck(request.sleepMillisPerRound());
            return Empty.DEFAULT;
        });
    }

    /**
     * Sends a quiescence command to the platform.
     *
     * @param request The request containing the quiescence command.
     * @return an empty response confirming that the command has been sent
     * @throws GrpcException if the platform is not started or if an internal error occurs.
     */
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

    /**
     * Handles a ping request from the test framework by publishing the message on the {@link EventBus} of this node.
     * All instrumented modules of the node receive it.
     *
     * @param request the ping request
     * @return an empty response confirming the ping
     * @throws GrpcException if the platform is not started or if an internal error occurs.
     */
    @Override
    @NonNull
    public Empty instrumentationPing(@NonNull final InstrumentationPingRequest request) {
        log.info(DEMO_INFO.getMarker(), "Received instrumentation ping request: {}", request.message());
        requirePlatformStarted();

        return wrapWithErrorHandling(() -> {
            EventBus.getInstance(selfId).publish(InstrumentedModules.PING_CHANNEL, request.message());
            return Empty.DEFAULT;
        });
    }

    private void requirePlatformStarted() {
        if (consensusNodeManager == null) {
            throw new GrpcException(GrpcStatus.FAILED_PRECONDITION, "Platform not started yet");
        }
    }

    private static <T> T wrapWithErrorHandling(@NonNull final Supplier<T> action) {
        try {
            return action.get();
        } catch (final IllegalArgumentException e) {
            log.error(DEMO_INFO.getMarker(), "Error processing gRPC request", e);
            throw new GrpcException(GrpcStatus.INVALID_ARGUMENT, e);
        } catch (final UnsupportedOperationException e) {
            log.error(DEMO_INFO.getMarker(), "Error processing gRPC request", e);
            throw new GrpcException(GrpcStatus.UNIMPLEMENTED, e);
        } catch (final Exception e) {
            log.error(DEMO_INFO.getMarker(), "Error processing gRPC request", e);
            throw new GrpcException(GrpcStatus.INTERNAL, e);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.platform;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.ERROR;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.helpers.Utils.createConfiguration;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.grpc.Pipeline;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.otter.docker.app.EventMessageFactory;
import org.hiero.consensus.otter.docker.app.OutboundDispatcher;
import org.hiero.otter.fixtures.container.proto.Empty;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceInterface;
import org.hiero.otter.fixtures.container.proto.QuiescenceRequest;
import org.hiero.otter.fixtures.container.proto.StartRequest;
import org.hiero.otter.fixtures.container.proto.SyntheticBottleneckRequest;
import org.hiero.otter.fixtures.container.proto.TransactionRequest;
import org.hiero.otter.fixtures.container.proto.TransactionRequestAnswer;
import org.hiero.otter.fixtures.internal.KeysAndCertsConverter;
import org.hiero.otter.fixtures.logging.internal.InMemorySubscriptionManager;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Responsible for all gRPC communication between the test framework and the consensus node. This class acts as an
 * intermediary between the test framework and the consensus node.
 */
public class NodeCommunicationService implements NodeCommunicationServiceInterface {

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
     * sent back to the test framework via the {@link Pipeline}.
     *
     * @param request The request containing details required to construct the platform.
     * @param replies The pipeline used to send messages back to the test framework.
     */
    @Override
    public synchronized void Start(
            @NonNull final StartRequest request, @NonNull final Pipeline<? super EventMessage> replies) {
        log.info(STARTUP.getMarker(), "Received start request: {}", request);

        if (isInvalidRequest(request, replies)) {
            return;
        }

        dispatcher = new OutboundDispatcher(dispatchExecutor, replies);

        InMemorySubscriptionManager.INSTANCE.subscribe(logEntry -> {
            dispatcher.enqueue(EventMessageFactory.fromStructuredLog(logEntry));
            return dispatcher.isCancelled() ? SubscriberAction.UNSUBSCRIBE : SubscriberAction.CONTINUE;
        });

        if (consensusNodeManager != null) {
            replies.onError(new IllegalStateException("Platform already started"));
            log.info(ERROR.getMarker(), "Invalid request, platform already started: {}", request);
            return;
        }

        final Configuration platformConfig = createConfiguration(request.overriddenProperties());
        final Roster genesisRoster = request.roster();
        final SemanticVersion version = request.version();
        final KeysAndCerts keysAndCerts = KeysAndCertsConverter.fromProto(request.keysAndCerts());

        consensusNodeManager = new ConsensusNodeManager(
                selfId, platformConfig, genesisRoster, version, keysAndCerts, backgroundExecutor);

        // Sets up all the streaming event dispatchers for the platform.
        consensusNodeManager.registerPlatformStatusChangeListener(
                notification -> dispatcher.enqueue(EventMessageFactory.fromPlatformStatusChange(notification)));

        consensusNodeManager.registerConsensusRoundListener(
                rounds -> dispatcher.enqueue(EventMessageFactory.fromConsensusRounds(rounds)));

        consensusNodeManager.start();
    }

    /**
     * Checks if the provided {@link StartRequest} is invalid and sends an error response if necessary.
     * <p>
     * This method validates the fields of the {@link StartRequest}. If any of the conditions are not met, an
     * appropriate error is sent to the {@link Pipeline}.
     *
     * @param request The {@link StartRequest} containing the details for starting the platform.
     * @param replies The pipeline used to send error messages back to the test framework.
     * @return {@code true} if the request is invalid; {@code false} otherwise.
     */
    private static boolean isInvalidRequest(final StartRequest request, final Pipeline<? super EventMessage> replies) {
        if (!request.hasVersion()) {
            log.info(ERROR.getMarker(), "Invalid request - version must be specified: {}", request);
            replies.onError(new IllegalArgumentException("version has to be specified"));
            return true;
        }
        if (!request.hasRoster()) {
            log.info(ERROR.getMarker(), "Invalid request - roster must be specified: {}", request);
            replies.onError(new IllegalArgumentException("roster has to be specified"));
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
     * @return The transaction submission result.
     * @throws IllegalStateException if the platform is not started.
     * @throws RuntimeException if an internal error occurs.
     */
    @Override
    @NonNull
    public synchronized TransactionRequestAnswer SubmitTransaction(@NonNull final TransactionRequest request) {
        log.debug(DEMO_INFO.getMarker(), "Received submit transaction request: {}", request);
        if (consensusNodeManager == null) {
            throw new IllegalStateException("Platform not started yet");
        }

        int numFailed = 0;
        for (final com.hedera.pbj.runtime.io.buffer.Bytes payload : request.payload()) {
            if (!consensusNodeManager.submitTransaction(payload.toByteArray())) {
                numFailed++;
            }
        }
        return new TransactionRequestAnswer(numFailed);
    }

    /**
     * Updates the synthetic bottleneck settings for the platform.
     * <p>
     * This method allows the test framework to control the synthetic bottleneck behavior of the platform.
     *
     * @param request The request containing the sleep duration per round.
     * @return Empty response confirming the update.
     * @throws IllegalStateException if the platform is not started.
     */
    @Override
    @NonNull
    public synchronized Empty SyntheticBottleneckUpdate(@NonNull final SyntheticBottleneckRequest request) {
        log.info(DEMO_INFO.getMarker(), "Received synthetic bottleneck request: {} ms", request.sleepMillisPerRound());
        if (consensusNodeManager == null) {
            throw new IllegalStateException("Platform not started yet");
        }
        consensusNodeManager.updateSyntheticBottleneck(request.sleepMillisPerRound());
        return Empty.DEFAULT;
    }

    /**
     * Updates the quiescence command for the platform.
     *
     * @param request The request containing the quiescence command.
     * @return Empty response confirming the update.
     * @throws IllegalStateException if the platform is not started.
     */
    @Override
    @NonNull
    public synchronized Empty QuiescenceCommandUpdate(@NonNull final QuiescenceRequest request) {
        log.info(DEMO_INFO.getMarker(), "Received quiescence request: {}", request.command());
        if (consensusNodeManager == null) {
            throw new IllegalStateException("Platform not started yet");
        }

        final QuiescenceCommand command =
                switch (request.command()) {
                    case QUIESCE -> QuiescenceCommand.QUIESCE;
                    case BREAK_QUIESCENCE -> QuiescenceCommand.BREAK_QUIESCENCE;
                    default -> QuiescenceCommand.DONT_QUIESCE;
                };

        consensusNodeManager.sendQuiescenceCommand(command);
        return Empty.DEFAULT;
    }
}

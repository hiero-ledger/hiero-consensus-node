// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.platform;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.initLogging;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.state.signed.StartupStateUtils.loadInitialState;
import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.helpers.Utils.createConfiguration;

import com.google.protobuf.Empty;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.builder.PlatformBuildingBlocks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.listeners.PlatformStatusChangeListener;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.platform.wiring.PlatformWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.otter.docker.app.EventMessageFactory;
import org.hiero.consensus.otter.docker.app.OutboundDispatcher;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.otter.fixtures.KeysAndCertsConverter;
import org.hiero.otter.fixtures.ProtobufConverter;
import org.hiero.otter.fixtures.TransactionFactory;
import org.hiero.otter.fixtures.app.OtterApp;
import org.hiero.otter.fixtures.app.OtterAppState;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc;
import org.hiero.otter.fixtures.container.proto.StartRequest;
import org.hiero.otter.fixtures.container.proto.SyntheticBottleneckRequest;
import org.hiero.otter.fixtures.container.proto.TransactionRequest;
import org.hiero.otter.fixtures.container.proto.TransactionRequestAnswer;
import org.hiero.otter.fixtures.logging.internal.InMemorySubscriptionManager;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Manages the lifecycle and operations of a consensus node within a container-based network. This class initializes the
 * platform, handles configuration, and provides methods for interacting with the consensus process, including
 * submitting transactions and listening for consensus rounds.
 */
public class ConsensusNodeManager extends NodeCommunicationServiceGrpc.NodeCommunicationServiceImplBase {

    private static final String APP_NAME = "org.hiero.consensus.otter.docker.app.platform.DockerApp";
    private static final String SWIRLD_NAME = "123";

    private static final Logger log = LogManager.getLogger(ConsensusNodeManager.class);

    /** The ID of the node this manager runs */
    private final NodeId selfId;

    /** The instance of the platform this consensus node manager runs. */
    private Platform platform;

    /** Stores the latest platform status. Set directly by the platform via notification. */
    private final AtomicReference<PlatformStatus> status = new AtomicReference<>();

    /**  */
    private final List<ConsensusRoundListener> consensusRoundListeners = new CopyOnWriteArrayList<>();

    private ContainerMarkerFileObserver markerFileObserver;

    /** Handles outgoing messages, may get called from different threads/callbacks */
    private volatile OutboundDispatcher dispatcher;

    /** Executor service for handling the dispatched messages */
    private final ExecutorService dispatchExecutor;

    /** Executor for background tasks, such as monitoring the file system */
    private final Executor backgroundExecutor;

    /**
     * Creates a new instance of {@code ConsensusNodeManager} with the specified parameters. This constructor
     * initializes the platform, sets up all necessary parts for the consensus node.
     *
     * @param selfId the unique identifier for this node, must not be {@code null}
     * @param dispatchExecutor the executor service to handle outgoing messages, must not be {@code null}
     * @param backgroundExecutor the executor to run background tasks, must not be {@code null}
     */
    public ConsensusNodeManager(
            @NonNull final NodeId selfId,
            @NonNull final ExecutorService dispatchExecutor,
            @NonNull final Executor backgroundExecutor) {
        this.selfId = requireNonNull(selfId);
        this.dispatchExecutor = requireNonNull(dispatchExecutor);
        this.backgroundExecutor = requireNonNull(backgroundExecutor);
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
        log.info("Received start request: {}", request);

        if (isInvalidRequest(request, responseObserver)) {
            log.info("Invalid request: {}", request);
            return;
        }

        if (platform != null) {
            responseObserver.onError(Status.ALREADY_EXISTS.asRuntimeException());
            log.info("Invalid request, platform already started: {}", request);
            return;
        }

        final Configuration platformConfig = createConfiguration(request.getOverriddenPropertiesMap());
        final Roster genesisRoster = ProtobufConverter.toPbj(request.getRoster());
        final SemanticVersion version = ProtobufConverter.toPbj(request.getVersion());
        final KeysAndCerts keysAndCerts = KeysAndCertsConverter.fromProto(request.getKeysAndCerts());

        buildPlatform(platformConfig, genesisRoster, version, keysAndCerts);

        setupStreamingEventDispatcher(platformConfig, responseObserver);
        platform.start();
    }

    /**
     * Builds and initializes the platform, but does not start it.
     *
     * @param platformConfig the configuration for the platform
     * @param genesisRoster the initial roster of nodes in the network
     * @param version the semantic version of the platform
     * @param keysAndCerts the keys and certificates for the node
     */
    private void buildPlatform(
            @NonNull final Configuration platformConfig,
            @NonNull final Roster genesisRoster,
            @NonNull final SemanticVersion version,
            @NonNull final KeysAndCerts keysAndCerts) {
        initLogging();
        BootstrapUtils.setupConstructableRegistry();
        TestingAppStateInitializer.registerMerkleStateRootClassIds();

        final var legacySelfId = org.hiero.consensus.model.node.NodeId.of(selfId.id());

        // Immediately initialize the cryptography and merkle cryptography factories
        // to avoid using default behavior instead of that defined in platformConfig
        final MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(platformConfig);

        setupGlobalMetrics(platformConfig);
        final Metrics metrics = getMetricsProvider().createPlatformMetrics(legacySelfId);
        final PlatformStateFacade platformStateFacade = new PlatformStateFacade();

        log.info("Starting node {} with version {}", selfId, version);

        final Time time = Time.getCurrent();
        final FileSystemManager fileSystemManager = FileSystemManager.create(platformConfig);
        final RecycleBin recycleBin = RecycleBin.create(
                metrics, platformConfig, getStaticThreadManager(), time, fileSystemManager, legacySelfId);

        final PlatformContext platformContext = PlatformContext.create(
                platformConfig, Time.getCurrent(), metrics, fileSystemManager, recycleBin, merkleCryptography);

        final HashedReservedSignedState reservedState = loadInitialState(
                recycleBin,
                version,
                () -> OtterAppState.createGenesisState(platformConfig, genesisRoster, metrics, version),
                APP_NAME,
                SWIRLD_NAME,
                legacySelfId,
                platformStateFacade,
                platformContext,
                OtterAppState::new);
        final ReservedSignedState initialState = reservedState.state();

        final MerkleNodeState state = initialState.get().getState();
        final RosterHistory rosterHistory = RosterUtils.createRosterHistory(state);

        final PlatformBuilder builder = PlatformBuilder.create(
                        APP_NAME,
                        SWIRLD_NAME,
                        version,
                        initialState,
                        OtterApp.INSTANCE,
                        legacySelfId,
                        selfId.toString(),
                        rosterHistory,
                        platformStateFacade,
                        (vm) -> state)
                .withPlatformContext(platformContext)
                .withConfiguration(platformConfig)
                .withKeysAndCerts(keysAndCerts)
                .withSystemTransactionEncoderCallback(txn -> Bytes.wrap(
                        TransactionFactory.createStateSignatureTransaction(txn).toByteArray()));

        // Build the platform component builder
        final PlatformComponentBuilder componentBuilder = builder.buildComponentBuilder();
        final PlatformBuildingBlocks blocks = componentBuilder.getBuildingBlocks();

        // Wiring: Forward consensus rounds to registered listeners
        final PlatformWiring wiring = blocks.platformWiring();
        wiring.getConsensusEngineOutputWire()
                .solderTo("dockerApp", "consensusRounds", this::notifyConsensusRoundListeners);

        platform = componentBuilder.build();

        platform.getNotificationEngine()
                .register(PlatformStatusChangeListener.class, newStatus -> status.set(newStatus.getNewStatus()));
    }

    /**
     * Sets up all the streaming event dispatchers for the platform.
     *
     * @param platformConfig the configuration of the platform
     * @param responseObserver the observer to register for streaming events
     */
    private void setupStreamingEventDispatcher(
            @NonNull final Configuration platformConfig, @NonNull final StreamObserver<EventMessage> responseObserver) {
        dispatcher = new OutboundDispatcher(dispatchExecutor, responseObserver);

        // Capture the dispatcher in a final variable so the lambda remains valid
        final OutboundDispatcher currentDispatcher = dispatcher;

        // Register the dispatcher to send platform status changes
        platform.getNotificationEngine()
                .register(
                        PlatformStatusChangeListener.class,
                        notification -> dispatcher.enqueue(EventMessageFactory.fromPlatformStatusChange(notification)));

        // Register the dispatcher to send consensus rounds
        consensusRoundListeners.add(rounds -> dispatcher.enqueue(EventMessageFactory.fromConsensusRounds(rounds)));

        // Register the dispatcher to send marker file names
        final PathsConfig pathsConfig = platformConfig.getConfigData(PathsConfig.class);
        final Path markerFilesDir = pathsConfig.getMarkerFilesDir();
        if (markerFilesDir != null) {
            markerFileObserver = new ContainerMarkerFileObserver(backgroundExecutor, markerFilesDir);
            markerFileObserver.addListener(
                    markerFiles -> dispatcher.enqueue(EventMessageFactory.fromMarkerFiles(markerFiles)));
        }

        // Register the dispatcher to send log entries
        InMemorySubscriptionManager.INSTANCE.subscribe(logEntry -> {
            dispatcher.enqueue(EventMessageFactory.fromStructuredLog(logEntry));
            return currentDispatcher.isCancelled() ? SubscriberAction.UNSUBSCRIBE : SubscriberAction.CONTINUE;
        });
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
        log.debug("Received submit transaction request: {}", request);
        if (platform == null) {
            setPlatformNotStartedResponse(responseObserver);
            return;
        }

        try {
            final boolean result = submitTransaction(request.getPayload().toByteArray());
            responseObserver.onNext(
                    TransactionRequestAnswer.newBuilder().setResult(result).build());
            responseObserver.onCompleted();
        } catch (final Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
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
        log.info("Received synthetic bottleneck request: {}", request);
        if (platform == null) {
            setPlatformNotStartedResponse(responseObserver);
            return;
        }
        updateSyntheticBottleneck(request.getSleepMillisPerRound());
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    /**
     * Shuts down the consensus node.
     *
     * @throws InterruptedException if the thread is interrupted while waiting for the platform to shut down
     */
    public synchronized void destroy() throws InterruptedException {
        if (markerFileObserver != null) {
            markerFileObserver.destroy();
        }
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
        platform.destroy();
    }

    private void setPlatformNotStartedResponse(@NonNull final StreamObserver<?> responseObserver) {
        responseObserver.onError(Status.FAILED_PRECONDITION
                .withDescription("Platform not started yet")
                .asRuntimeException());
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
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("version has to be specified")
                    .asRuntimeException());
            return true;
        }
        if (!request.hasRoster()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("roster has to be specified")
                    .asRuntimeException());
            return true;
        }
        return false;
    }

    /**
     * Notifies registered listeners about new consensus rounds.
     *
     * @param rounds the list of consensus rounds to notify listeners about, must not be {@code null}
     */
    private void notifyConsensusRoundListeners(@NonNull final List<ConsensusRound> rounds) {
        consensusRoundListeners.forEach(listener -> listener.onConsensusRounds(rounds));
    }

    /**
     * Submits a raw transaction to the underlying platform for processing.
     *
     * @param transaction the serialized transaction bytes, must not be {@code null}
     * @return {@code true} if the transaction was successfully submitted, {@code false} otherwise
     */
    private boolean submitTransaction(@NonNull final byte[] transaction) {
        return platform.createTransaction(transaction);
    }

    /**
     * Updates the synthetic bottleneck duration engages on the handle thread. Setting this value to zero disables the
     * bottleneck.
     *
     * @param millisToSleepPerRound the number of milliseconds to sleep per round, must be non-negative
     */
    private void updateSyntheticBottleneck(final long millisToSleepPerRound) {
        if (millisToSleepPerRound < 0) {
            throw new IllegalArgumentException("millisToSleepPerRound must be non-negative");
        }
        OtterApp.INSTANCE.updateSyntheticBottleneck(millisToSleepPerRound);
    }
}

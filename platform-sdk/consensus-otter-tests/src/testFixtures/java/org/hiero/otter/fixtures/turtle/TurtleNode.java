// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.state.signed.StartupStateUtils.loadInitialState;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.fail;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.DESTROYED;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.RUNNING;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.SHUTDOWN;
import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.model.DeterministicWiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.builder.PlatformBuildingBlocks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.ReadablePlatformStateStore;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.state.MerkleNodeState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.app.OtterApp;
import org.hiero.otter.fixtures.app.OtterAppState;
import org.hiero.otter.fixtures.app.OtterExecutionLayer;
import org.hiero.otter.fixtures.app.OtterTransaction;
import org.hiero.otter.fixtures.internal.AbstractNode;
import org.hiero.otter.fixtures.internal.result.NodeResultsCollector;
import org.hiero.otter.fixtures.internal.result.SingleNodeEventStreamResultImpl;
import org.hiero.otter.fixtures.internal.result.SingleNodeMarkerFileResultImpl;
import org.hiero.otter.fixtures.internal.result.SingleNodePcesResultImpl;
import org.hiero.otter.fixtures.internal.result.SingleNodeReconnectResultImpl;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext.LoggingContextScope;
import org.hiero.otter.fixtures.logging.internal.InMemorySubscriptionManager;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeEventStreamResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodeMarkerFileResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedGossip;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedNetwork;
import org.hiero.otter.fixtures.turtle.logging.TurtleLogging;
import org.hiero.otter.fixtures.util.OtterSavedStateUtils;
import org.hiero.otter.fixtures.util.SecureRandomBuilder;

/**
 * A node in the turtle network.
 *
 * <p>This class implements the {@link Node} interface and provides methods to control the state of the node.
 */
public class TurtleNode extends AbstractNode implements Node, TurtleTimeManager.TimeTickReceiver {
    private static final Logger log = LogManager.getLogger();

    private final Randotron randotron;
    private final TurtleTimeManager timeManager;
    private final SimulatedNetwork network;
    private final TurtleLogging logging;
    private final TurtleNodeConfiguration nodeConfiguration;
    private final NodeResultsCollector resultsCollector;
    private final TurtleMarkerFileObserver markerFileObserver;
    private final Path outputDirectory;

    private PlatformContext platformContext;

    private QuiescenceCommand quiescenceCommand = QuiescenceCommand.DONT_QUIESCE;

    @Nullable
    private DeterministicWiringModel model;

    @Nullable
    private Platform platform;

    @Nullable
    private OtterExecutionLayer executionLayer;

    @Nullable
    private PlatformComponents platformComponent;

    @Nullable
    private OtterApp otterApp;

    /**
     * Constructor of {@link TurtleNode}.
     *
     * @param randotron the random number generator
     * @param timeManager the time manager for this test
     * @param selfId the node ID of the node
     * @param keysAndCerts the keys and certificates of the node
     * @param network the simulated network
     * @param logging the logging instance for the node
     * @param outputDirectory the output directory for the node
     */
    public TurtleNode(
            @NonNull final Randotron randotron,
            @NonNull final TurtleTimeManager timeManager,
            @NonNull final NodeId selfId,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final SimulatedNetwork network,
            @NonNull final TurtleLogging logging,
            @NonNull final Path outputDirectory) {
        super(selfId, keysAndCerts);
        try (final LoggingContextScope ignored = installNodeContext()) {
            this.outputDirectory = requireNonNull(outputDirectory);
            logging.addNodeLogging(selfId, outputDirectory);

            this.randotron = requireNonNull(randotron);
            this.timeManager = requireNonNull(timeManager);
            this.network = requireNonNull(network);
            this.logging = requireNonNull(logging);
            this.nodeConfiguration = new TurtleNodeConfiguration(() -> lifeCycle, outputDirectory);
            this.resultsCollector = new NodeResultsCollector(selfId);
            this.markerFileObserver = new TurtleMarkerFileObserver(resultsCollector);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStart(@NonNull final Duration timeout) {
        try (final LoggingContextScope ignored = installNodeContext()) {
            throwIfInLifecycle(RUNNING, "Node has already been started.");
            throwIfInLifecycle(DESTROYED, "Node has already been destroyed.");

            if (savedStateDirectory != null) {
                try {
                    OtterSavedStateUtils.copySaveState(selfId, savedStateDirectory, outputDirectory);
                } catch (final IOException exception) {
                    log.error("Failed to copy save state to output directory", exception);
                }
            }

            // Start node from current state
            final Configuration currentConfiguration = nodeConfiguration.current();

            setupGlobalMetrics(currentConfiguration);

            final PathsConfig pathsConfig = currentConfiguration.getConfigData(PathsConfig.class);
            final Path markerFilesDir = pathsConfig.getMarkerFilesDir();
            if (markerFilesDir != null) {
                markerFileObserver.startObserving(markerFilesDir);
            }

            final PlatformStateFacade platformStateFacade = new PlatformStateFacade();
            final Metrics metrics = getMetricsProvider().createPlatformMetrics(selfId);
            final FileSystemManager fileSystemManager = FileSystemManager.create(currentConfiguration);
            final RecycleBin recycleBin = RecycleBin.create(
                    metrics,
                    currentConfiguration,
                    getStaticThreadManager(),
                    timeManager.time(),
                    fileSystemManager,
                    selfId);

            platformContext = TestPlatformContextBuilder.create()
                    .withTime(timeManager.time())
                    .withConfiguration(currentConfiguration)
                    .withFileSystemManager(fileSystemManager)
                    .withMetrics(metrics)
                    .withRecycleBin(recycleBin)
                    .build();

            model = WiringModelBuilder.create(platformContext.getMetrics(), timeManager.time())
                    .withDeterministicModeEnabled(true)
                    .withUncaughtExceptionHandler((t, e) -> fail("Unexpected exception in wiring framework", e))
                    .build();

            otterApp = new OtterApp(version);

            final HashedReservedSignedState reservedState = loadInitialState(
                    recycleBin,
                    version,
                    () -> OtterAppState.createGenesisState(
                            currentConfiguration, roster(), metrics, version, otterApp.allServices()),
                    OtterApp.APP_NAME,
                    OtterApp.SWIRLD_NAME,
                    selfId,
                    platformStateFacade,
                    platformContext,
                    OtterAppState::new);

            final ReservedSignedState initialState = reservedState.state();
            final MerkleNodeState state = initialState.get().getState();

            // Set active the roster
            final ReadablePlatformStateStore store =
                    new ReadablePlatformStateStore(state.getReadableStates(PlatformStateService.NAME));
            RosterUtils.setActiveRoster(state, roster(), store.getRound() + 1);

            final RosterHistory rosterHistory = RosterUtils.createRosterHistory(state);
            final String eventStreamLoc = Long.toString(selfId.id());

            this.executionLayer = new OtterExecutionLayer(
                    new Random(randotron.nextLong()), platformContext.getMetrics(), timeManager.time());

            final PlatformBuilder platformBuilder = PlatformBuilder.create(
                            OtterApp.APP_NAME,
                            OtterApp.SWIRLD_NAME,
                            version,
                            initialState,
                            otterApp,
                            selfId,
                            eventStreamLoc,
                            rosterHistory,
                            platformStateFacade,
                            OtterAppState::new)
                    .withPlatformContext(platformContext)
                    .withConfiguration(currentConfiguration)
                    .withKeysAndCerts(keysAndCerts)
                    .withExecutionLayer(executionLayer)
                    .withModel(model)
                    .withSecureRandomSupplier(new SecureRandomBuilder(randotron.nextLong()));

            final PlatformComponentBuilder platformComponentBuilder = platformBuilder.buildComponentBuilder();
            final PlatformBuildingBlocks platformBuildingBlocks = platformComponentBuilder.getBuildingBlocks();

            final SimulatedGossip gossip = network.getGossipInstance(selfId);
            gossip.provideIntakeEventCounter(platformBuildingBlocks.intakeEventCounter());

            platformComponentBuilder
                    .withMetricsDocumentationEnabled(false)
                    .withGossip(network.getGossipInstance(selfId));

            platformComponent = platformBuildingBlocks.platformComponents();

            platformComponent
                    .consensusEngineWiring()
                    .consensusRoundsOutputWire()
                    .solderTo(
                            "nodeConsensusRoundsCollector",
                            "consensusRounds",
                            wrapConsumerWithNodeContext(resultsCollector::addConsensusRounds));

            platformComponent
                    .platformMonitorWiring()
                    .getOutputWire()
                    .solderTo(
                            "nodePlatformStatusCollector",
                            "platformStatus",
                            wrapConsumerWithNodeContext(this::handlePlatformStatusChange));

            InMemorySubscriptionManager.INSTANCE.subscribe(logEntry -> {
                if (Objects.equals(logEntry.nodeId(), selfId)) {
                    resultsCollector.addLogEntry(logEntry);
                }
                return lifeCycle == DESTROYED ? UNSUBSCRIBE : CONTINUE;
            });

            platform = platformComponentBuilder.build();
            platformStatus = PlatformStatus.STARTING_UP;
            platform.start();

            quiescenceCommand = QuiescenceCommand.DONT_QUIESCE;
            lifeCycle = RUNNING;
        }
    }

    /**
     * {@inheritDoc}
     * <p>This method must <emphasize>NEVER</emphasize> be called from inside the
     * {@link org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver#tick(Instant)} because this method
     * requires time to pass using that method.
     */
    @Override
    protected void doKillImmediately(@NonNull final Duration timeout) {
        try (final LoggingContextScope ignored = installNodeContext()) {
            markerFileObserver.stopObserving();
            try {
                if (platform != null) {
                    platform.destroy();
                }
            } catch (final InterruptedException e) {
                throw new AssertionError("Unexpected interruption during platform shutdown", e);
            }
            platformStatus = null;
            platform = null;
            platformComponent = null;
            model = null;
            lifeCycle = SHUTDOWN;

            // Wait a bit to allow a simulated gossip cycle to pass.
            // This is important to ensure that the node receives all
            // necessary events when/if it is restarted.
            timeManager.waitFor(Duration.ofSeconds(1));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStartSyntheticBottleneck(@NonNull final Duration delayPerRound, @NonNull final Duration timeout) {
        throw new UnsupportedOperationException("startSyntheticBottleneck is not supported in TurtleNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStopSyntheticBottleneck(@NonNull final Duration timeout) {
        throw new UnsupportedOperationException("stopSyntheticBottleneck is not supported in TurtleNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doSendQuiescenceCommand(@NonNull final QuiescenceCommand command, @NonNull final Duration timeout) {
        assert platform != null; // platform must be initialized if node is RUNNING
        platform.quiescenceCommand(requireNonNull(command));

        this.quiescenceCommand = command;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransaction(@NonNull final OtterTransaction transaction) {
        try (final LoggingContextScope ignored = installNodeContext()) {
            throwIsNotInLifecycle(RUNNING, "Cannot submit transaction when the network is not running.");
            assert platform != null; // platform must be initialized if lifeCycle is STARTED
            assert executionLayer != null; // executionLayer must be initialized

            if (quiescenceCommand == QuiescenceCommand.QUIESCE) {
                // When quiescing, ignore new transactions
                return;
            }

            assert executionLayer != null; // executionLayer must be initialized
            executionLayer.submitApplicationTransaction(transaction.toByteArray());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration configuration() {
        return nodeConfiguration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeConsensusResult newConsensusResult() {
        return resultsCollector.newConsensusResult();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public SingleNodeLogResult newLogResult() {
        return resultsCollector.newLogResult();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePlatformStatusResult newPlatformStatusResult() {
        return resultsCollector.newStatusProgression();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePcesResult newPcesResult() {
        return new SingleNodePcesResultImpl(selfId(), platformContext.getConfiguration());
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method is not supported in TurtleNode and will throw an {@link UnsupportedOperationException}.
     */
    @Override
    @NonNull
    public SingleNodeReconnectResult newReconnectResult() {
        // Turtle networks do not support reconnects. However we can
        // still provide a result object that contains the base results.
        // Doing so allows tests that can run in multiple environments can
        // still make basic verifications, like the absence of reconnects.
        return new SingleNodeReconnectResultImpl(
                selfId, resultsCollector.newStatusProgression(), resultsCollector.newLogResult());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeMarkerFileResult newMarkerFileResult() {
        return new SingleNodeMarkerFileResultImpl(resultsCollector);
    }
    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeEventStreamResult newEventStreamResult() {
        final Configuration currentConfiguration = configuration().current();
        final EventConfig eventConfig = currentConfiguration.getConfigData(EventConfig.class);
        final Path eventStreamDir = Path.of(eventConfig.eventsLogDir());

        return new SingleNodeEventStreamResultImpl(selfId, eventStreamDir, currentConfiguration, newReconnectResult());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected Random random() {
        return randotron;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected TimeManager timeManager() {
        return timeManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAlive() {
        return lifeCycle == RUNNING;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        try (final LoggingContextScope ignored = installNodeContext()) {
            if (lifeCycle == RUNNING) {
                assert model != null; // model must be initialized if lifeCycle is STARTED
                model.tick();
            }
            markerFileObserver.tick(now);
        }
    }

    /**
     * Shuts down the node and cleans up resources. Once this method is called, the node cannot be started again. This
     * method is idempotent and can be called multiple times without any side effects.
     */
    void destroy() {
        killImmediately();

        try (final LoggingContextScope ignored = installNodeContext()) {
            resultsCollector.destroy();
            if (otterApp != null) {
                otterApp.destroy();
            }
            lifeCycle = DESTROYED;

            logging.removeNodeLogging(selfId);
        }
    }

    private void handlePlatformStatusChange(@NonNull final PlatformStatus platformStatus) {
        this.platformStatus = requireNonNull(platformStatus);
        resultsCollector.addPlatformStatus(platformStatus);
    }

    @NonNull
    private NodeLoggingContext.LoggingContextScope installNodeContext() {
        return NodeLoggingContext.install(Long.toString(selfId().id()));
    }

    @NonNull
    private <T> Consumer<T> wrapConsumerWithNodeContext(@NonNull final Consumer<T> consumer) {
        requireNonNull(consumer);
        return value -> {
            try (final LoggingContextScope ignored = installNodeContext()) {
                consumer.accept(value);
            }
        };
    }

    /**
     * Indicated if the node starts from a saved state
     *
     * @return {@code true} if node starts from saved state
     */
    public boolean startFromSavedState() {
        return savedStateDirectory != null;
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.state.signed.StartupStateUtils.loadInitialState;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.fail;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.DESTROYED;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.INIT;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.RUNNING;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.SHUTDOWN;
import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import com.swirlds.base.time.Time;
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
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.app.OtterApp;
import org.hiero.otter.fixtures.app.OtterAppState;
import org.hiero.otter.fixtures.app.OtterExecutionLayer;
import org.hiero.otter.fixtures.app.OtterTransaction;
import org.hiero.otter.fixtures.internal.AbstractNode;
import org.hiero.otter.fixtures.internal.result.NodeResultsCollector;
import org.hiero.otter.fixtures.internal.result.SingleNodeMarkerFileResultImpl;
import org.hiero.otter.fixtures.internal.result.SingleNodePcesResultImpl;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext.LoggingContextScope;
import org.hiero.otter.fixtures.logging.internal.InMemorySubscriptionManager;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodeMarkerFileResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedGossip;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedNetwork;
import org.hiero.otter.fixtures.turtle.logging.TurtleLogging;
import org.hiero.otter.fixtures.util.SecureRandomBuilder;

/**
 * A node in the turtle network.
 *
 * <p>This class implements the {@link Node} interface and provides methods to control the state of the node.
 */
public class TurtleNode extends AbstractNode implements Node, TurtleTimeManager.TimeTickReceiver {

    private final Randotron randotron;
    private final Time time;
    private final SimulatedNetwork network;
    private final TurtleLogging logging;
    private final TurtleNodeConfiguration nodeConfiguration;
    private final NodeResultsCollector resultsCollector;
    private final TurtleMarkerFileObserver markerFileObserver;

    private PlatformContext platformContext;

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
     * @param time the time provider
     * @param selfId the node ID of the node
     * @param keysAndCerts the keys and certificates of the node
     * @param network the simulated network
     * @param logging the logging instance for the node
     * @param outputDirectory the output directory for the node
     */
    public TurtleNode(
            @NonNull final Randotron randotron,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final SimulatedNetwork network,
            @NonNull final TurtleLogging logging,
            @NonNull final Path outputDirectory) {
        super(selfId, keysAndCerts);
        try (final LoggingContextScope ignored = installNodeContext()) {
            logging.addNodeLogging(selfId, outputDirectory);

            this.randotron = requireNonNull(randotron);
            this.time = requireNonNull(time);
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
            throwIfIn(RUNNING, "Node has already been started.");
            throwIfIn(DESTROYED, "Node has already been destroyed.");

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
                    metrics, currentConfiguration, getStaticThreadManager(), time, fileSystemManager, selfId);

            platformContext = TestPlatformContextBuilder.create()
                    .withTime(time)
                    .withConfiguration(currentConfiguration)
                    .withFileSystemManager(fileSystemManager)
                    .withMetrics(metrics)
                    .withRecycleBin(recycleBin)
                    .build();

            model = WiringModelBuilder.create(platformContext.getMetrics(), time)
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
            final State state = initialState.get().getState();

            final RosterHistory rosterHistory = RosterUtils.createRosterHistory(state);
            final String eventStreamLoc = selfId.toString();

            this.executionLayer =
                    new OtterExecutionLayer(new Random(randotron.nextLong()), platformContext.getMetrics());

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

            lifeCycle = RUNNING;
        }
    }

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
        }
    }

    @Override
    protected void doStartSyntheticBottleneck(@NonNull final Duration delayPerRound, @NonNull final Duration timeout) {
        throw new UnsupportedOperationException("startSyntheticBottleneck is not supported in TurtleNode.");
    }

    @Override
    protected void doStopSyntheticBottleneck(@NonNull final Duration timeout) {
        throw new UnsupportedOperationException("stopSyntheticBottleneck is not supported in TurtleNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransaction(@NonNull final OtterTransaction transaction) {
        try (final LoggingContextScope ignored = installNodeContext()) {
            throwIfIn(INIT, "Node has not been started yet.");
            throwIfIn(SHUTDOWN, "Node has been shut down.");
            throwIfIn(DESTROYED, "Node has been destroyed.");
            assert platform != null; // platform must be initialized if lifeCycle is STARTED
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
        throw new UnsupportedOperationException("Reconnect is not supported in TurtleNode.");
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
}

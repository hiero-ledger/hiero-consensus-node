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
import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.APP_NAME;
import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.SWIRLD_NAME;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.model.DeterministicWiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.builder.PlatformBuildingBlocks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.util.RandomBuilder;
import com.swirlds.platform.wiring.PlatformWiring;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.apache.logging.log4j.ThreadContext;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.otter.fixtures.AsyncNodeActions;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.TransactionFactory;
import org.hiero.otter.fixtures.app.OtterApp;
import org.hiero.otter.fixtures.app.OtterAppState;
import org.hiero.otter.fixtures.internal.AbstractNode;
import org.hiero.otter.fixtures.internal.result.NodeResultsCollector;
import org.hiero.otter.fixtures.internal.result.SingleNodeLogResultImpl;
import org.hiero.otter.fixtures.internal.result.SingleNodePcesResultImpl;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedGossip;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedNetwork;
import org.jetbrains.annotations.NotNull;

/**
 * A node in the turtle network.
 *
 * <p>This class implements the {@link Node} interface and provides methods to control the state of the node.
 */
public class TurtleNode extends AbstractNode implements Node, TurtleTimeManager.TimeTickReceiver {

    public static final String THREAD_CONTEXT_NODE_ID = "nodeId";

    private final Randotron randotron;
    private final Time time;
    private final Roster roster;
    private final KeysAndCerts keysAndCerts;
    private final SimulatedNetwork network;
    private final TurtleLogging logging;
    private final TurtleNodeConfiguration nodeConfiguration;
    private final NodeResultsCollector resultsCollector;
    private final AsyncNodeActions asyncNodeActions = new TurtleAsyncNodeActions();

    private PlatformContext platformContext;

    @Nullable
    private DeterministicWiringModel model;

    @Nullable
    private Platform platform;

    @Nullable
    private PlatformWiring platformWiring;

    /**
     * Constructor of {@link TurtleNode}.
     *
     * @param randotron the random number generator
     * @param time the time provider
     * @param selfId the node ID of the node
     * @param roster the initial roster
     * @param keysAndCerts the keys and certificates of the node
     * @param network the simulated network
     * @param logging the logging instance for the node
     * @param outputDirectory the output directory for the node
     */
    public TurtleNode(
            @NonNull final Randotron randotron,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final Roster roster,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final SimulatedNetwork network,
            @NonNull final TurtleLogging logging,
            @NonNull final Path outputDirectory) {
        super(selfId, roster);
        logging.addNodeLogging(selfId, outputDirectory);
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, this.selfId.toString());

            this.randotron = requireNonNull(randotron);
            this.time = requireNonNull(time);
            this.roster = requireNonNull(roster);
            this.keysAndCerts = requireNonNull(keysAndCerts);
            this.network = requireNonNull(network);
            this.logging = requireNonNull(logging);
            this.nodeConfiguration = new TurtleNodeConfiguration(() -> lifeCycle, outputDirectory);
            this.resultsCollector = new NodeResultsCollector(selfId);

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void killImmediately() throws InterruptedException {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            doShutdownNode();

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method is not supported in TurtleNode and will throw an {@link UnsupportedOperationException}.
     */
    @Override
    public void startSyntheticBottleneck(@NotNull final Duration delayPerRound) {
        throw new UnsupportedOperationException("Synthetic bottleneck is not supported in TurtleNode.");
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method is not supported in TurtleNode and will throw an {@link UnsupportedOperationException}.
     */
    @Override
    public void stopSyntheticBottleneck() {
        throw new UnsupportedOperationException("Synthetic bottleneck is not supported in TurtleNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            throwIfIn(RUNNING, "Node has already been started.");
            throwIfIn(DESTROYED, "Node has already been destroyed.");

            // Start node from current state
            doStartNode();

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AsyncNodeActions withTimeout(@NonNull final Duration timeout) {
        return asyncNodeActions;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransaction(@NonNull final byte[] transaction) {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            throwIfIn(INIT, "Node has not been started yet.");
            throwIfIn(SHUTDOWN, "Node has been shut down.");
            throwIfIn(DESTROYED, "Node has been destroyed.");
            assert platform != null; // platform must be initialized if lifeCycle is STARTED

            platform.createTransaction(transaction);

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
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
    public SingleNodeConsensusResult getConsensusResult() {
        return resultsCollector.getConsensusResult();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public SingleNodeLogResult getLogResult() {
        return new SingleNodeLogResultImpl(selfId, Set.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePlatformStatusResults getPlatformStatusResults() {
        return resultsCollector.getStatusProgression();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePcesResult getPcesResult() {
        return new SingleNodePcesResultImpl(selfId(), platformContext.getConfiguration());
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method is not supported in TurtleNode and will throw an {@link UnsupportedOperationException}.
     */
    @Override
    public @NotNull SingleNodeReconnectResult getReconnectResults() {
        throw new UnsupportedOperationException("Reconnect is not supported in TurtleNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        if (lifeCycle == RUNNING) {
            assert model != null; // model must be initialized if lifeCycle is STARTED
            try {
                ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());
                model.tick();
            } finally {
                ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
            }
        }
    }

    /**
     * Shuts down the node and cleans up resources. Once this method is called, the node cannot be started again. This
     * method is idempotent and can be called multiple times without any side effects.
     *
     * @throws InterruptedException if the thread is interrupted while the node is being destroyed
     */
    void destroy() throws InterruptedException {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            resultsCollector.destroy();
            doShutdownNode();
            lifeCycle = DESTROYED;

            logging.removeNodeLogging(selfId);

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    private void doShutdownNode() throws InterruptedException {
        if (lifeCycle == RUNNING) {
            assert platform != null; // platform must be initialized if lifeCycle is STARTED
            platform.destroy();
            platformStatus = null;
            platform = null;
            platformWiring = null;
            model = null;
        }
        lifeCycle = SHUTDOWN;
    }

    private void doStartNode() {

        final Configuration currentConfiguration = nodeConfiguration.current();
        final org.hiero.consensus.model.node.NodeId legacyNodeId =
                org.hiero.consensus.model.node.NodeId.of(selfId.id());

        setupGlobalMetrics(currentConfiguration);

        final PlatformStateFacade platformStateFacade = new PlatformStateFacade();
        MerkleDb.resetDefaultInstancePath();
        final Metrics metrics = getMetricsProvider().createPlatformMetrics(legacyNodeId);
        final FileSystemManager fileSystemManager = FileSystemManager.create(currentConfiguration);
        final RecycleBin recycleBin = RecycleBin.create(
                metrics, currentConfiguration, getStaticThreadManager(), time, fileSystemManager, legacyNodeId);

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

        final HashedReservedSignedState reservedState = loadInitialState(
                recycleBin,
                version,
                () -> OtterAppState.createGenesisState(currentConfiguration, roster, version),
                APP_NAME,
                SWIRLD_NAME,
                legacyNodeId,
                platformStateFacade,
                platformContext);
        final ReservedSignedState initialState = reservedState.state();

        final State state = initialState.get().getState();
        final RosterHistory rosterHistory = RosterUtils.createRosterHistory(state);
        final String eventStreamLoc = selfId.toString();

        final PlatformBuilder platformBuilder = PlatformBuilder.create(
                        APP_NAME,
                        SWIRLD_NAME,
                        version,
                        initialState,
                        OtterApp.INSTANCE,
                        legacyNodeId,
                        eventStreamLoc,
                        rosterHistory,
                        platformStateFacade)
                .withPlatformContext(platformContext)
                .withConfiguration(currentConfiguration)
                .withKeysAndCerts(keysAndCerts)
                .withSystemTransactionEncoderCallback(txn -> Bytes.wrap(
                        TransactionFactory.createStateSignatureTransaction(txn).toByteArray()))
                .withModel(model)
                .withRandomBuilder(new RandomBuilder(randotron.nextLong()));

        final PlatformComponentBuilder platformComponentBuilder = platformBuilder.buildComponentBuilder();
        final PlatformBuildingBlocks platformBuildingBlocks = platformComponentBuilder.getBuildingBlocks();

        final SimulatedGossip gossip = network.getGossipInstance(legacyNodeId);
        gossip.provideIntakeEventCounter(platformBuildingBlocks.intakeEventCounter());

        platformComponentBuilder
                .withMetricsDocumentationEnabled(false)
                .withGossip(network.getGossipInstance(legacyNodeId));

        platformWiring = platformBuildingBlocks.platformWiring();

        platformWiring
                .getConsensusEngineOutputWire()
                .solderTo("nodeConsensusRoundsCollector", "consensusRounds", resultsCollector::addConsensusRounds);

        platformWiring
                .getStatusStateMachineOutputWire()
                .solderTo("nodePlatformStatusCollector", "platformStatus", this::handlePlatformStatusChange);

        platform = platformComponentBuilder.build();
        platformStatus = PlatformStatus.STARTING_UP;
        platform.start();

        lifeCycle = RUNNING;
    }

    private void handlePlatformStatusChange(@NonNull final PlatformStatus platformStatus) {
        this.platformStatus = requireNonNull(platformStatus);
        resultsCollector.addPlatformStatus(platformStatus);
    }

    /**
     * Turtle-specific implementation of {@link AsyncNodeActions}.
     */
    private class TurtleAsyncNodeActions implements AsyncNodeActions {

        /**
         * {@inheritDoc}
         */
        @Override
        public void killImmediately() throws InterruptedException {
            TurtleNode.this.killImmediately();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void startSyntheticBottleneck(@NonNull final Duration delayPerRound) {
            throw new UnsupportedOperationException("startSyntheticBottleneck is not supported in TurtleNode.");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void stopSyntheticBottleneck() {
            throw new UnsupportedOperationException("stopSyntheticBottleneck is not supported in TurtleNode.");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void start() {
            TurtleNode.this.start();
        }
    }
}

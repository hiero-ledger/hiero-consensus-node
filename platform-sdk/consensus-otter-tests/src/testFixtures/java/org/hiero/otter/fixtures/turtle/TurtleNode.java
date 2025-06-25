// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.state.signed.StartupStateUtils.loadInitialState;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.fail;
import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.APP_NAME;
import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.SWIRLD_NAME;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
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
import com.swirlds.platform.listeners.PlatformStatusChangeListener;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.test.fixtures.turtle.gossip.SimulatedGossip;
import com.swirlds.platform.test.fixtures.turtle.gossip.SimulatedNetwork;
import com.swirlds.platform.util.RandomBuilder;
import com.swirlds.platform.wiring.PlatformWiring;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.logging.log4j.ThreadContext;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.otter.fixtures.AsyncNodeActions;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.result.NodeResultsCollector;
import org.hiero.otter.fixtures.internal.result.SingleNodeLogResultImpl;
import org.hiero.otter.fixtures.internal.result.SingleNodePcesResultImpl;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.logging.internal.InMemoryAppender;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;
import org.hiero.otter.fixtures.turtle.app.TurtleApp;
import org.hiero.otter.fixtures.turtle.app.TurtleAppState;

/**
 * A node in the turtle network.
 *
 * <p>This class implements the {@link Node} interface and provides methods to control the state of the node.
 */
public class TurtleNode implements Node, TurtleTimeManager.TimeTickReceiver {

    public static final String THREAD_CONTEXT_NODE_ID = "nodeId";

    private enum LifeCycle {
        INIT,
        STARTED,
        SHUTDOWN,
        DESTROYED
    }

    private final NodeId selfId;

    private final Randotron randotron;
    private final Time time;
    private final Roster roster;
    private final KeysAndCerts keysAndCerts;
    private final SimulatedNetwork network;
    private final TurtleLogging logging;
    private final TurtleNodeConfiguration nodeConfiguration;
    private final NodeResultsCollector resultsCollector;
    private final PlatformStatusChangeListener platformStatusChangeListener;
    private final AsyncNodeActions asyncNodeActions = new TurtleAcyncNodeActions();

    private PlatformContext platformContext;
    private LifeCycle lifeCycle = LifeCycle.INIT;
    private SemanticVersion version = Node.DEFAULT_VERSION;

    @Nullable
    private DeterministicWiringModel model;

    @Nullable
    private Platform platform;

    @Nullable
    private PlatformWiring platformWiring;

    @Nullable
    private PlatformStatus platformStatus;

    /**
     * Constructor of {@link TurtleNode}.
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
        logging.addNodeLogging(selfId, outputDirectory);
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            this.randotron = requireNonNull(randotron);
            this.time = requireNonNull(time);
            this.selfId = requireNonNull(selfId);
            this.roster = requireNonNull(roster);
            this.keysAndCerts = requireNonNull(keysAndCerts);
            this.network = requireNonNull(network);
            this.logging = requireNonNull(logging);
            this.nodeConfiguration = new TurtleNodeConfiguration(outputDirectory);
            this.resultsCollector = new NodeResultsCollector(selfId);
            this.platformStatusChangeListener = data -> {
                final PlatformStatus newStatus = data.getNewStatus();
                TurtleNode.this.platformStatus = newStatus;
                resultsCollector.addPlatformStatus(newStatus);
            };

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public PlatformStatus platformStatus() {
        return platformStatus;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeId getSelfId() {
        return selfId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SemanticVersion getVersion() {
        return version;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setVersion(@NonNull final SemanticVersion version) {
        this.version = requireNonNull(version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bumpConfigVersion() {
        int newBuildNumber;
        try {
            newBuildNumber = Integer.parseInt(version.build()) + 1;
        } catch (final NumberFormatException e) {
            newBuildNumber = 1;
        }
        this.version = this.version.copyBuilder().build("" + newBuildNumber).build();
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
     */
    @Override
    public void shutdownGracefully() throws InterruptedException {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            if (platformWiring != null) {
                platformWiring.flushIntakePipeline();
            }
            doShutdownNode();

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            checkLifeCycle(LifeCycle.STARTED, "Node has already been started.");
            checkLifeCycle(LifeCycle.DESTROYED, "Node has already been destroyed.");

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

            checkLifeCycle(LifeCycle.INIT, "Node has not been started yet.");
            checkLifeCycle(LifeCycle.SHUTDOWN, "Node has been shut down.");
            checkLifeCycle(LifeCycle.DESTROYED, "Node has been destroyed.");
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
    public NodeConfiguration getConfiguration() {
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
        final List<StructuredLog> logs = InMemoryAppender.getLogs(selfId.id());
        return new SingleNodeLogResultImpl(selfId, logs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeStatusProgression getStatusProgression() {
        return resultsCollector.getStatusProgression();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePcesResult getPcesResult() {
        return new SingleNodePcesResultImpl(selfId, platformContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        if (lifeCycle == LifeCycle.STARTED) {
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
            lifeCycle = LifeCycle.DESTROYED;

            logging.removeNodeLogging(selfId);

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    private void checkLifeCycle(@NonNull final LifeCycle expected, @NonNull final String message) {
        if (lifeCycle == expected) {
            throw new IllegalStateException(message);
        }
    }

    private void doShutdownNode() throws InterruptedException {
        if (lifeCycle == LifeCycle.STARTED) {
            assert platform != null; // platform must be initialized if lifeCycle is STARTED
            assert platformWiring != null; // platformWiring must be initialized if lifeCycle is STARTED
            getMetricsProvider().removePlatformMetrics(platform.getSelfId());
            platformWiring.stop();
            platform.getNotificationEngine().unregisterAll();
            platformStatus = null;
            platform = null;
            platformWiring = null;
            model = null;
        }
        lifeCycle = LifeCycle.SHUTDOWN;
    }

    private void doStartNode() {

        final Configuration currentConfiguration = nodeConfiguration.createConfiguration();

        setupGlobalMetrics(currentConfiguration);

        final PlatformStateFacade platformStateFacade = new PlatformStateFacade();
        MerkleDb.resetDefaultInstancePath();
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

        final HashedReservedSignedState reservedState = loadInitialState(
                recycleBin,
                version,
                () -> TurtleAppState.createGenesisState(currentConfiguration, roster, version),
                APP_NAME,
                SWIRLD_NAME,
                selfId,
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
                        TurtleApp.INSTANCE,
                        selfId,
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

        final SimulatedGossip gossip = network.getGossipInstance(selfId);
        gossip.provideIntakeEventCounter(platformBuildingBlocks.intakeEventCounter());

        platformComponentBuilder.withMetricsDocumentationEnabled(false).withGossip(network.getGossipInstance(selfId));

        platformWiring = platformBuildingBlocks.platformWiring();

        platformWiring
                .getConsensusEngineOutputWire()
                .solderTo("nodeResultCollector", "consensusRounds", resultsCollector::addConsensusRounds);

        platform = platformComponentBuilder.build();
        platformStatus = PlatformStatus.STARTING_UP;
        platform.getNotificationEngine().register(PlatformStatusChangeListener.class, platformStatusChangeListener);
        platform.start();

        lifeCycle = LifeCycle.STARTED;
    }

    /**
     * Turtle-specific implementation of {@link AsyncNodeActions}.
     */
    private class TurtleAcyncNodeActions implements AsyncNodeActions {

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
        public void shutdownGracefully() throws InterruptedException {
            TurtleNode.this.shutdownGracefully();
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

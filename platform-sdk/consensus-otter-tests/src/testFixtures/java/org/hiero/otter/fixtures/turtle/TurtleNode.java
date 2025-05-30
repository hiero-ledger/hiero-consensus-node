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
import com.swirlds.common.io.config.FileSystemManagerConfig_;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.FileUtils;
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
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.hiero.consensus.roster.ReadableRosterStoreImpl;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterStateId;
import org.hiero.consensus.roster.RosterUtils;
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
    private static final Logger log = LogManager.getLogger(TurtleNode.class);

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
    private final TurtleNodeConfiguration nodeConfiguration;
    private final NodeResultsCollector resultsCollector;

    private final PlatformStatusChangeListener platformStatusChangeListener;

    private DeterministicWiringModel model;
    private PlatformContext platformContext;
    private Platform platform;
    private PlatformWiring platformWiring;
    private LifeCycle lifeCycle = LifeCycle.INIT;

    private PlatformStatus platformStatus;

    public TurtleNode(
            @NonNull final Randotron randotron,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final Roster roster,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final SimulatedNetwork network,
            @NonNull final Path outputDirectory) {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            this.randotron = requireNonNull(randotron);
            this.time = requireNonNull(time);
            this.selfId = requireNonNull(selfId);
            this.roster = requireNonNull(roster);
            this.keysAndCerts = requireNonNull(keysAndCerts);
            this.network = requireNonNull(network);
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
     * Returns the status of the platform while the node is running or {@code null} if not.
     *
     * @return the status of the platform
     */
    PlatformStatus platformStatus() {
        return platformStatus;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public NodeId getSelfId() {
        return selfId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void failUnexpectedly(@NonNull final Duration timeout) throws InterruptedException {
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
    public void shutdownGracefully(@NonNull final Duration timeout) throws InterruptedException {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            platformWiring.flushIntakePipeline();
            doShutdownNode();

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void revive(@NonNull final Duration timeout) {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            checkLifeCycle(LifeCycle.INIT, "Node has not been started previously.");
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
    public void submitTransaction(@NonNull final byte[] transaction) {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            checkLifeCycle(LifeCycle.INIT, "Node has not been started yet.");
            checkLifeCycle(LifeCycle.SHUTDOWN, "Node has been shut down.");
            checkLifeCycle(LifeCycle.DESTROYED, "Node has been destroyed.");

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
    public void tick(@NonNull Instant now) {
        if (lifeCycle == LifeCycle.STARTED) {
            try {
                ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());
                model.tick();
            } finally {
                ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
            }
        }
    }

    /**
     * Start the node
     */
    public void start() {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            checkLifeCycle(LifeCycle.STARTED, "Node has already been started.");
            checkLifeCycle(LifeCycle.DESTROYED, "Node has already been destroyed.");

            // Clean the output directory and start the node
            final String rootPath =
                    nodeConfiguration.createConfiguration().getValue(FileSystemManagerConfig_.ROOT_PATH);
            log.info("Deleting directory: {}", rootPath);
            if (rootPath != null) {
                try {
                    FileUtils.deleteDirectory(new File(rootPath).toPath());
                } catch (IOException ex) {
                    log.warn("Failed to delete directory: {}", rootPath, ex);
                }
            }
            doStartNode();

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    /**
     * Shuts down the node and cleans up resources. Once this method is called, the node cannot be started again. This
     * method is idempotent and can be called multiple times without any side effects.
     */
    public void destroy() throws InterruptedException {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            doShutdownNode();
            lifeCycle = LifeCycle.DESTROYED;

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
            // TODO: Release all resources
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

        final SemanticVersion version =
                currentConfiguration.getValue(TurtleNodeConfiguration.SOFTWARE_VERSION, SemanticVersion.class);
        assert version != null; // avoids a warning, not really needed as there is always a default

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
        final ReadableRosterStore rosterStore =
                new ReadableRosterStoreImpl(state.getReadableStates(RosterStateId.NAME));
        final RosterHistory rosterHistory = RosterUtils.createRosterHistory(rosterStore);
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
}

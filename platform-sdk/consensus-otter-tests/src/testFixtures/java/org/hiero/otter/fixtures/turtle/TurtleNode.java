// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static com.hedera.node.app.ServicesMain.canonicalEventStreamLoc;
import static com.hedera.node.app.ServicesMain.eventStreamLocOrThrow;
import static com.swirlds.platform.state.signed.StartupStateUtils.loadInitialState;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static com.swirlds.platform.system.InitTrigger.RESTART;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.fail;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.DESTROYED;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.RUNNING;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.SHUTDOWN;
import static org.hiero.otter.fixtures.turtle.TurtleInMemoryAppender.toJSON;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.Hedera;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.node.internal.network.Network;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.component.framework.model.DeterministicWiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.builder.PlatformBuildingBlocks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.wiring.PlatformWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.ThreadContext;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.otter.fixtures.AsyncNodeActions;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.app.OtterApp;
import org.hiero.otter.fixtures.app.OtterAppState;
import org.hiero.otter.fixtures.internal.AbstractNode;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodeMarkerFileResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedGossip;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedNetwork;
import org.hiero.otter.fixtures.util.SecureRandomBuilder;

/**
 * A node in the turtle network.
 *
 * <p>This class implements the {@link Node} interface and provides methods to control the state of the node.
 */
public class TurtleNode extends AbstractNode implements Node, TurtleTimeManager.TimeTickReceiver {

    /** The thread context key for the node ID. */
    public static final String THREAD_CONTEXT_NODE_ID = "nodeId";

    private final Randotron randotron;
    private final Time time;
    //    private final Roster roster;
    private final KeysAndCerts keysAndCerts;
    private final SimulatedNetwork network;
    private final TurtleLogging logging;
    private final TurtleNodeConfiguration nodeConfiguration;
    //    private final NodeResultsCollector resultsCollector;
    //    private final TurtleMarkerFileObserver markerFileObserver;
    private final AsyncNodeActions defaultAsyncActions = new TurtleAsyncNodeActions();

    //    private PlatformContext platformContext;

    @Nullable
    private DeterministicWiringModel model;

    @Nullable
    private Platform platform;

//    @Nullable
//    private ExecutionLayer executionLayer;

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
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, toJSON(selfId));

            this.randotron = requireNonNull(randotron);
            this.time = requireNonNull(time);
            //            this.roster = requireNonNull(roster);
            this.keysAndCerts = requireNonNull(keysAndCerts);
            this.network = requireNonNull(network);
            this.logging = requireNonNull(logging);
            this.nodeConfiguration = new TurtleNodeConfiguration(() -> lifeCycle, outputDirectory);
            //            this.resultsCollector = new NodeResultsCollector(selfId);
            //            this.markerFileObserver = new TurtleMarkerFileObserver(resultsCollector);

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    /**
     * Gets the platform.
     *
     * @return the platform
     */
    @NonNull
    public Platform platform() {
        if (platform == null) {
            throw new IllegalStateException("Platform is not initialized. Has the node been started?");
        }
        return platform;
    }

    /**
     * Gets the time provider.
     *
     * @return the time provider
     */
    @NonNull
    public Time time() {
        return time;
    }

    @Override
    @NonNull
    protected AsyncNodeActions defaultAsyncActions() {
        return defaultAsyncActions;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AsyncNodeActions withTimeout(@NonNull final Duration timeout) {
        return defaultAsyncActions;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransaction(@NonNull final byte[] transaction) {
        throw new UnsupportedOperationException("submitTransaction is not yet supported in TurtleNode.");
        //        try {
        //            ThreadContext.put(THREAD_CONTEXT_NODE_ID, toJSON(selfId));
        //
        //            throwIfIn(INIT, "Node has not been started yet.");
        //            throwIfIn(SHUTDOWN, "Node has been shut down.");
        //            throwIfIn(DESTROYED, "Node has been destroyed.");
        //            assert platform != null; // platform must be initialized if lifeCycle is STARTED
        //            assert executionLayer != null; // executionLayer must be initialized
        //
        //            executionLayer.submitApplicationTransaction(transaction);
        //
        //        } finally {
        //            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        //        }
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
        throw new UnsupportedOperationException("New Consensus is not yet supported for TurtleNode.");
        //        return resultsCollector.newConsensusResult();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public SingleNodeLogResult newLogResult() {
        throw new UnsupportedOperationException("New Log is not yet supported for TurtleNode.");
        //        return resultsCollector.newLogResult();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePlatformStatusResult newPlatformStatusResult() {
        throw new UnsupportedOperationException("New Platform Status is not yet supported for TurtleNode.");
        //        return resultsCollector.newStatusProgression();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePcesResult newPcesResult() {
        throw new UnsupportedOperationException("New PCES is not yet supported for TurtleNode.");
        //        return new SingleNodePcesResultImpl(selfId(), platformContext.getConfiguration());
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
        throw new UnsupportedOperationException("New Marker File is not yet supported for TurtleNode.");
        //        return new SingleNodeMarkerFileResultImpl(resultsCollector);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        if (lifeCycle == RUNNING) {
            assert model != null; // model must be initialized if lifeCycle is STARTED
            try {
                ThreadContext.put(THREAD_CONTEXT_NODE_ID, toJSON(selfId));
                model.tick();
            } finally {
                ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
            }
        }

        //        markerFileObserver.tick(now);
    }

    /**
     * Shuts down the node and cleans up resources. Once this method is called, the node cannot be started again. This
     * method is idempotent and can be called multiple times without any side effects.
     */
    void destroy() {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, toJSON(selfId));

            //            resultsCollector.destroy();
            doShutdownNode();
            lifeCycle = DESTROYED;

            logging.removeNodeLogging(selfId);

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    private void doShutdownNode() {
        if (lifeCycle == RUNNING) {
            //            markerFileObserver.stopObserving();
            assert platform != null; // platform must be initialized if lifeCycle is STARTED
            try {
                platform.destroy();
            } catch (final InterruptedException e) {
                throw new AssertionError("Unexpected interruption during platform shutdown", e);
            }
            platformStatus = null;
            platform = null;
            platformWiring = null;
            model = null;
        }
        lifeCycle = SHUTDOWN;
    }

    public void initHedera(
            @NonNull final Hedera hedera,
            @NonNull final PlatformContext platformContext,
            @NonNull final AddressBook addressBook) {

        final Configuration currentConfiguration = platformContext.getConfiguration();
        //        final Configuration currentConfiguration = nodeConfiguration.current();
        final org.hiero.consensus.model.node.NodeId legacyNodeId =
                org.hiero.consensus.model.node.NodeId.of(selfId.id());

        //        setupGlobalMetrics(currentConfiguration);
        //
        //        final PathsConfig pathsConfig = currentConfiguration.getConfigData(PathsConfig.class);
        //        final Path markerFilesDir = pathsConfig.getMarkerFilesDir();
        //        if (markerFilesDir != null) {
        //            markerFileObserver.startObserving(markerFilesDir);
        //        }

        final PlatformStateFacade platformStateFacade = new PlatformStateFacade();
        //        MerkleDb.resetDefaultInstancePath();
        //        final Metrics metrics = getMetricsProvider().createPlatformMetrics(legacyNodeId);
        //        final FileSystemManager fileSystemManager = FileSystemManager.create(currentConfiguration);
        //        final RecycleBin recycleBin = RecycleBin.create(
        //                metrics, currentConfiguration, getStaticThreadManager(), time, fileSystemManager,
        // legacyNodeId);
        //
        //        platformContext = TestPlatformContextBuilder.create()
        //                .withTime(time)
        //                .withConfiguration(currentConfiguration)
        //                .withFileSystemManager(fileSystemManager)
        //                .withMetrics(metrics)
        //                .withRecycleBin(recycleBin)
        //                .build();

        model = WiringModelBuilder.create(platformContext.getMetrics(), time)
                .withDeterministicModeEnabled(true)
                .withUncaughtExceptionHandler((t, e) -> fail("Unexpected exception in wiring framework", e))
                .build();

        version = hedera.getSemanticVersion();
        final AtomicReference<Network> genesisNetwork = new AtomicReference<>();

        final HashedReservedSignedState reservedState = loadInitialState(
                platformContext.getRecycleBin(),
                version,
                () -> {
                    Network network;
                    try {
                        network = hedera.startupNetworks().genesisNetworkOrThrow(currentConfiguration);
                    } catch (Exception ignore) {
                        // Fallback to the legacy address book if genesis-network.json or equivalent not loaded
                        network = DiskStartupNetworks.fromLegacyAddressBook(
                                addressBook, hedera.bootstrapConfigProvider().getConfiguration());
                    }
                    genesisNetwork.set(network);
                    final var genesisState = hedera.newStateRoot();
                    hedera.initializeStatesApi(genesisState, GENESIS, currentConfiguration);
                    return genesisState;
                },
                Hedera.APP_NAME,
                Hedera.SWIRLD_NAME,
                legacyNodeId,
                platformStateFacade,
                platformContext,
                hedera.stateRootFromVirtualMap());
        final ReservedSignedState initialState = reservedState.state();

        final MerkleNodeState state = initialState.get().getState();
        if (genesisNetwork.get() == null) {
            hedera.initializeStatesApi(state, RESTART, currentConfiguration);
        }
        hedera.setInitialStateHash(reservedState.hash());

        final RosterHistory rosterHistory = RosterUtils.createRosterHistory(state);
        //        final String eventStreamLoc = selfId.toString();

        //        this.executionLayer = new OtterExecutionLayerNo(platformContext.getMetrics());

        final PlatformBuilder platformBuilder = PlatformBuilder.create(
                        OtterApp.APP_NAME,
                        OtterApp.SWIRLD_NAME,
                        version,
                        initialState,
                        hedera.newConsensusStateEvenHandler(),
                        legacyNodeId,
                        // If at genesis, base the event stream location on the genesis network metadata
                        Optional.ofNullable(genesisNetwork.get())
                                .map(network -> eventStreamLocOrThrow(network, selfId.id()))
                                // Otherwise derive if from the node's id in state or
                                .orElseGet(() -> canonicalEventStreamLoc(selfId.id(), state)),
                        rosterHistory,
                        platformStateFacade,
                        OtterAppState::new)
                .withPlatformContext(platformContext)
                .withConfiguration(currentConfiguration)
                .withKeysAndCerts(keysAndCerts)
                .withExecutionLayer(hedera)
                .withModel(model)
                .withSecureRandomSupplier(new SecureRandomBuilder(randotron.nextLong()));

        final PlatformComponentBuilder platformComponentBuilder = platformBuilder.buildComponentBuilder();
        final PlatformBuildingBlocks platformBuildingBlocks = platformComponentBuilder.getBuildingBlocks();

        final SimulatedGossip gossip = network.getGossipInstance(legacyNodeId);
        gossip.provideIntakeEventCounter(platformBuildingBlocks.intakeEventCounter());

        platformComponentBuilder
                .withMetricsDocumentationEnabled(false)
                .withGossip(network.getGossipInstance(legacyNodeId));

        platformWiring = platformBuildingBlocks.platformWiring();

        //        platformWiring
        //                .getConsensusEngineOutputWire()
        //                .solderTo("nodeConsensusRoundsCollector", "consensusRounds", resultsCollector::addConsensusRounds);

        platformWiring
                .getStatusStateMachineOutputWire()
                .solderTo("nodePlatformStatusCollector", "platformStatus", this::handlePlatformStatusChange);

        //        InMemorySubscriptionManager.INSTANCE.subscribe(logEntry -> {
        //            if (Objects.equals(logEntry.nodeId(), selfId)) {
        //                resultsCollector.addLogEntry(logEntry);
        //            }
        //            return lifeCycle == DESTROYED ? UNSUBSCRIBE : CONTINUE;
        //        });

        platform = platformComponentBuilder.build();
        platformStatus = PlatformStatus.STARTING_UP;
    }

    private void doStartNode() {
        platform.start();

        lifeCycle = RUNNING;
    }

    private void handlePlatformStatusChange(@NonNull final PlatformStatus platformStatus) {
        this.platformStatus = requireNonNull(platformStatus);
        //        resultsCollector.addPlatformStatus(platformStatus);
    }

    /**
     * Turtle-specific implementation of {@link AsyncNodeActions}.
     */
    private class TurtleAsyncNodeActions implements AsyncNodeActions {

        /**
         * {@inheritDoc}
         */
        @Override
        public void start() {
            try {
                ThreadContext.put(THREAD_CONTEXT_NODE_ID, toJSON(selfId));

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
        public void killImmediately() {
            try {
                ThreadContext.put(THREAD_CONTEXT_NODE_ID, toJSON(selfId));

                doShutdownNode();

            } finally {
                ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
            }
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
    }
}

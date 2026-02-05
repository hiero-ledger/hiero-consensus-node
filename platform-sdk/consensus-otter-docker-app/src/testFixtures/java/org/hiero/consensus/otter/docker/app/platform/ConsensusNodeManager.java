// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.platform;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.initLogging;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.state.signed.StartupStateUtils.loadInitialState;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.hiero.otter.fixtures.app.OtterStateUtils.createGenesisState;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBinImpl;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.builder.PlatformBuildingBlocks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.listeners.PlatformStatusChangeListener;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.StateLifecycleManagerImpl;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.platformstate.ReadablePlatformStateStore;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterStateUtils;
import org.hiero.otter.fixtures.app.OtterApp;
import org.hiero.otter.fixtures.app.OtterExecutionLayer;

/**
 * Manages the lifecycle and operations of a consensus node within a container-based network. This class initializes the
 * platform, handles configuration, and provides methods for interacting with the consensus process, including
 * submitting transactions and listening for consensus rounds.
 */
public class ConsensusNodeManager {

    private static final Logger log = LogManager.getLogger(ConsensusNodeManager.class);

    /** The instance of the Otter application used by this consensus node manager. */
    private final OtterApp otterApp;

    /** The instance of the platform this consensus node manager runs. */
    private final Platform platform;

    private final OtterExecutionLayer executionCallback;

    /**
     * A threadsafe list of consensus round listeners. Written to by the platform, read by listeners on the dispatch
     * thread.
     */
    private final List<ConsensusRoundListener> consensusRoundListeners = new CopyOnWriteArrayList<>();

    /** The current quiescence command. Volatile because it is read and set by different gRPC messages */
    private volatile QuiescenceCommand quiescenceCommand = QuiescenceCommand.DONT_QUIESCE;

    /**
     * Creates a new instance of {@code ConsensusNodeManager} with the specified parameters. This constructor
     * initializes the platform, sets up all necessary parts for the consensus node.
     *
     * @param selfId the unique identifier for this node, must not be {@code null}
     * @param platformConfig the configuration for the platform, must not be {@code null}
     * @param activeRoster the roster of nodes in the network, must not be {@code null}
     * @param version the semantic version of the platform, must not be {@code null}
     * @param keysAndCerts the keys and certificates for this node, must not
     */
    public ConsensusNodeManager(
            @NonNull final NodeId selfId,
            @NonNull final Configuration platformConfig,
            @NonNull final Roster activeRoster,
            @NonNull final SemanticVersion version,
            @NonNull final KeysAndCerts keysAndCerts) {

        initLogging();
        BootstrapUtils.setupConstructableRegistry();

        setupGlobalMetrics(platformConfig);
        final Metrics metrics = getMetricsProvider().createPlatformMetrics(selfId);

        log.info(STARTUP.getMarker(), "Creating node {} with version {}", selfId, version);

        final Time time = Time.getCurrent();
        final FileSystemManager fileSystemManager = FileSystemManager.create(platformConfig);
        final RecycleBin recycleBin = RecycleBinImpl.create(
                metrics, platformConfig, getStaticThreadManager(), time, fileSystemManager, selfId);

        final PlatformContext platformContext =
                PlatformContext.create(platformConfig, Time.getCurrent(), metrics, fileSystemManager, recycleBin);
        final StateLifecycleManager stateLifecycleManager = new StateLifecycleManagerImpl(
                metrics, time, virtualMap -> new VirtualMapStateImpl(virtualMap, metrics), platformConfig);

        otterApp = new OtterApp(platformConfig, version);

        final HashedReservedSignedState reservedState = loadInitialState(
                recycleBin,
                version,
                () -> createGenesisState(platformConfig, metrics, activeRoster, version, otterApp.allServices()),
                OtterApp.APP_NAME,
                OtterApp.SWIRLD_NAME,
                selfId,
                platformContext,
                stateLifecycleManager);
        final ReservedSignedState initialState = reservedState.state();
        final VirtualMapState state = initialState.get().getState();

        // Set active the roster
        final ReadablePlatformStateStore store =
                new ReadablePlatformStateStore(state.getReadableStates(PlatformStateService.NAME));
        RosterStateUtils.setActiveRoster(state, activeRoster, store.getRound() + 1);

        final RosterHistory rosterHistory = RosterStateUtils.createRosterHistory(state);
        executionCallback = new OtterExecutionLayer(new Random(), metrics, time);
        final PlatformBuilder builder = PlatformBuilder.create(
                        OtterApp.APP_NAME,
                        OtterApp.SWIRLD_NAME,
                        version,
                        initialState,
                        otterApp,
                        selfId,
                        Long.toString(selfId.id()),
                        rosterHistory,
                        stateLifecycleManager)
                .withPlatformContext(platformContext)
                .withConfiguration(platformConfig)
                .withKeysAndCerts(keysAndCerts)
                .withExecutionLayer(executionCallback);

        // Build the platform component builder
        final PlatformComponentBuilder componentBuilder = builder.buildComponentBuilder();
        final PlatformBuildingBlocks blocks = componentBuilder.getBuildingBlocks();

        // Wiring: Forward consensus rounds to registered listeners
        final PlatformComponents platformComponents = blocks.platformComponents();
        platformComponents
                .hashgraphModule()
                .consensusRoundOutputWire()
                .solderTo("dockerApp", "consensusRounds", this::notifyConsensusRoundListeners);

        platform = componentBuilder.build();
    }

    /**
     * Starts the consensus node. Once complete, transactions can be submitted.
     */
    public void start() {
        log.info(STARTUP.getMarker(), "Starting node");
        platform.start();
    }

    /**
     * Registers a listener to receive notifications about changes in the platform's status.
     *
     * @param listener the listener to register, must not be {@code null}
     */
    public void registerPlatformStatusChangeListener(@NonNull final PlatformStatusChangeListener listener) {
        platform.getNotificationEngine().register(PlatformStatusChangeListener.class, listener);
    }

    /**
     * Notifies registered listeners about new consensus rounds.
     *
     * @param round the consensus round to notify listeners about, must not be {@code null}
     */
    private void notifyConsensusRoundListeners(@NonNull final ConsensusRound round) {
        consensusRoundListeners.forEach(listener -> listener.onConsensusRound(round));
    }

    /**
     * Submits a raw transaction to the underlying platform for processing.
     *
     * @param transaction the serialized transaction bytes, must not be {@code null}
     * @return {@code true} if the transaction was successfully submitted, {@code false} otherwise
     */
    public boolean submitTransaction(@NonNull final byte[] transaction) {
        if (quiescenceCommand == QuiescenceCommand.QUIESCE) {
            return false;
        }
        return executionCallback.submitApplicationTransaction(transaction);
    }

    /**
     * Registers a listener to receive notifications about new consensus rounds.
     *
     * @param listener the listener to register, must not be {@code null}
     */
    public void registerConsensusRoundListener(@NonNull final ConsensusRoundListener listener) {
        consensusRoundListeners.add(listener);
    }

    /**
     * Updates the synthetic bottleneck duration engages on the handle thread. Setting this value to zero disables the
     * bottleneck.
     *
     * @param millisToSleepPerRound the number of milliseconds to sleep per round, must be non-negative
     */
    public void updateSyntheticBottleneck(final long millisToSleepPerRound) {
        if (millisToSleepPerRound < 0) {
            throw new IllegalArgumentException("millisToSleepPerRound must be non-negative");
        }
        otterApp.updateSyntheticBottleneck(millisToSleepPerRound);
    }

    /**
     * Sends a quiescence command to the platform.
     *
     * @param command the quiescence command to send, must not be {@code null}
     */
    public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
        this.quiescenceCommand = command;
        platform.quiescenceCommand(command);
    }
}

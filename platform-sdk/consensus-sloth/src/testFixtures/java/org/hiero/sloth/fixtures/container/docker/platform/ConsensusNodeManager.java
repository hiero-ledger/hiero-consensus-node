// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.container.docker.platform;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.initLogging;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.state.signed.StartupStateUtils.loadInitialState;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.hiero.sloth.fixtures.app.SlothStateUtils.initGenesisState;

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
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.metrics.platform.SnapshotEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.platformstate.ReadablePlatformStateStore;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterStateUtils;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.sloth.fixtures.app.SlothApp;
import org.hiero.sloth.fixtures.app.SlothExecutionLayer;
import org.hiero.sloth.fixtures.container.docker.metrics.ToFilePrometheusExporter;

/**
 * Manages the lifecycle and operations of a consensus node within a container-based network.
 */
public class ConsensusNodeManager {

    private static final Logger log = LogManager.getLogger(ConsensusNodeManager.class);

    /** The instance of the Benchmark application used by this consensus node manager. */
    private final SlothApp slothApp;

    /** The instance of the platform this consensus node manager runs. */
    private final Platform platform;

    private final SlothExecutionLayer executionCallback;

    /**
     * A threadsafe list of consensus round listeners.
     */
    private final List<ConsensusRoundListener> consensusRoundListeners = new CopyOnWriteArrayList<>();

    /** The current quiescence command. Volatile because it is read and set by different gRPC messages */
    private volatile QuiescenceCommand quiescenceCommand = QuiescenceCommand.DONT_QUIESCE;

    /**
     * Creates a new instance of {@code ConsensusNodeManager} with the specified parameters.
     *
     * @param selfId the unique identifier for this node
     * @param platformConfig the configuration for the platform
     * @param activeRoster the roster of nodes in the network
     * @param version the semantic version of the platform
     * @param keysAndCerts the keys and certificates for this node
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
        getMetricsProvider().subscribeSnapshot((Consumer<? super SnapshotEvent>)
                new ToFilePrometheusExporter(selfId, platformConfig)::handleSnapshots);

        final PlatformContext platformContext =
                PlatformContext.create(platformConfig, time, metrics, fileSystemManager, recycleBin);
        final VirtualMapStateLifecycleManager stateLifecycleManager =
                new VirtualMapStateLifecycleManager(metrics, time, platformConfig);

        slothApp = new SlothApp(platformConfig, version);

        final HashedReservedSignedState reservedState = loadInitialState(
                recycleBin,
                version,
                SlothApp.APP_NAME,
                SlothApp.SWIRLD_NAME,
                selfId,
                platformContext,
                stateLifecycleManager);
        final ReservedSignedState initialState = reservedState.state();
        final VirtualMapState state = initialState.get().getState();
        if (initialState.get().isGenesisState()) {
            initGenesisState(state, activeRoster, version, slothApp.allServices());
        }

        // Set active the roster
        final ReadablePlatformStateStore store =
                new ReadablePlatformStateStore(state.getReadableStates(PlatformStateService.NAME));
        RosterStateUtils.setActiveRoster(state, activeRoster, store.getRound() + 1);

        final RosterHistory rosterHistory = RosterStateUtils.createRosterHistory(state);
        executionCallback = new SlothExecutionLayer(new Random(), metrics, time);
        final PlatformBuilder builder = PlatformBuilder.create(
                        SlothApp.APP_NAME,
                        SlothApp.SWIRLD_NAME,
                        version,
                        initialState,
                        slothApp,
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
     * Starts the consensus node.
     */
    public void start() {
        log.info(STARTUP.getMarker(), "Starting node");
        platform.start();
    }

    /**
     * Registers a listener to receive notifications about changes in the platform's status.
     *
     * @param listener the listener to register
     */
    public void registerPlatformStatusChangeListener(@NonNull final PlatformStatusChangeListener listener) {
        platform.getNotificationEngine().register(PlatformStatusChangeListener.class, listener);
    }

    private void notifyConsensusRoundListeners(@NonNull final ConsensusRound round) {
        consensusRoundListeners.forEach(listener -> listener.onConsensusRound(round));
    }

    /**
     * Submits a raw transaction to the underlying platform for processing.
     *
     * @param transaction the serialized transaction bytes
     * @return {@code true} if the transaction was successfully submitted, {@code false} otherwise
     */
    public boolean submitTransaction(@NonNull final byte[] transaction) {
        if (quiescenceCommand == QuiescenceCommand.QUIESCE) {
            return false;
        }
        return executionCallback.submitApplicationTransaction(transaction);
    }

    /**
     * Updates the synthetic bottleneck duration.
     *
     * @param millisToSleepPerRound the number of milliseconds to sleep per round
     */
    public void updateSyntheticBottleneck(final long millisToSleepPerRound) {
        if (millisToSleepPerRound < 0) {
            throw new IllegalArgumentException("millisToSleepPerRound must be non-negative");
        }
        slothApp.updateSyntheticBottleneck(millisToSleepPerRound);
    }

    /**
     * Sends a quiescence command to the platform.
     *
     * @param command the quiescence command to send
     */
    public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
        this.quiescenceCommand = command;
        platform.quiescenceCommand(command);
    }
}

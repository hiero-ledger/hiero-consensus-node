// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.StateInitializer.initializeState;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.state.address.RosterMetrics.registerRosterMetrics;
import static org.hiero.base.CompareTo.isLessThan;
import static org.hiero.consensus.platformstate.PlatformStateUtils.ancientThresholdOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.consensusSnapshotOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.legacyRunningEventHashOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.setCreationSoftwareVersionTo;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.PlatformBuildingBlocks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.DefaultAppNotifier;
import com.swirlds.platform.components.DefaultEventWindowManager;
import com.swirlds.platform.components.DefaultSavedStateController;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.event.preconsensus.PcesReplayer;
import com.swirlds.platform.metrics.RuntimeMetrics;
import com.swirlds.platform.reconnect.DefaultSignedStateValidator;
import com.swirlds.platform.reconnect.ReconnectController;
import com.swirlds.platform.state.nexus.DefaultLatestCompleteStateNexus;
import com.swirlds.platform.state.nexus.LatestCompleteStateNexus;
import com.swirlds.platform.state.nexus.LockFreeStateNexus;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.signed.DefaultStateSignatureCollector;
import com.swirlds.platform.state.signed.SignedStateMetrics;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.state.snapshot.SavedStateInfo;
import com.swirlds.platform.state.snapshot.SignedStateFilePath;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.status.actions.DoneReplayingEventsAction;
import com.swirlds.platform.system.status.actions.StartedReplayingEventsAction;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.platform.wiring.PlatformCoordinator;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.concurrent.manager.AdHocThreadManager;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.pces.config.PcesConfig;
import org.hiero.consensus.round.EventWindowUtils;
import org.hiero.consensus.state.config.StateConfig;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;

/**
 * The swirlds consensus node platform. Responsible for the creation, gossip, and consensus of events. Also manages the
 * transaction handling and state management.
 */
public class SwirldsPlatform implements Platform {

    private static final Logger logger = LogManager.getLogger(SwirldsPlatform.class);

    /**
     * The unique ID of this node.
     */
    private final NodeId selfId;

    /**
     * the current nodes in the network and their information
     */
    private final Roster currentRoster;

    /**
     * the object that contains all key pairs and CSPRNG state for this member
     */
    private final KeysAndCerts keysAndCerts;

    /**
     * If a state was loaded from disk, this is the minimum generation non-ancient for that round. If starting from a
     * genesis state, this is 0.
     */
    private final long initialAncientThreshold;

    /**
     * The latest round to have reached consensus in the initial state
     */
    private final long startingRound;

    /**
     * Holds the latest state that is immutable. May be unhashed (in the future), may or may not have all required
     * signatures. State is returned with a reservation.
     * <p>
     * NOTE: This is currently set when a state has finished hashing. In the future, this will be set at the moment a
     * new state is created, before it is hashed.
     */
    private final SignedStateNexus latestImmutableStateNexus = new LockFreeStateNexus();

    /**
     * For passing notifications between the platform and the application.
     */
    private final NotificationEngine notificationEngine;

    /**
     * The platform context for this platform. Should be used to access basic services
     */
    private final PlatformContext platformContext;

    /**
     * Controls which states are saved to disk
     */
    private final SavedStateController savedStateController;

    /**
     * Encapsulated wiring for the platform.
     */
    private final PlatformComponents platformComponents;

    private final long pcesReplayLowerBound;
    private final PlatformCoordinator platformCoordinator;

    /**
     * Constructor.
     *
     * @param builder this object is responsible for building platform components and other things needed by the
     * platform
     */
    public SwirldsPlatform(@NonNull final PlatformComponentBuilder builder) {
        final PlatformBuildingBlocks blocks = builder.getBuildingBlocks();
        platformContext = blocks.platformContext();

        // The reservation on this state is held by the caller of this constructor.
        final SignedState initialState = blocks.initialState().get();

        selfId = blocks.selfId();

        notificationEngine = blocks.notificationEngine();

        logger.info(STARTUP.getMarker(), "Starting with roster history:\n{}", blocks.rosterHistory());
        currentRoster = blocks.rosterHistory().getCurrentRoster();

        final Metrics metrics = platformContext.getMetrics();
        registerRosterMetrics(metrics, currentRoster, selfId);

        RuntimeMetrics.setup(metrics);

        keysAndCerts = blocks.keysAndCerts();

        final LatestCompleteStateNexus latestCompleteStateNexus = new DefaultLatestCompleteStateNexus(platformContext);

        savedStateController = new DefaultSavedStateController(platformContext);

        final SignedStateMetrics signedStateMetrics = new SignedStateMetrics(metrics);
        final StateSignatureCollector stateSignatureCollector =
                new DefaultStateSignatureCollector(platformContext, signedStateMetrics);

        this.platformComponents = blocks.platformComponents();
        this.platformCoordinator = new PlatformCoordinator(blocks.platformComponents(), blocks.applicationCallbacks());

        blocks.statusActionSubmitterReference().set(platformCoordinator);

        final Configuration configuration = platformContext.getConfiguration();
        final Duration replayHealthThreshold =
                configuration.getConfigData(PcesConfig.class).replayHealthThreshold();
        final PcesReplayer pcesReplayer = new PcesReplayer(
                configuration,
                platformContext.getTime(),
                platformComponents.pcesReplayerWiring().eventOutput(),
                platformCoordinator::flushIntakePipeline,
                platformCoordinator::flushTransactionHandler,
                () -> latestImmutableStateNexus.getState("PCES replay"),
                () -> isLessThan(blocks.model().getUnhealthyDuration(), replayHealthThreshold));

        initializeState(this, initialState, blocks.consensusStateEventHandler());

        // This object makes a copy of the state. After this point, initialState becomes immutable.
        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager = blocks.stateLifecycleManager();
        final State state = initialState.getState();
        stateLifecycleManager.initState((VirtualMapState) state);
        // Genesis state must stay empty until changes can be externalized in the block stream
        if (!initialState.isGenesisState()) {
            setCreationSoftwareVersionTo(stateLifecycleManager.getMutableState(), blocks.appVersion());
        }

        final EventWindowManager eventWindowManager = new DefaultEventWindowManager();

        final AppNotifier appNotifier = new DefaultAppNotifier(blocks.notificationEngine());

        final ReconnectController reconnectController = new ReconnectController(
                configuration,
                platformContext.getTime(),
                currentRoster,
                this,
                platformCoordinator,
                stateLifecycleManager,
                savedStateController,
                blocks.consensusStateEventHandler(),
                blocks.reservedSignedStateResultPromise(),
                selfId,
                blocks.fallenBehindMonitor(),
                new DefaultSignedStateValidator());

        final Thread reconnectControllerThread = new ThreadConfiguration(AdHocThreadManager.getStaticThreadManager())
                .setComponent("platform-core")
                .setThreadName("reconnectController")
                .setRunnable(reconnectController)
                .build(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            reconnectController.stopReconnectLoop();
            reconnectControllerThread.interrupt();
        }));

        platformComponents.bind(
                builder,
                pcesReplayer,
                stateSignatureCollector,
                eventWindowManager,
                latestImmutableStateNexus,
                latestCompleteStateNexus,
                savedStateController,
                appNotifier);

        final Hash legacyRunningEventHash = legacyRunningEventHashOf(initialState.getState()) == null
                ? Cryptography.NULL_HASH
                : legacyRunningEventHashOf((initialState.getState()));
        final RunningEventHashOverride runningEventHashOverride =
                new RunningEventHashOverride(legacyRunningEventHash, false);
        platformCoordinator.updateRunningHash(runningEventHashOverride);

        // Load the minimum generation into the pre-consensus event writer
        final String actualMainClassName =
                configuration.getConfigData(StateConfig.class).getMainClassName(blocks.mainClassName());
        final SignedStateFilePath statePath =
                new SignedStateFilePath(configuration.getConfigData(StateCommonConfig.class));
        final List<SavedStateInfo> savedStates =
                statePath.getSavedStateFiles(actualMainClassName, selfId, blocks.swirldName());
        if (!savedStates.isEmpty()) {
            // The minimum generation of non-ancient events for the oldest state snapshot on disk.
            final long minimumGenerationNonAncientForOldestState =
                    savedStates.get(savedStates.size() - 1).metadata().minimumBirthRoundNonAncient();
            platformCoordinator.injectPcesMinimumGenerationToStore(minimumGenerationNonAncientForOldestState);
        }

        final boolean startedFromGenesis = initialState.isGenesisState();

        latestImmutableStateNexus.setState(initialState.reserve("set latest immutable to initial state"));

        if (startedFromGenesis) {
            initialAncientThreshold = 0;
            startingRound = 0;
            platformCoordinator.updateEventWindow(EventWindow.getGenesisEventWindow());
        } else {
            initialAncientThreshold = ancientThresholdOf(initialState.getState());
            startingRound = initialState.getRound();

            platformCoordinator.sendStateToHashLogger(initialState);
            platformCoordinator.injectSignatureCollectorState(
                    initialState.reserve("loading initial state into sig collector"));

            savedStateController.registerSignedStateFromDisk(initialState);

            final ConsensusSnapshot consensusSnapshot =
                    Objects.requireNonNull(consensusSnapshotOf(initialState.getState()));
            platformCoordinator.consensusSnapshotOverride(consensusSnapshot);

            // We only load non-ancient events during start up, so the initial expired threshold will be
            // equal to the ancient threshold when the system first starts. Over time as we get more events,
            // the expired threshold will continue to expand until it reaches its full size.
            final int roundsNonAncient =
                    configuration.getConfigData(ConsensusConfig.class).roundsNonAncient();
            platformCoordinator.updateEventWindow(
                    EventWindowUtils.createEventWindow(consensusSnapshot, roundsNonAncient));
            platformCoordinator.overrideIssDetectorState(initialState.reserve("initialize issDetector"));
        }

        blocks.getLatestCompleteStateReference()
                .set(() -> latestCompleteStateNexus.getState("get latest complete state for reconnect"));

        blocks.latestImmutableStateProviderReference().set(latestImmutableStateNexus::getState);

        if (!initialState.isGenesisState()) {
            pcesReplayLowerBound = initialAncientThreshold;
        } else {
            pcesReplayLowerBound = 0;
        }
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
     * Start this platform.
     */
    @Override
    public void start() {
        logger.info(STARTUP.getMarker(), "Starting platform {}", selfId);

        platformContext.getRecycleBin().start();
        platformContext.getMetrics().start();
        platformCoordinator.start();

        replayPreconsensusEvents();
        platformCoordinator.startGossip();
    }

    @Override
    public void destroy() throws InterruptedException {
        notificationEngine.shutdown();
        platformContext.getRecycleBin().stop();
        platformCoordinator.stop();
        getMetricsProvider().removePlatformMetrics(selfId);
    }

    /**
     * Replay preconsensus events.
     */
    private void replayPreconsensusEvents() {
        platformCoordinator.submitStatusAction(new StartedReplayingEventsAction());

        final IOIterator<PlatformEvent> iterator =
                platformComponents.pcesModule().storedEvents(pcesReplayLowerBound, startingRound);

        logger.info(STARTUP.getMarker(), "replaying preconsensus event stream starting at {}", pcesReplayLowerBound);

        platformCoordinator.injectPcesReplayerIterator(iterator);

        // We have to wait for all the PCES transactions to reach the ISS detector before telling it that PCES replay is
        // done. The PCES replay will flush the intake pipeline, but we have to flush the hasher

        // FUTURE WORK: These flushes can be done by the PCES replayer.
        platformCoordinator.flushStateHasher();
        platformCoordinator.signalEndOfPcesReplay();

        platformCoordinator.submitStatusAction(
                new DoneReplayingEventsAction(platformContext.getTime().now()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public PlatformContext getContext() {
        return platformContext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NotificationEngine getNotificationEngine() {
        return notificationEngine;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Signature sign(@NonNull final byte[] data) {
        return new PlatformSigner(keysAndCerts).sign(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void quiescenceCommand(@NonNull final QuiescenceCommand quiescenceCommand) {
        platformCoordinator.quiescenceCommand(quiescenceCommand);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Roster getRoster() {
        return currentRoster;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    @NonNull
    public <T extends State> AutoCloseableWrapper<T> getLatestImmutableState(@NonNull final String reason) {
        final ReservedSignedState wrapper = latestImmutableStateNexus.getState(reason);
        return wrapper == null
                ? AutoCloseableWrapper.empty()
                : new AutoCloseableWrapper<>((T) wrapper.get().getState(), wrapper::close);
    }
}

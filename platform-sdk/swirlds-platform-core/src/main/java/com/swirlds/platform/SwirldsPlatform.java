// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.common.utility.CompareTo.isLessThan;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static com.swirlds.platform.StateInitializer.initializeState;
import static com.swirlds.platform.event.preconsensus.PcesBirthRoundMigration.migratePcesToBirthRoundMode;
import static com.swirlds.platform.state.BirthRoundStateMigration.modifyStateForBirthRoundMigration;
import static com.swirlds.platform.state.address.RosterMetrics.registerRosterMetrics;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.Signature;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.platform.builder.PlatformBuildingBlocks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.DefaultAppNotifier;
import com.swirlds.platform.components.DefaultEventWindowManager;
import com.swirlds.platform.components.DefaultSavedStateController;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.crypto.PlatformSigner;
import com.swirlds.platform.event.EventCounter;
import com.swirlds.platform.event.preconsensus.PcesConfig;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.event.preconsensus.PcesReplayer;
import com.swirlds.platform.event.preconsensus.PcesUtilities;
import com.swirlds.platform.eventhandling.EventConfig;
import com.swirlds.platform.metrics.RuntimeMetrics;
import com.swirlds.platform.pool.TransactionPoolNexus;
import com.swirlds.platform.publisher.DefaultPlatformPublisher;
import com.swirlds.platform.publisher.PlatformPublisher;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.nexus.DefaultLatestCompleteStateNexus;
import com.swirlds.platform.state.nexus.LatestCompleteStateNexus;
import com.swirlds.platform.state.nexus.LockFreeStateNexus;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.DefaultStateSignatureCollector;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateMetrics;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.state.snapshot.SavedStateInfo;
import com.swirlds.platform.state.snapshot.SignedStateFilePath;
import com.swirlds.platform.state.snapshot.StateDumpRequest;
import com.swirlds.platform.state.snapshot.StateToDiskReason;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.events.BirthRoundMigrationShim;
import com.swirlds.platform.system.events.DefaultBirthRoundMigrationShim;
import com.swirlds.platform.system.status.actions.DoneReplayingEventsAction;
import com.swirlds.platform.system.status.actions.StartedReplayingEventsAction;
import com.swirlds.platform.wiring.PlatformWiring;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

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
     * The initial preconsensus event files read from disk.
     */
    private final PcesFileTracker initialPcesFiles;

    /**
     * Controls which states are saved to disk
     */
    private final SavedStateController savedStateController;

    /**
     * Used to submit application transactions.
     */
    private final TransactionPoolNexus transactionPoolNexus;

    /**
     * Encapsulated wiring for the platform.
     */
    private final PlatformWiring platformWiring;

    /**
     * Flag to indicate whether PCES events were migrated to use birth rounds instead of generation-based ancient
     * age. True indicates events were migrated.
     */
    private final boolean wereEventsMigratedToBirthRound;

    /**
     * Indicates how ancient events are determined, e.g. based on the event's birth round or generation.
     */
    private final AncientMode ancientMode;

    /**
     * Constructor.
     *
     * @param builder this object is responsible for building platform components and other things needed by the
     *                platform
     */
    public SwirldsPlatform(@NonNull final PlatformComponentBuilder builder) {
        final PlatformBuildingBlocks blocks = builder.getBuildingBlocks();
        platformContext = blocks.platformContext();
        final ConsensusStateEventHandler consensusStateEventHandler = blocks.consensusStateEventHandler();

        ancientMode = platformContext
                .getConfiguration()
                .getConfigData(EventConfig.class)
                .getAncientMode();

        // The reservation on this state is held by the caller of this constructor.
        final SignedState initialState = blocks.initialState().get();

        // This method is a no-op if we are not in birth round mode, or if we have already migrated.
        final SoftwareVersion appVersion = blocks.appVersion();
        PlatformStateFacade platformStateFacade = blocks.platformStateFacade();
        modifyStateForBirthRoundMigration(
                initialState, ancientMode, appVersion.getPbjSemanticVersion(), platformStateFacade);

        selfId = blocks.selfId();

        if (ancientMode == AncientMode.BIRTH_ROUND_THRESHOLD) {
            try {
                // This method is a no-op if we have already completed birth round migration or if we are at genesis.
                wereEventsMigratedToBirthRound = migratePcesToBirthRoundMode(
                        platformContext,
                        selfId,
                        initialState.getRound(),
                        platformStateFacade.lowestJudgeGenerationBeforeBirthRoundModeOf(initialState.getState()));

                // re-load the PCES files now that they have been migrated
                final PcesConfig pcesConfig = platformContext.getConfiguration().getConfigData(PcesConfig.class);
                final Path databaseDir = PcesUtilities.getDatabaseDirectory(platformContext, selfId);
                initialPcesFiles = PcesFileReader.readFilesFromDisk(
                        platformContext, databaseDir, initialState.getRound(), pcesConfig.permitGaps(), ancientMode);
            } catch (final IOException e) {
                throw new UncheckedIOException("Birth round migration failed during PCES migration.", e);
            }
        } else {
            wereEventsMigratedToBirthRound = false;
            initialPcesFiles = blocks.initialPcesFiles();
        }

        notificationEngine = blocks.notificationEngine();

        logger.info(STARTUP.getMarker(), "Starting with roster history:\n{}", blocks.rosterHistory());
        currentRoster = blocks.rosterHistory().getCurrentRoster();

        registerRosterMetrics(platformContext.getMetrics(), currentRoster, selfId);

        RuntimeMetrics.setup(platformContext.getMetrics());

        keysAndCerts = blocks.keysAndCerts();

        EventCounter.registerEventCounterMetrics(platformContext.getMetrics());

        final LatestCompleteStateNexus latestCompleteStateNexus = new DefaultLatestCompleteStateNexus(platformContext);

        savedStateController = new DefaultSavedStateController(platformContext);

        final SignedStateMetrics signedStateMetrics = new SignedStateMetrics(platformContext.getMetrics());
        final StateSignatureCollector stateSignatureCollector =
                new DefaultStateSignatureCollector(platformContext, signedStateMetrics);

        this.platformWiring = blocks.platformWiring();

        blocks.statusActionSubmitterReference()
                .set(x -> platformWiring.getStatusActionSubmitter().submitStatusAction(x));

        final Duration replayHealthThreshold = platformContext
                .getConfiguration()
                .getConfigData(PcesConfig.class)
                .replayHealthThreshold();
        final PcesReplayer pcesReplayer = new PcesReplayer(
                platformContext,
                platformWiring.getPcesReplayerEventOutput(),
                platformWiring::flushIntakePipeline,
                platformWiring::flushTransactionHandler,
                () -> latestImmutableStateNexus.getState("PCES replay"),
                () -> isLessThan(blocks.model().getUnhealthyDuration(), replayHealthThreshold));

        initializeState(this, platformContext, initialState, consensusStateEventHandler, platformStateFacade);

        // This object makes a copy of the state. After this point, initialState becomes immutable.
        /**
         * Handles all interaction with {@link ConsensusStateEventHandler}
         */
        SwirldStateManager swirldStateManager = blocks.swirldStateManager();
        swirldStateManager.setInitialState(initialState.getState());

        final EventWindowManager eventWindowManager = new DefaultEventWindowManager();

        blocks.isInFreezePeriodReference().set(swirldStateManager::isInFreezePeriod);

        final BirthRoundMigrationShim birthRoundMigrationShim =
                buildBirthRoundMigrationShim(initialState, ancientMode, platformStateFacade);

        final AppNotifier appNotifier = new DefaultAppNotifier(blocks.notificationEngine());

        final PlatformPublisher publisher = new DefaultPlatformPublisher(blocks.applicationCallbacks());

        platformWiring.bind(
                builder,
                pcesReplayer,
                stateSignatureCollector,
                eventWindowManager,
                birthRoundMigrationShim,
                latestImmutableStateNexus,
                latestCompleteStateNexus,
                savedStateController,
                appNotifier,
                publisher);

        final Hash legacyRunningEventHash =
                platformStateFacade.legacyRunningEventHashOf(initialState.getState()) == null
                        ? Cryptography.NULL_HASH
                        : platformStateFacade.legacyRunningEventHashOf((initialState.getState()));
        final RunningEventHashOverride runningEventHashOverride =
                new RunningEventHashOverride(legacyRunningEventHash, false);
        platformWiring.updateRunningHash(runningEventHashOverride);

        // Load the minimum generation into the pre-consensus event writer
        final String actualMainClassName = platformContext
                .getConfiguration()
                .getConfigData(StateConfig.class)
                .getMainClassName(blocks.mainClassName());
        final SignedStateFilePath statePath =
                new SignedStateFilePath(platformContext.getConfiguration().getConfigData(StateCommonConfig.class));
        final List<SavedStateInfo> savedStates =
                statePath.getSavedStateFiles(actualMainClassName, selfId, blocks.swirldName());
        if (!savedStates.isEmpty()) {
            // The minimum generation of non-ancient events for the oldest state snapshot on disk.
            final long minimumGenerationNonAncientForOldestState =
                    savedStates.get(savedStates.size() - 1).metadata().minimumGenerationNonAncient();
            platformWiring.getPcesMinimumGenerationToStoreInput().inject(minimumGenerationNonAncientForOldestState);
        }

        transactionPoolNexus = blocks.transactionPoolNexus();

        final boolean startedFromGenesis = initialState.isGenesisState();

        latestImmutableStateNexus.setState(initialState.reserve("set latest immutable to initial state"));

        if (startedFromGenesis) {
            initialAncientThreshold = 0;
            startingRound = 0;
            platformWiring.updateEventWindow(EventWindow.getGenesisEventWindow(ancientMode));
        } else {
            initialAncientThreshold = platformStateFacade.ancientThresholdOf(initialState.getState());
            startingRound = initialState.getRound();

            platformWiring.sendStateToHashLogger(initialState);
            platformWiring
                    .getSignatureCollectorStateInput()
                    .put(initialState.reserve("loading initial state into sig collector"));

            savedStateController.registerSignedStateFromDisk(initialState);

            platformWiring.consensusSnapshotOverride(
                    Objects.requireNonNull(platformStateFacade.consensusSnapshotOf(initialState.getState())));

            // We only load non-ancient events during start up, so the initial expired threshold will be
            // equal to the ancient threshold when the system first starts. Over time as we get more events,
            // the expired threshold will continue to expand until it reaches its full size.
            platformWiring.updateEventWindow(new EventWindow(
                    initialState.getRound(), initialAncientThreshold, initialAncientThreshold, ancientMode));
            platformWiring.overrideIssDetectorState(initialState.reserve("initialize issDetector"));
        }

        blocks.getLatestCompleteStateReference()
                .set(() -> latestCompleteStateNexus.getState("get latest complete state for reconnect"));

        final ReconnectStateLoader reconnectStateLoader = new ReconnectStateLoader(
                this,
                platformContext,
                platformWiring,
                swirldStateManager,
                latestImmutableStateNexus,
                savedStateController,
                currentRoster,
                consensusStateEventHandler,
                platformStateFacade);

        blocks.loadReconnectStateReference().set(reconnectStateLoader::loadReconnectState);
        blocks.clearAllPipelinesForReconnectReference().set(platformWiring::clear);
        blocks.latestImmutableStateProviderReference().set(latestImmutableStateNexus::getState);
    }

    /**
     * Builds the birth round migration shim if necessary.
     *
     * @param initialState the initial state
     * @param ancientMode  the ancient mode
     * @return the birth round migration shim, or null if it is not needed
     */
    @Nullable
    private BirthRoundMigrationShim buildBirthRoundMigrationShim(
            @NonNull final SignedState initialState,
            @NonNull final AncientMode ancientMode,
            @NonNull final PlatformStateFacade platformStateFacade) {

        if (ancientMode == AncientMode.GENERATION_THRESHOLD) {
            // We don't need the shim if we haven't migrated to birth round mode.
            return null;
        }
        if (initialState.isGenesisState()) {
            // We don't need the shim if we are starting from genesis.
            return null;
        }

        final State state = initialState.getState();
        return new DefaultBirthRoundMigrationShim(
                platformContext,
                platformStateFacade.firstVersionInBirthRoundModeOf(state),
                platformStateFacade.lastRoundBeforeBirthRoundModeOf(state),
                platformStateFacade.lowestJudgeGenerationBeforeBirthRoundModeOf(state));
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
        platformWiring.start();

        replayPreconsensusEvents();
        platformWiring.startGossip();
    }

    /**
     * Performs a PCES recovery:
     * <ul>
     *     <li>Starts all components for handling events</li>
     *     <li>Does not start gossip</li>
     *     <li>Replays events from PCES, reaches consensus on them and handles them</li>
     *     <li>Saves the last state produces by this replay to disk</li>
     * </ul>
     */
    public void performPcesRecovery() {
        platformContext.getRecycleBin().start();
        platformContext.getMetrics().start();
        platformWiring.start();

        replayPreconsensusEvents();
        try (final ReservedSignedState reservedState = latestImmutableStateNexus.getState("Get PCES recovery state")) {
            if (reservedState == null) {
                logger.warn(
                        STATE_TO_DISK.getMarker(),
                        "Trying to dump PCES recovery state to disk, but no state is available.");
            } else {
                final SignedState signedState = reservedState.get();
                signedState.markAsStateToSave(StateToDiskReason.PCES_RECOVERY_COMPLETE);

                final StateDumpRequest request =
                        StateDumpRequest.create(signedState.reserve("dumping PCES recovery state"));

                platformWiring.getDumpStateToDiskInput().put(request);
                request.waitForFinished().run();
            }
        }
    }

    /**
     * Replay preconsensus events.
     */
    private void replayPreconsensusEvents() {
        platformWiring.getStatusActionSubmitter().submitStatusAction(new StartedReplayingEventsAction());

        final long lowerBound = wereEventsMigratedToBirthRound
                ? 0L // events were migrated so set the lower bound to 0 such that all PCES events will be read
                : initialAncientThreshold;

        final IOIterator<PlatformEvent> iterator = initialPcesFiles.getEventIterator(lowerBound, startingRound);

        logger.info(
                STARTUP.getMarker(),
                "replaying preconsensus event stream starting at {} ({})",
                lowerBound,
                ancientMode);

        platformWiring.getPcesReplayerIteratorInput().inject(iterator);

        // We have to wait for all the PCES transactions to reach the ISS detector before telling it that PCES replay is
        // done. The PCES replay will flush the intake pipeline, but we have to flush the hasher

        // FUTURE WORK: These flushes can be done by the PCES replayer.
        platformWiring.flushStateHasher();
        platformWiring.signalEndOfPcesReplay();

        platformWiring
                .getStatusActionSubmitter()
                .submitStatusAction(
                        new DoneReplayingEventsAction(platformContext.getTime().now()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean createTransaction(@NonNull final byte[] transaction) {
        return transactionPoolNexus.submitApplicationTransaction(Bytes.wrap(transaction));
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

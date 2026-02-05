// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.recovery;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.eventhandling.DefaultTransactionPrehandler.NO_OP_CONSUMER;
import static com.swirlds.platform.util.BootstrapUtils.setupConstructableRegistry;
import static org.hiero.consensus.platformstate.PlatformStateUtils.bulkUpdateOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.creationSoftwareVersionOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.freezeTimeOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.legacyRunningEventHashOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.updateLastFrozenTime;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.stream.RunningHashCalculatorForStream;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.recovery.internal.EventStreamRoundIterator;
import com.swirlds.platform.recovery.internal.StreamedRound;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import com.swirlds.platform.state.snapshot.SignedStateFileWriter;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.platform.system.state.notifications.NewRecoveredStateListener;
import com.swirlds.platform.system.state.notifications.NewRecoveredStateNotification;
import com.swirlds.platform.util.HederaUtils;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.StateLifecycleManagerImpl;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.CompareTo;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.crypto.ConsensusCryptoUtils;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.hashgraph.impl.consensus.SyntheticSnapshot;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pces.config.PcesConfig;
import org.hiero.consensus.pces.config.PcesFileWriterType;
import org.hiero.consensus.pces.impl.common.PcesFile;
import org.hiero.consensus.pces.impl.common.PcesMutableFile;

/**
 * Handles the event stream recovery workflow.
 */
public final class EventRecoveryWorkflow {

    private static final Logger logger = LogManager.getLogger(EventRecoveryWorkflow.class);

    private EventRecoveryWorkflow() {}

    /**
     * Read a signed state from disk and apply events from an event stream on disk. Write the resulting signed state to
     * disk.
     *
     * @param platformContext         the platform context
     * @param signedStateDir         the bootstrap signed state file
     * @param eventStreamDirectory    a directory containing the event stream
     * @param finalRound              stop reapplying events after this round has been generated
     * @param resultingStateDirectory the location where the resulting state will be written
     * @param selfId                  the self ID of the node
     * @param allowPartialRounds      if true then allow the last round to be missing events, if false then ignore the
     *                                last round if it does not have all of its events
     * @param loadSigningKeys         if true then load the signing keys
     */
    public static void recoverState(
            @NonNull final PlatformContext platformContext,
            @NonNull final Path signedStateDir,
            @NonNull final Path eventStreamDirectory,
            @NonNull final Boolean allowPartialRounds,
            @NonNull final Long finalRound,
            @NonNull final Path resultingStateDirectory,
            @NonNull final NodeId selfId,
            final boolean loadSigningKeys)
            throws IOException, ParseException {
        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(signedStateDir, "signedStateDir must not be null");
        Objects.requireNonNull(eventStreamDirectory, "eventStreamDirectory must not be null");
        Objects.requireNonNull(allowPartialRounds, "allowPartialRounds must not be null");
        Objects.requireNonNull(finalRound, "finalRound must not be null");
        Objects.requireNonNull(resultingStateDirectory, "resultingStateDirectory must not be null");
        Objects.requireNonNull(selfId, "selfId must not be null");

        setupConstructableRegistry();

        if (!Files.exists(resultingStateDirectory)) {
            Files.createDirectories(resultingStateDirectory);
        }

        logger.info(STARTUP.getMarker(), "Loading state from {}", signedStateDir);
        // FUTURE-WORK: Follow Browser approach
        final SwirldMain<? extends MerkleNodeState> hederaApp = HederaUtils.createHederaAppMain(platformContext);

        final StateLifecycleManager stateLifecycleManager = new StateLifecycleManagerImpl(
                platformContext.getMetrics(),
                platformContext.getTime(),
                virtualMap -> new VirtualMapState(virtualMap, platformContext.getMetrics()),
                platformContext.getConfiguration());

        final DeserializedSignedState deserializedSignedState =
                SignedStateFileReader.readState(signedStateDir, platformContext, stateLifecycleManager);

        try (final ReservedSignedState initialState = deserializedSignedState.reservedSignedState()) {
            HederaUtils.updateStateHash(hederaApp, deserializedSignedState);

            logger.info(
                    STARTUP.getMarker(),
                    "State from round {} loaded.",
                    initialState.get().getRound());
            logger.info(STARTUP.getMarker(), "Loading event stream at {}", eventStreamDirectory);

            final IOIterator<StreamedRound> roundIterator = new EventStreamRoundIterator(
                    initialState.get().getRoster(),
                    eventStreamDirectory,
                    initialState.get().getRound() + 1,
                    allowPartialRounds);

            logger.info(STARTUP.getMarker(), "Reapplying transactions");

            final RecoveredState recoveredState = reapplyTransactions(
                    platformContext,
                    initialState.getAndReserve("recoverState()"),
                    hederaApp,
                    roundIterator,
                    finalRound,
                    selfId,
                    loadSigningKeys);

            logger.info(
                    STARTUP.getMarker(),
                    "Finished reapplying transactions, writing state to {}",
                    resultingStateDirectory);

            // Make one more copy to force the state in recoveredState to be immutable.
            final State mutableStateCopy =
                    recoveredState.state().get().getState().copy();

            SignedStateFileWriter.writeSignedStateFilesToDirectory(
                    platformContext,
                    selfId,
                    resultingStateDirectory,
                    recoveredState.state().get(),
                    stateLifecycleManager);

            logger.info(STARTUP.getMarker(), "Signed state written to disk");

            final PcesFile preconsensusEventFile = PcesFile.of(
                    Instant.now(),
                    0,
                    recoveredState.judge().getBirthRound(),
                    recoveredState.judge().getBirthRound(),
                    recoveredState.state().get().getRound(),
                    resultingStateDirectory);
            final PcesFileWriterType type = platformContext
                    .getConfiguration()
                    .getConfigData(PcesConfig.class)
                    .pcesFileWriterType();
            final PcesMutableFile mutableFile = preconsensusEventFile.getMutableFile(type);
            mutableFile.writeEvent(recoveredState.judge());
            mutableFile.close();

            recoveredState.state().close();
            mutableStateCopy.release();

            logger.info(STARTUP.getMarker(), "Recovery process completed");
        }
    }

    /**
     * Send a notification that the recovered state has been calculated.
     *
     * @param notificationEngine the notification engine used to dispatch the notification
     * @param recoveredState     the recovered state
     */
    private static void notifyStateRecovered(
            final NotificationEngine notificationEngine, final SignedState recoveredState) {
        final NewRecoveredStateNotification notification = new NewRecoveredStateNotification(
                recoveredState.getState(), recoveredState.getRound(), recoveredState.getConsensusTimestamp());
        notificationEngine.dispatch(NewRecoveredStateListener.class, notification);
    }

    /**
     * Apply transactions on top of a state to produce a new state
     *
     * @param platformContext the platform context
     * @param initialSignedState    the starting signed state
     * @param appMain         the {@link SwirldMain} for the app. Ignored if null.
     * @param roundIterator   an iterator that walks over transactions
     * @param finalRound      the last round to apply to the state (inclusive), will stop earlier if the event stream
     *                        does not have events from the final round
     * @param selfId          the self ID of the node
     * @param loadSigningKeys if true then load the signing keys
     * @return the resulting signed state
     * @throws IOException if there is a problem reading from the event stream file
     */
    @NonNull
    public static RecoveredState reapplyTransactions(
            @NonNull final PlatformContext platformContext,
            @NonNull final ReservedSignedState initialSignedState,
            @NonNull final SwirldMain<? extends MerkleNodeState> appMain,
            @NonNull final IOIterator<StreamedRound> roundIterator,
            final long finalRound,
            @NonNull final NodeId selfId,
            final boolean loadSigningKeys)
            throws IOException {

        Objects.requireNonNull(platformContext, "platformContext must not be null");
        Objects.requireNonNull(initialSignedState, "initialSignedState must not be null");
        Objects.requireNonNull(appMain, "appMain must not be null");
        Objects.requireNonNull(roundIterator, "roundIterator must not be null");
        Objects.requireNonNull(selfId, "selfId must not be null");

        final Configuration configuration = platformContext.getConfiguration();

        final ReservedSignedState workingSignedState = ensureMutableState(initialSignedState, configuration);
        final MerkleNodeState initialState = workingSignedState.get().getState();
        initialState.throwIfImmutable("initial state must be mutable");

        logger.info(STARTUP.getMarker(), "Initializing application state");

        final RecoveryPlatform platform =
                new RecoveryPlatform(configuration, workingSignedState.get(), selfId, loadSigningKeys);

        final ConsensusStateEventHandler consensusStateEventHandler = appMain.newConsensusStateEvenHandler();
        final SemanticVersion softwareVersion = creationSoftwareVersionOf(initialState);
        final var notificationEngine = platform.getNotificationEngine();
        notificationEngine.register(
                NewRecoveredStateListener.class,
                notification -> consensusStateEventHandler.onNewRecoveredState(notification.getState()));
        consensusStateEventHandler.onStateInitialized(
                initialState, platform, InitTrigger.EVENT_STREAM_RECOVERY, softwareVersion);
        appMain.init(platform, platform.getSelfId());

        ReservedSignedState signedState = workingSignedState;

        // Apply events to the state
        ConsensusEvent lastEvent = null;
        while (roundIterator.hasNext()
                && (finalRound == -1 || roundIterator.peek().getRoundNum() <= finalRound)) {
            final StreamedRound round = roundIterator.next();

            logger.info(
                    STARTUP.getMarker(),
                    "Applying {} events from round {}",
                    round.getEventCount(),
                    round.getRoundNum());

            signedState = handleNextRound(consensusStateEventHandler, platformContext, signedState, round);
            platform.setLatestState(signedState.get());
            lastEvent = getLastEvent(round);
        }

        logger.info(STARTUP.getMarker(), "Hashing resulting signed state");
        signedState.get().getState().getHash();
        logger.info(STARTUP.getMarker(), "Hashing complete");

        // Let the application know about the recovered state
        notifyStateRecovered(platform.getNotificationEngine(), signedState.get());

        platform.close();

        return new RecoveredState(signedState, ((CesEvent) Objects.requireNonNull(lastEvent)).getPlatformEvent());
    }

    private static ReservedSignedState ensureMutableState(
            @NonNull final ReservedSignedState initialSignedState, @NonNull final Configuration configuration) {
        final SignedState signedState = initialSignedState.get();
        final MerkleNodeState state = signedState.getState();
        if (!state.isHashed()) {
            return initialSignedState;
        }

        // Snapshot loading hashes the map, which freezes leaf mutations; copy to regain mutability.
        final MerkleNodeState mutableState = state.copy();
        final SignedState mutableSignedState = new SignedState(
                configuration,
                ConsensusCryptoUtils::verifySignature,
                mutableState,
                "EventRecoveryWorkflow.ensureMutableState()",
                signedState.isFreezeState(),
                false,
                signedState.isPcesRound());
        mutableSignedState.setSigSet(signedState.getSigSet().copy());

        initialSignedState.close();
        return mutableSignedState.reserve("EventRecoveryWorkflow.ensureMutableState()");
    }

    /**
     * Apply a single round and generate a new state. The previous state is released.
     *
     * @param platformContext the current context
     * @param previousSignedState   the previous round's signed state
     * @param round           the next round
     * @return the resulting signed state
     */
    private static ReservedSignedState handleNextRound(
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final PlatformContext platformContext,
            @NonNull final ReservedSignedState previousSignedState,
            @NonNull final StreamedRound round) {

        final Instant currentRoundTimestamp = getRoundTimestamp(round);
        final SignedState previousState = previousSignedState.get();
        previousState.getState().throwIfImmutable();
        final MerkleNodeState newState = previousState.getState().copy();
        final PlatformEvent lastEvent = ((CesEvent) getLastEvent(round)).getPlatformEvent();
        final ConsensusConfig config = platformContext.getConfiguration().getConfigData(ConsensusConfig.class);
        new DefaultEventHasher().hashEvent(lastEvent);

        bulkUpdateOf(newState, v -> {
            v.setRound(round.getRoundNum());
            v.setLegacyRunningEventHash(getHashEventsCons(legacyRunningEventHashOf(newState), round));
            v.setConsensusTimestamp(currentRoundTimestamp);
            v.setSnapshot(SyntheticSnapshot.generateSyntheticSnapshot(
                    round.getRoundNum(), lastEvent.getConsensusOrder(), currentRoundTimestamp, config, lastEvent));
            v.setCreationSoftwareVersion(creationSoftwareVersionOf(previousState.getState()));
        });

        applyTransactions(consensusStateEventHandler, previousState.getState(), newState, round);

        final boolean isFreezeState =
                isFreezeState(previousState.getConsensusTimestamp(), currentRoundTimestamp, freezeTimeOf(newState));
        if (isFreezeState) {
            updateLastFrozenTime(newState);
        }

        final SignedState signedState = new SignedState(
                platformContext.getConfiguration(),
                ConsensusCryptoUtils::verifySignature,
                newState,
                "EventRecoveryWorkflow.handleNextRound()",
                isFreezeState,
                false,
                false);
        final ReservedSignedState reservedSignedState = signedState.reserve("recovery");
        previousSignedState.close();

        return reservedSignedState;
    }

    /**
     * Calculate the running hash at the end of a round.
     *
     * @param previousRunningHash the previous running hash
     * @param round               the current round
     * @return the running event hash at the end of the current round
     */
    static Hash getHashEventsCons(final Hash previousRunningHash, final StreamedRound round) {
        final RunningHashCalculatorForStream<CesEvent> hashCalculator = new RunningHashCalculatorForStream<>();
        hashCalculator.setRunningHash(previousRunningHash);

        for (final ConsensusEvent event : round) {
            hashCalculator.addObject((CesEvent) event);
        }

        final Hash runningHash = hashCalculator.getRunningHash();
        hashCalculator.close();

        return runningHash;
    }

    /**
     * Get the timestamp for a round (equivalent to the timestamp of the last event).
     *
     * @param round the current round
     * @return the round's timestamp
     */
    static Instant getRoundTimestamp(final Round round) {
        return getLastEvent(round).getConsensusTimestamp();
    }

    static ConsensusEvent getLastEvent(final Round round) {
        final Iterator<ConsensusEvent> iterator = round.iterator();

        while (iterator.hasNext()) {
            final ConsensusEvent event = iterator.next();

            if (!iterator.hasNext()) {
                return event;
            }
        }

        throw new IllegalStateException("round has no events");
    }

    /**
     * Apply the next round of transactions and produce a new swirld state.
     *
     * @param immutableState the immutable swirld state for the previous round
     * @param mutableState   the swirld state for the current round
     * @param round          the current round
     */
    static void applyTransactions(
            final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler,
            final MerkleNodeState immutableState,
            final MerkleNodeState mutableState,
            final Round round) {

        mutableState.throwIfImmutable();

        for (final ConsensusEvent event : round) {
            consensusStateEventHandler.onPreHandle(event, immutableState, NO_OP_CONSUMER);
        }

        consensusStateEventHandler.onHandleConsensusRound(round, mutableState, NO_OP_CONSUMER);

        // FUTURE WORK: there are currently no system transactions that are capable of modifying
        //  the state. If/when system transactions capable of modifying state are added, this workflow
        //  must be updated to reflect that behavior.
    }

    /**
     * Check if this state should be a freeze state.
     *
     * @param previousRoundTimestamp the timestamp of the previous round
     * @param currentRoundTimestamp  the timestamp of the current round
     * @param freezeTime             the freeze time in the state
     * @return true if this round will create a freeze state
     */
    static boolean isFreezeState(
            final Instant previousRoundTimestamp, final Instant currentRoundTimestamp, final Instant freezeTime) {

        if (freezeTime == null) {
            return false;
        }

        return CompareTo.isLessThan(previousRoundTimestamp, freezeTime)
                && CompareTo.isGreaterThanOrEqualTo(currentRoundTimestamp, freezeTime);
    }
}

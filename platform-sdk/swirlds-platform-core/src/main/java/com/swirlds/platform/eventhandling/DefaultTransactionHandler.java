// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.eventhandling.TransactionHandlerPhase.CREATING_SIGNED_STATE;
import static com.swirlds.platform.eventhandling.TransactionHandlerPhase.GETTING_STATE_TO_SIGN;
import static com.swirlds.platform.eventhandling.TransactionHandlerPhase.HANDLING_CONSENSUS_ROUND;
import static com.swirlds.platform.eventhandling.TransactionHandlerPhase.IDLE;
import static com.swirlds.platform.eventhandling.TransactionHandlerPhase.SETTING_EVENT_CONSENSUS_DATA;
import static com.swirlds.platform.eventhandling.TransactionHandlerPhase.UPDATING_PLATFORM_STATE;
import static com.swirlds.platform.eventhandling.TransactionHandlerPhase.UPDATING_PLATFORM_STATE_RUNNING_HASH;
import static com.swirlds.platform.eventhandling.TransactionHandlerPhase.WAITING_FOR_PREHANDLE;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.platform.components.transaction.system.ScopedSystemTransaction;
import com.swirlds.platform.consensus.ConsensusConfig;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.metrics.RoundHandlingMetrics;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.system.status.actions.FreezePeriodEnteredAction;
import com.swirlds.platform.wiring.PlatformSchedulersConfig;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.Queue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A standard implementation of {@link TransactionHandler}.
 */
public class DefaultTransactionHandler implements TransactionHandler {

    private static final Logger logger = LogManager.getLogger(DefaultTransactionHandler.class);

    /**
     * The class responsible for all interactions with the swirld state
     */
    private final SwirldStateManager swirldStateManager;

    private final RoundHandlingMetrics handlerMetrics;

    /**
     * Whether a round in a freeze period has been received. This may never be reset to false after it is set to true.
     */
    private boolean freezeRoundReceived = false;

    /**
     * The legacy running event hash (used by the soon-to-be-retired consensus event stream) from the previous round. We
     * need to save this here because of a quirk in the way the CES handles empty rounds. This legacy hash is always
     * taken from the last consensus event when a round reaches consensus, which means that when a round has zero events
     * we need to reuse the previous round's hash.
     */
    private Hash previousRoundLegacyRunningEventHash;

    /**
     * Enables access to the platform state.
     */
    private final PlatformStateFacade platformStateFacade;

    /**
     * Enables submitting platform status actions.
     */
    private final StatusActionSubmitter statusActionSubmitter;

    private final SemanticVersion softwareVersion;

    /**
     * The number of non-ancient rounds.
     */
    private final int roundsNonAncient;

    private final PlatformContext platformContext;

    /**
     * If true then write the legacy running event hash each round.
     */
    private final boolean writeLegacyRunningEventHash;

    /**
     * If true then wait for application transactions to be prehandled before handling the consensus round.
     */
    private final boolean waitForPrehandle;

    /**
     * An estimation of the hash complexity of the next state to be sent for hashing. The number of transactions is used
     * to estimate this value, which is ultimately used by the health monitor. Some states may not be hashed, so this
     * value is an accumulation.
     */
    private long accumulatedHashComplexity = 0;

    /**
     * Constructor
     *
     * @param platformContext       contains various platform utilities
     * @param swirldStateManager    the swirld state manager to send events to
     * @param statusActionSubmitter enables submitting of platform status actions
     * @param softwareVersion       the current version of the software
     * @param platformStateFacade   enables access to the platform state
     */
    public DefaultTransactionHandler(
            @NonNull final PlatformContext platformContext,
            @NonNull final SwirldStateManager swirldStateManager,
            @NonNull final StatusActionSubmitter statusActionSubmitter,
            @NonNull final SemanticVersion softwareVersion,
            @NonNull final PlatformStateFacade platformStateFacade) {

        this.platformContext = Objects.requireNonNull(platformContext);
        this.swirldStateManager = Objects.requireNonNull(swirldStateManager);
        this.statusActionSubmitter = Objects.requireNonNull(statusActionSubmitter);
        this.softwareVersion = Objects.requireNonNull(softwareVersion);

        this.roundsNonAncient = platformContext
                .getConfiguration()
                .getConfigData(ConsensusConfig.class)
                .roundsNonAncient();
        this.handlerMetrics = new RoundHandlingMetrics(platformContext);

        previousRoundLegacyRunningEventHash = Cryptography.NULL_HASH;
        this.platformStateFacade = platformStateFacade;

        final PlatformSchedulersConfig schedulersConfig =
                platformContext.getConfiguration().getConfigData(PlatformSchedulersConfig.class);

        // If the CES is using a no-op scheduler then the legacy running event hash won't be computed.
        writeLegacyRunningEventHash = schedulersConfig.consensusEventStream().type() != TaskSchedulerType.NO_OP;

        // If the application transaction prehandler is a no-op then we don't need to wait for it.
        waitForPrehandle = schedulersConfig.applicationTransactionPrehandler().type() != TaskSchedulerType.NO_OP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateLegacyRunningEventHash(@NonNull final RunningEventHashOverride runningHashUpdate) {
        previousRoundLegacyRunningEventHash = runningHashUpdate.legacyRunningEventHash();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public TransactionHandlerResult handleConsensusRound(@NonNull final ConsensusRound consensusRound) {
        // consensus rounds with no events are ignored
        if (consensusRound.isEmpty()) {
            // Future work: the long term goal is for empty rounds to not be ignored here. For now, the way that the
            // running hash of consensus events is calculated by the EventStreamManager prevents that from being
            // possible.
            logger.info(STARTUP.getMarker(), "Ignoring empty consensus round {}", consensusRound.getRoundNum());
            return null;
        }

        // Once there is a saved state created in a freeze period, we will never apply any more rounds to the state.
        if (freezeRoundReceived) {
            logger.info(
                    STARTUP.getMarker(),
                    "Round {} reached consensus after freeze. Round will not be processed until after network "
                            + "restarts.",
                    consensusRound.getRoundNum());
            return null;
        }

        if (swirldStateManager.isInFreezePeriod(consensusRound.getConsensusTimestamp())) {
            statusActionSubmitter.submitStatusAction(new FreezePeriodEnteredAction(consensusRound.getRoundNum()));
            freezeRoundReceived = true;
        }

        handlerMetrics.recordEventsPerRound(consensusRound.getNumEvents());
        handlerMetrics.recordConsensusTime(consensusRound.getConsensusTimestamp());

        try {
            handlerMetrics.setPhase(SETTING_EVENT_CONSENSUS_DATA);
            for (final PlatformEvent event : consensusRound.getConsensusEvents()) {
                event.setConsensusTimestampsOnTransactions();
            }

            handlerMetrics.setPhase(UPDATING_PLATFORM_STATE);
            // it is important to update the platform state before handling the consensus round, since the platform
            // state is passed into the application handle method, and should contain the data for the current round
            updatePlatformState(consensusRound);

            if (waitForPrehandle) {
                handlerMetrics.setPhase(WAITING_FOR_PREHANDLE);
                consensusRound.getConsensusEvents().forEach(PlatformEvent::awaitPrehandleCompletion);
            }

            handlerMetrics.setPhase(HANDLING_CONSENSUS_ROUND);
            final var systemTransactions = swirldStateManager.handleConsensusRound(consensusRound);

            handlerMetrics.setPhase(UPDATING_PLATFORM_STATE_RUNNING_HASH);
            updateRunningEventHash(consensusRound);

            return createSignedState(consensusRound, systemTransactions);
        } catch (final InterruptedException e) {
            logger.error(EXCEPTION.getMarker(), "onHandleConsensusRound interrupted");
            Thread.currentThread().interrupt();

            return null;
        } finally {
            handlerMetrics.setPhase(IDLE);
        }
    }

    /**
     * Populate the {@link PlatformStateModifier} with all needed data for this round.
     *
     * @param round the consensus round
     */
    private void updatePlatformState(@NonNull final ConsensusRound round) {
        platformStateFacade.bulkUpdateOf(swirldStateManager.getConsensusState(), v -> {
            v.setRound(round.getRoundNum());
            v.setConsensusTimestamp(round.getConsensusTimestamp());
            v.setCreationSoftwareVersion(softwareVersion);
            v.setRoundsNonAncient(roundsNonAncient);
            v.setSnapshot(round.getSnapshot());
        });
    }

    /**
     * Update the state with the running event hash.
     *
     * @param round the consensus round
     * @throws InterruptedException if this thread is interrupted
     */
    private void updateRunningEventHash(@NonNull final ConsensusRound round) throws InterruptedException {
        final State consensusState = swirldStateManager.getConsensusState();

        if (writeLegacyRunningEventHash) {
            // Update the running hash object. If there are no events, the running hash does not change.
            // Future work: this is a redundant check, since empty rounds are currently ignored entirely. The check is
            // here anyway, for when that changes in the future.
            if (!round.isEmpty()) {
                previousRoundLegacyRunningEventHash = round.getStreamedEvents()
                        .getLast()
                        .getRunningHash()
                        .getFutureHash()
                        .getAndRethrow();
            }

            platformStateFacade.setLegacyRunningEventHashTo(consensusState, previousRoundLegacyRunningEventHash);
        } else {
            platformStateFacade.setLegacyRunningEventHashTo(consensusState, Cryptography.NULL_HASH);
        }
    }

    /**
     * Create a signed state
     *
     * @param consensusRound the consensus round that resulted in the state being created
     * @return a StateAndRound object containing the signed state and the consensus round
     * @throws InterruptedException if this thread is interrupted
     */
    @NonNull
    private TransactionHandlerResult createSignedState(
            @NonNull final ConsensusRound consensusRound,
            @NonNull final Queue<ScopedSystemTransaction<StateSignatureTransaction>> systemTransactions)
            throws InterruptedException {
        if (freezeRoundReceived) {
            // Let the swirld state manager know we are about to write the saved state for the freeze period
            swirldStateManager.savedStateInFreezePeriod();
        }

        final boolean isBoundary = swirldStateManager.sealConsensusRound(consensusRound);
        final ReservedSignedState reservedSignedState;
        if (isBoundary || freezeRoundReceived) {
            handlerMetrics.setPhase(GETTING_STATE_TO_SIGN);
            final MerkleNodeState immutableStateCons = swirldStateManager.getStateForSigning();

            handlerMetrics.setPhase(CREATING_SIGNED_STATE);
            final SignedState signedState = new SignedState(
                    platformContext.getConfiguration(),
                    CryptoStatic::verifySignature,
                    immutableStateCons,
                    "TransactionHandler.createSignedState()",
                    freezeRoundReceived,
                    true,
                    consensusRound.isPcesRound(),
                    platformStateFacade);
            signedState.init(platformContext);

            reservedSignedState = signedState.reserve("transaction handler output");

            // Estimate the amount of work it will be to calculate the hash of this state. The primary modifier
            // of the state is transactions, so that's our best bet.
            final long hashComplexity = Math.max(accumulatedHashComplexity, 1);
            final TransactionHandlerResult result = new TransactionHandlerResult(
                    new StateWithHashComplexity(reservedSignedState, hashComplexity), systemTransactions);
            accumulatedHashComplexity = 0;

            return result;
        } else {
            // Only include non-system transactions, because system transactions do not modify the state
            accumulatedHashComplexity += consensusRound.getNumAppTransactions() - systemTransactions.size();
            return new TransactionHandlerResult(null, systemTransactions);
        }
    }
}

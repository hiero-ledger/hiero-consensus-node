// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.node.app.workflows.handle.HandleWorkflow.ALERT_MESSAGE;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.swirlds.common.stream.LinkedObjectStreamUtilities.getPeriod;
import static java.time.ZoneOffset.UTC;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.records.ReadableBlockRecordStore;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUpdater;
import com.hedera.node.app.service.token.records.TokenContext;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.types.StreamMode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.time.LocalDate;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.roster.WritableRosterStore;

/**
 * Orchestrates changes that happen as a side effect of a transaction crossing a staking period boundary. See
 * {@link #advanceTimeTo(ParentTxn, boolean)} for details.
 */
@Singleton
public class StakePeriodChanges {
    private static final Logger logger = LogManager.getLogger(StakePeriodChanges.class);

    private static final long DEFAULT_STAKING_PERIOD_MINS = 1440L;
    private static final long MINUTES_TO_MILLISECONDS = 60_000L;

    private final EndOfStakingPeriodUpdater endOfStakingPeriodUpdater;
    private final ExchangeRateManager exchangeRateManager;
    private final BlockRecordManager blockRecordManager;
    private final BlockStreamManager blockStreamManager;
    private final StreamMode streamMode;

    @Inject
    public StakePeriodChanges(
            @NonNull final ConfigProvider configProvider,
            @NonNull final EndOfStakingPeriodUpdater endOfStakingPeriodUpdater,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final BlockRecordManager blockRecordManager,
            @NonNull final BlockStreamManager blockStreamManager) {
        this.endOfStakingPeriodUpdater = requireNonNull(endOfStakingPeriodUpdater);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.blockRecordManager = requireNonNull(blockRecordManager);
        this.blockStreamManager = requireNonNull(blockStreamManager);
        this.streamMode = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .streamMode();
    }

    /**
     * Orchestrates changes that happen as side effects of a transaction crossing a staking period boundary, as follows:
     * <ol>
     *     <li>Saves the current exchange rates as the "midnight rates" that tether the rates within the
     *     following period to a bounded interval, barring an explicit admin override.</li>
     *     <li>Updates node staking metadata (in particular, the nodes' reward rates earned for the just-ending
     *     period and their weight for the just-starting period); and exports this to the block stream.</li>
     *     <li>If appropriate, triggers rekeying a new candidate roster based on a snapshot of the node
     *     information computed in the previous step, and all dynamic address book (DAB) transactions
     *     handled up to this consensus time.</li>
     * </ol>
     * <p>
     * There is an edge case where we don't want to process the stake period changes, which is when we are at genesis;
     * at that point the system entities involved in the stake period changes will not yet exist.
     *
     * @param parentTxn the user transaction whose consensus time is being reached
     * @param includeStakePeriodSideEffects if true, includes stake period boundary side effects
     */
    public void advanceTimeTo(@NonNull final ParentTxn parentTxn, final boolean includeStakePeriodSideEffects) {
        if (includeStakePeriodSideEffects) {
            try {
                processSideEffects(
                        parentTxn.stack(),
                        parentTxn.tokenContextImpl(),
                        streamMode,
                        blockStreamManager.lastHandleTime());
            } catch (final Exception e) {
                // We don't propagate a failure here to avoid a catastrophic scenario
                // where we are "stuck" trying to process node stake updates and never
                // get back to user transactions
                logger.error("Failed to process stake period changes", e);
            }
        }
        blockStreamManager.setLastHandleTime(parentTxn.consensusNow());
        if (streamMode != BLOCKS) {
            // This updates consTimeOfLastHandledTxn as a side effect
            blockRecordManager.advanceConsensusClock(parentTxn.consensusNow(), parentTxn.state());
        }
    }

    /**
     * Orchestrates changes that happen before the first transaction in a new staking period, as follows:
     * <ol>
     *     <li>Saves the current exchange rates as the "midnight rates" that tether the rates within the
     *     following period to a bounded interval, barring an explicit admin override.</li>
     *     <li>Updates node staking metadata (in particular, the nodes' reward rates earned for the just-ending
     *     period and their weight for the just-starting period); and exports this to the block stream.</li>
     *     <li>If appropriate, triggers rekeying a new candidate roster based on a snapshot of the node
     *     information computed in the previous step, and all dynamic address book (DAB) transactions
     *     handled up to this consensus time.</li>
     * </ol>
     *
     * @param stack the savepoint stack
     * @param tokenContext the token context
     * @param streamMode the stream mode
     * @param lastHandleTimeFromBlockStream the last instant at which a transaction was handled per block stream
     */
    private void processSideEffects(
            @NonNull final SavepointStackImpl stack,
            @NonNull final TokenContext tokenContext,
            @NonNull final StreamMode streamMode,
            @NonNull final Instant lastHandleTimeFromBlockStream) {
        requireNonNull(stack);
        requireNonNull(tokenContext);
        requireNonNull(streamMode);
        requireNonNull(lastHandleTimeFromBlockStream);
        final var isStakePeriodBoundary =
                isStakingPeriodBoundary(streamMode, tokenContext, lastHandleTimeFromBlockStream);
        if (isStakePeriodBoundary) {
            try {
                exchangeRateManager.updateMidnightRates(stack);
                stack.commitFullStack();
            } catch (Exception e) {
                logger.error("CATASTROPHIC failure updating midnight rates", e);
                stack.rollbackFullStack();
            }
            try {
                final var streamBuilder =
                        endOfStakingPeriodUpdater.updateNodes(tokenContext, exchangeRateManager.exchangeRates());
                if (streamBuilder != null) {
                    stack.commitTransaction(streamBuilder);
                }
            } catch (Exception e) {
                logger.error("CATASTROPHIC failure updating end-of-day stakes", e);
                stack.rollbackFullStack();
            }
            try {
                final var rosterStore = new WritableRosterStore(stack.getWritableStates(RosterService.NAME));
                // Unless the candidate roster is for a pending upgrade, we set a new one with the latest weights
                if (rosterStore.getCandidateRosterHash() == null || rosterStore.candidateIsWeightRotation()) {
                    final var weightFunction = tokenContext
                            .readableStore(ReadableStakingInfoStore.class)
                            .weightFunction();
                    final var reweightedRoster =
                            new Roster(requireNonNull(rosterStore.getActiveRoster()).rosterEntries().stream()
                                    .map(rosterEntry -> rosterEntry
                                            .copyBuilder()
                                            .weight(weightFunction.applyAsLong(rosterEntry.nodeId()))
                                            .build())
                                    .toList());
                    if (!hasZeroWeight(reweightedRoster)) {
                        rosterStore.putCandidateRoster(reweightedRoster);
                        stack.commitFullStack();
                    }
                }
            } catch (Exception e) {
                logger.error("{} setting reweighted candidate roster", ALERT_MESSAGE, e);
                stack.rollbackFullStack();
            }
        }
    }

    private boolean isStakingPeriodBoundary(
            @NonNull final StreamMode streamMode,
            @NonNull final TokenContext tokenContext,
            @NonNull final Instant lastHandleTimeFromBlockStream) {
        final var consensusTime = tokenContext.consensusTime();
        if (streamMode == RECORDS) {
            final var blockStore = tokenContext.readableStore(ReadableBlockRecordStore.class);
            final var consTimeOfLastHandled = blockStore.getLastBlockInfo().consTimeOfLastHandledTxnOrThrow();
            if (consensusTime.getEpochSecond() > consTimeOfLastHandled.seconds()) {
                return isNextStakingPeriod(
                        consensusTime,
                        Instant.ofEpochSecond(consTimeOfLastHandled.seconds(), consTimeOfLastHandled.nanos()),
                        tokenContext);
            }
        } else {
            if (isNextSecond(lastHandleTimeFromBlockStream, consensusTime)) {
                return isNextStakingPeriod(consensusTime, lastHandleTimeFromBlockStream, tokenContext);
            }
        }
        return false;
    }

    public static boolean isNextSecond(final @NonNull Instant lastHandleTime, final Instant consensusTime) {
        return consensusTime.getEpochSecond() > lastHandleTime.getEpochSecond();
    }

    @VisibleForTesting
    public static boolean isNextStakingPeriod(
            @NonNull final Instant currentConsensusTime,
            @NonNull final Instant previousConsensusTime,
            @NonNull final TokenContext tokenContext) {
        return isNextStakingPeriod(
                currentConsensusTime,
                previousConsensusTime,
                tokenContext.configuration().getConfigData(StakingConfig.class).periodMins());
    }

    public static boolean isNextStakingPeriod(
            @NonNull final Instant currentConsensusTime,
            @NonNull final Instant previousConsensusTime,
            final long stakingPeriod) {
        if (stakingPeriod == DEFAULT_STAKING_PERIOD_MINS) {
            return isLaterUtcDay(currentConsensusTime, previousConsensusTime);
        } else {
            final var periodMs = stakingPeriod * MINUTES_TO_MILLISECONDS;
            return getPeriod(currentConsensusTime, periodMs) > getPeriod(previousConsensusTime, periodMs);
        }
    }

    private static boolean isLaterUtcDay(@NonNull final Instant now, @NonNull final Instant then) {
        final var nowDay = LocalDate.ofInstant(now, UTC);
        final var thenDay = LocalDate.ofInstant(then, UTC);
        return nowDay.isAfter(thenDay);
    }

    private static boolean hasZeroWeight(@NonNull final Roster roster) {
        return roster.rosterEntries().stream().mapToLong(RosterEntry::weight).sum() == 0L;
    }
}

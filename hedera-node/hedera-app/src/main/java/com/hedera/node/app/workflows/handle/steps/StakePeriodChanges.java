// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.swirlds.common.stream.LinkedObjectStreamUtilities.getPeriod;
import static java.time.ZoneOffset.UTC;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.records.ReadableBlockRecordStore;
import com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUpdater;
import com.hedera.node.app.service.token.records.TokenContext;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.types.StreamMode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.time.LocalDate;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Orchestrates changes that happen before the first transaction in a new staking period. See
 * {@link #process(Dispatch, SavepointStackImpl, TokenContext, StreamMode, Instant)}
 * for details.
 */
@Singleton
public class StakePeriodChanges {
    private static final Logger logger = LogManager.getLogger(StakePeriodChanges.class);

    private static final long DEFAULT_STAKING_PERIOD_MINS = 1440L;
    private static final long MINUTES_TO_MILLISECONDS = 60_000L;

    private final EndOfStakingPeriodUpdater endOfStakingPeriodUpdater;
    private final ExchangeRateManager exchangeRateManager;

    @Inject
    public StakePeriodChanges(
            @NonNull final EndOfStakingPeriodUpdater endOfStakingPeriodUpdater,
            @NonNull final ExchangeRateManager exchangeRateManager) {
        this.endOfStakingPeriodUpdater = requireNonNull(endOfStakingPeriodUpdater);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
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
     * @param dispatch the dispatch
     * @param stack the savepoint stack
     * @param tokenContext the token context
     * @param streamMode the stream mode
     * @param lastHandleTime the last instant at which a transaction was handled
     */
    public void process(
            @NonNull final Dispatch dispatch,
            @NonNull final SavepointStackImpl stack,
            @NonNull final TokenContext tokenContext,
            @NonNull final StreamMode streamMode,
            @NonNull final Instant lastHandleTime) {
        requireNonNull(stack);
        requireNonNull(dispatch);
        requireNonNull(tokenContext);
        requireNonNull(streamMode);
        requireNonNull(lastHandleTime);
        final var isStakePeriodBoundary = isStakingPeriodBoundary(streamMode, tokenContext, lastHandleTime);
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
        }
    }

    private boolean isStakingPeriodBoundary(
            @NonNull final StreamMode streamMode,
            @NonNull final TokenContext tokenContext,
            @NonNull final Instant lastHandleTime) {
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
            if (isNextSecond(lastHandleTime, consensusTime)) {
                return isNextStakingPeriod(consensusTime, lastHandleTime, tokenContext);
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
}

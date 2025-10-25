// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.service.token.api.StakingRewardsApi.epochSecondAtStartOfPeriod;
import static com.hedera.node.app.service.token.api.StakingRewardsApi.stakePeriodAt;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.ReadableEntityIdStoreImpl;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.impl.ReadableScheduleStoreImpl;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * A stateful probe that explores a {@link State} to find the earliest target consensus timestamp (TCT) that marks
 * where quiescence should end, no matter if user transactions remain dormant.
 * <p>
 * Suppose the given state has a last-handled consensus time of `T`.
 * <ol>
 *   <li>When the probe was given a non-negative staking period, the start of the next stake period after `T` is a TCT.</li>
 *   <li>Every second after `T` with a transaction scheduled to execute there is a TCT.</li>
 * </ol>
 */
public class TctProbe {
    private final int maxConsecutiveScheduleSecondsToProbe;
    private final long stakePeriodMins;
    private final State state;

    @Nullable
    private Instant nearestTct;

    @Nullable
    private Instant nextScheduledSecond;

    @Nullable
    private Instant nextStakePeriodStart;

    @Nullable
    private Instant lastHandledConsensusTime;

    public TctProbe(
            final int maxConsecutiveScheduleSecondsToProbe, final long stakePeriodMins, @NonNull final State state) {
        this.maxConsecutiveScheduleSecondsToProbe = maxConsecutiveScheduleSecondsToProbe;
        this.stakePeriodMins = stakePeriodMins;
        this.state = requireNonNull(state);
    }

    /**
     * Probes its {@link State} for the earliest target consensus timestamp (TCT) that marks where quiescence should
     * end, no matter if user transactions remain dormant.
     * @return the earliest TCT or {@code null} if there is no TCT discovered yet
     */
    public @Nullable Instant findTct() {
        if (stakePeriodMins > 0 && nextStakePeriodStart == null) {
            final long currentStakePeriod = stakePeriodAt(lastHandledConsensusTime(), stakePeriodMins);
            nextStakePeriodStart =
                    Instant.ofEpochSecond(epochSecondAtStartOfPeriod(currentStakePeriod + 1, stakePeriodMins));
        }
        if (nextScheduledSecond == null || nextScheduledSecondCouldBeNewNearestTct()) {
            if (nextScheduledSecond == null) {
                nextScheduledSecond = lastHandledConsensusTime();
            }
            final var readableScheduleStore = new ReadableScheduleStoreImpl(
                    state.getReadableStates(ScheduleService.NAME),
                    new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME)));
            for (int i = 0; i < maxConsecutiveScheduleSecondsToProbe; i++) {
                final var counts = readableScheduleStore.scheduledCountsAt(nextScheduledSecond.getEpochSecond());
                if (counts != null && counts.numberProcessed() < counts.numberScheduled()) {
                    nearestTct = nextScheduledSecond;
                    break;
                }
                nextScheduledSecond = nextScheduledSecond.plusSeconds(1);
            }
        }
        if (nearestTct == null) {
            nearestTct = nextStakePeriodStart;
        }
        return nearestTct;
    }

    /**
     * Gets the block stream info from the given state.
     * @param state the state
     * @return the block stream info
     */
    public static @NonNull BlockStreamInfo blockStreamInfoFrom(@NonNull final State state) {
        final var blockStreamInfoState = state.getReadableStates(BlockStreamService.NAME)
                .<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID);
        return requireNonNull(blockStreamInfoState.get());
    }

    private @NonNull Instant lastHandledConsensusTime() {
        if (lastHandledConsensusTime == null) {
            lastHandledConsensusTime = asInstant(blockStreamInfoFrom(state).lastHandleTimeOrElse(EPOCH));
        }
        return lastHandledConsensusTime;
    }

    private boolean nextScheduledSecondCouldBeNewNearestTct() {
        if (nearestTct == null) {
            return true;
        }
        return nearestTct.equals(nextStakePeriodStart);
    }
}

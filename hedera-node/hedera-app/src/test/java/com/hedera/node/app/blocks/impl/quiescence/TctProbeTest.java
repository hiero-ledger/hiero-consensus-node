// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.quiescence;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.service.token.api.StakingRewardsApi.epochSecondAtStartOfPeriod;
import static com.hedera.node.app.service.token.api.StakingRewardsApi.stakePeriodAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.node.state.schedule.ScheduledCounts;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TctProbeTest {

    private static final Instant LAST_HANDLED_TIME = Instant.ofEpochSecond(1_234_567L);
    private static final long STAKE_PERIOD_MINS = 1440L; // 24 hours
    private static final int MAX_CONSECUTIVE_SCHEDULE_SECONDS = 10;

    @Mock
    private State state;

    @Mock
    private ReadableStates blockStreamReadableStates;

    @Mock
    private ReadableStates scheduleReadableStates;

    @Mock
    private ReadableStates entityIdReadableStates;

    @Mock
    private ReadableSingletonState<BlockStreamInfo> blockStreamInfoState;

    @Mock
    private ReadableKVState<TimestampSeconds, ScheduledCounts> scheduledCountsState;

    private TctProbe subject;

    @BeforeEach
    void setUp() {
        // Setup default state mocking
        lenient().when(state.getReadableStates(BlockStreamService.NAME)).thenReturn(blockStreamReadableStates);
        lenient().when(state.getReadableStates(ScheduleService.NAME)).thenReturn(scheduleReadableStates);
        lenient().when(state.getReadableStates(EntityIdService.NAME)).thenReturn(entityIdReadableStates);
        lenient()
                .when(blockStreamReadableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID))
                .thenReturn(blockStreamInfoState);
        lenient()
                .when(scheduleReadableStates.<TimestampSeconds, ScheduledCounts>get(anyInt()))
                .thenReturn(scheduledCountsState);
    }

    @Test
    void blockStreamInfoFromReturnsBlockStreamInfo() {
        // Given
        final var expectedInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(expectedInfo);

        // When
        final var result = TctProbe.blockStreamInfoFrom(state);

        // Then
        assertNotNull(result);
        assertEquals(expectedInfo, result);
    }

    @Test
    void blockStreamInfoFromThrowsWhenBlockStreamInfoIsNull() {
        // Given
        given(blockStreamInfoState.get()).willReturn(null);

        // When/Then
        assertThrows(NullPointerException.class, () -> TctProbe.blockStreamInfoFrom(state));
    }

    @Test
    void findTctReturnsNullWhenNoStakePeriodAndNoScheduledTransactions() {
        // Given - no staking period (stakePeriodMins = 0)
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, 0, state);

        // When
        final var result = subject.findTct();

        // Then
        assertNull(result);
    }

    @Test
    void findTctReturnsNextStakePeriodStartWhenNoScheduledTransactions() {
        // Given
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(scheduledCountsState.get(any())).willReturn(null);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When
        final var result = subject.findTct();

        // Then
        assertNotNull(result);
        final long currentStakePeriod = stakePeriodAt(LAST_HANDLED_TIME, STAKE_PERIOD_MINS);
        final var expectedNextStakePeriodStart =
                Instant.ofEpochSecond(epochSecondAtStartOfPeriod(currentStakePeriod + 1, STAKE_PERIOD_MINS));
        assertEquals(expectedNextStakePeriodStart, result);
    }

    @Test
    void findTctReturnsScheduledSecondWhenScheduledTransactionExists() {
        // Given
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);

        // First second has scheduled transactions with unprocessed items
        final var scheduledCounts = ScheduledCounts.newBuilder()
                .numberScheduled(5)
                .numberProcessed(2)
                .build();
        given(scheduledCountsState.get(new TimestampSeconds(LAST_HANDLED_TIME.getEpochSecond())))
                .willReturn(scheduledCounts);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When
        final var result = subject.findTct();

        // Then
        assertNotNull(result);
        assertEquals(LAST_HANDLED_TIME, result);
    }

    @Test
    void findTctReturnsScheduledSecondAfterSeveralSecondsWithNoScheduledTransactions() {
        // Given
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);

        // First 3 seconds have no scheduled transactions
        given(scheduledCountsState.get(new TimestampSeconds(LAST_HANDLED_TIME.getEpochSecond())))
                .willReturn(null);
        given(scheduledCountsState.get(
                        new TimestampSeconds(LAST_HANDLED_TIME.plusSeconds(1).getEpochSecond())))
                .willReturn(null);
        given(scheduledCountsState.get(
                        new TimestampSeconds(LAST_HANDLED_TIME.plusSeconds(2).getEpochSecond())))
                .willReturn(null);

        // Fourth second has scheduled transactions with unprocessed items
        final var scheduledCounts = ScheduledCounts.newBuilder()
                .numberScheduled(3)
                .numberProcessed(0)
                .build();
        given(scheduledCountsState.get(
                        new TimestampSeconds(LAST_HANDLED_TIME.plusSeconds(3).getEpochSecond())))
                .willReturn(scheduledCounts);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When
        final var result = subject.findTct();

        // Then
        assertNotNull(result);
        assertEquals(LAST_HANDLED_TIME.plusSeconds(3), result);
    }

    @Test
    void findTctReturnsScheduledSecondWhenAllScheduledTransactionsAreNotProcessed() {
        // Given
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);

        // Scheduled transactions exist but all are processed
        final var allProcessedCounts = ScheduledCounts.newBuilder()
                .numberScheduled(5)
                .numberProcessed(5)
                .build();
        given(scheduledCountsState.get(new TimestampSeconds(LAST_HANDLED_TIME.getEpochSecond())))
                .willReturn(allProcessedCounts);

        // Next second has unprocessed scheduled transactions
        final var unprocessedCounts = ScheduledCounts.newBuilder()
                .numberScheduled(3)
                .numberProcessed(1)
                .build();
        given(scheduledCountsState.get(
                        new TimestampSeconds(LAST_HANDLED_TIME.plusSeconds(1).getEpochSecond())))
                .willReturn(unprocessedCounts);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When
        final var result = subject.findTct();

        // Then
        assertNotNull(result);
        assertEquals(LAST_HANDLED_TIME.plusSeconds(1), result);
    }

    @Test
    void probeStopsAfterMaxConsecutiveScheduleSecondsToFindTct() {
        // Given
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);

        // All seconds within the probe limit have fully processed scheduled transactions
        final var allProcessedCounts = ScheduledCounts.newBuilder()
                .numberScheduled(5)
                .numberProcessed(5)
                .build();
        for (int i = 0; i < MAX_CONSECUTIVE_SCHEDULE_SECONDS; i++) {
            given(scheduledCountsState.get(new TimestampSeconds(
                            LAST_HANDLED_TIME.plusSeconds(i).getEpochSecond())))
                    .willReturn(allProcessedCounts);
        }

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When
        final var result = subject.findTct();

        // Then - should return next stake period start since no unprocessed scheduled transactions found
        assertNotNull(result);
        final long currentStakePeriod = stakePeriodAt(LAST_HANDLED_TIME, STAKE_PERIOD_MINS);
        final var expectedNextStakePeriodStart =
                Instant.ofEpochSecond(epochSecondAtStartOfPeriod(currentStakePeriod + 1, STAKE_PERIOD_MINS));
        assertEquals(expectedNextStakePeriodStart, result);
    }

    @Test
    void findTctReturnsEarlierOfScheduledSecondAndStakePeriodStart() {
        // Given - stake period start is sooner than any scheduled transaction
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);

        // No scheduled transactions found within probe limit
        given(scheduledCountsState.get(any())).willReturn(null);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When
        final var result = subject.findTct();

        // Then - should return next stake period start
        assertNotNull(result);
        final long currentStakePeriod = stakePeriodAt(LAST_HANDLED_TIME, STAKE_PERIOD_MINS);
        final var expectedNextStakePeriodStart =
                Instant.ofEpochSecond(epochSecondAtStartOfPeriod(currentStakePeriod + 1, STAKE_PERIOD_MINS));
        assertEquals(expectedNextStakePeriodStart, result);
    }

    @Test
    void findTctUsesEpochWhenLastHandleTimeIsNull() {
        // Given - BlockStreamInfo with null lastHandleTime
        final var blockStreamInfo =
                BlockStreamInfo.newBuilder().lastHandleTime((Timestamp) null).build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(scheduledCountsState.get(any())).willReturn(null);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When
        final var result = subject.findTct();

        // Then - should use EPOCH as the last handled time
        assertNotNull(result);
        final var epochInstant = Instant.ofEpochSecond(EPOCH.seconds(), EPOCH.nanos());
        final long currentStakePeriod = stakePeriodAt(epochInstant, STAKE_PERIOD_MINS);
        final var expectedNextStakePeriodStart =
                Instant.ofEpochSecond(epochSecondAtStartOfPeriod(currentStakePeriod + 1, STAKE_PERIOD_MINS));
        assertEquals(expectedNextStakePeriodStart, result);
    }

    @Test
    void multipleFindTctCallsReturnSameResultWhenNearestTctIsFound() {
        // Given
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);

        // Scheduled transaction exists at the first second
        final var scheduledCounts = ScheduledCounts.newBuilder()
                .numberScheduled(5)
                .numberProcessed(2)
                .build();
        given(scheduledCountsState.get(new TimestampSeconds(LAST_HANDLED_TIME.getEpochSecond())))
                .willReturn(scheduledCounts);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When - probe multiple times
        final var result1 = subject.findTct();
        final var result2 = subject.findTct();
        final var result3 = subject.findTct();

        // Then - all results should be the same
        assertNotNull(result1);
        assertEquals(LAST_HANDLED_TIME, result1);
        assertEquals(result1, result2);
        assertEquals(result1, result3);
    }

    @Test
    void multipleFindTctCallsContinueSearchingWhenNearestTctIsStakePeriod() {
        // Given
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);

        // First probe: no scheduled transactions found
        given(scheduledCountsState.get(any())).willReturn(null);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When - first probe
        final var result1 = subject.findTct();

        // Then - should return next stake period start
        assertNotNull(result1);
        final long currentStakePeriod = stakePeriodAt(LAST_HANDLED_TIME, STAKE_PERIOD_MINS);
        final var expectedNextStakePeriodStart =
                Instant.ofEpochSecond(epochSecondAtStartOfPeriod(currentStakePeriod + 1, STAKE_PERIOD_MINS));
        assertEquals(expectedNextStakePeriodStart, result1);

        // Given - now add a scheduled transaction that could be a new nearest TCT
        final var scheduledCounts = ScheduledCounts.newBuilder()
                .numberScheduled(3)
                .numberProcessed(1)
                .build();
        // The probe should continue from where it left off (nextScheduledSecond was incremented)
        given(scheduledCountsState.get(new TimestampSeconds(LAST_HANDLED_TIME
                        .plusSeconds(MAX_CONSECUTIVE_SCHEDULE_SECONDS)
                        .getEpochSecond())))
                .willReturn(scheduledCounts);

        // When - second probe
        final var result2 = subject.findTct();

        // Then - should find the scheduled transaction
        assertNotNull(result2);
        assertEquals(LAST_HANDLED_TIME.plusSeconds(MAX_CONSECUTIVE_SCHEDULE_SECONDS), result2);
    }

    @Test
    void findTctWithZeroStakePeriodOnlySearchesForScheduledTransactions() {
        // Given - zero stake period means no stake period TCT
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);

        // Scheduled transaction exists
        final var scheduledCounts = ScheduledCounts.newBuilder()
                .numberScheduled(2)
                .numberProcessed(0)
                .build();
        given(scheduledCountsState.get(new TimestampSeconds(LAST_HANDLED_TIME.getEpochSecond())))
                .willReturn(scheduledCounts);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, 0, state);

        // When
        final var result = subject.findTct();

        // Then - should find the scheduled transaction
        assertNotNull(result);
        assertEquals(LAST_HANDLED_TIME, result);
    }

    @Test
    void findTctWithNegativeStakePeriodOnlySearchesForScheduledTransactions() {
        // Given - negative stake period means no stake period TCT
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);

        // No scheduled transactions
        given(scheduledCountsState.get(any())).willReturn(null);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, -100, state);

        // When
        final var result = subject.findTct();

        // Then - should return null since no stake period and no scheduled transactions
        assertNull(result);
    }

    @Test
    void findTctWithZeroMaxConsecutiveScheduleSecondsStillChecksFirstSecond() {
        // Given - max consecutive schedule seconds is 0, but it should still check at least once
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);

        subject = new TctProbe(0, STAKE_PERIOD_MINS, state);

        // When
        final var result = subject.findTct();

        // Then - should return next stake period start
        assertNotNull(result);
        final long currentStakePeriod = stakePeriodAt(LAST_HANDLED_TIME, STAKE_PERIOD_MINS);
        final var expectedNextStakePeriodStart =
                Instant.ofEpochSecond(epochSecondAtStartOfPeriod(currentStakePeriod + 1, STAKE_PERIOD_MINS));
        assertEquals(expectedNextStakePeriodStart, result);
    }
}

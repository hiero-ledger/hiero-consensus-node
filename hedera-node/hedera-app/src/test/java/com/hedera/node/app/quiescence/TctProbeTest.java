// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.service.token.api.StakingRewardsApi.epochSecondAtStartOfPeriod;
import static com.hedera.node.app.service.token.api.StakingRewardsApi.stakePeriodAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.hedera.node.app.service.networkadmin.impl.FreezeServiceImpl;
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
    private ReadableStates freezeReadableStates;

    @Mock
    private ReadableSingletonState<BlockStreamInfo> blockStreamInfoState;

    @Mock
    private ReadableKVState<TimestampSeconds, ScheduledCounts> scheduledCountsState;

    @Mock
    private ReadableSingletonState<Timestamp> freezeTimeState;

    private TctProbe subject;

    @BeforeEach
    void setUp() {
        // Setup default state mocking
        lenient().when(state.getReadableStates(BlockStreamService.NAME)).thenReturn(blockStreamReadableStates);
        lenient().when(state.getReadableStates(ScheduleService.NAME)).thenReturn(scheduleReadableStates);
        lenient().when(state.getReadableStates(EntityIdService.NAME)).thenReturn(entityIdReadableStates);
        lenient().when(state.getReadableStates(FreezeServiceImpl.NAME)).thenReturn(freezeReadableStates);
        lenient()
                .when(blockStreamReadableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID))
                .thenReturn(blockStreamInfoState);
        lenient()
                .when(scheduleReadableStates.<TimestampSeconds, ScheduledCounts>get(anyInt()))
                .thenReturn(scheduledCountsState);
        lenient().when(freezeReadableStates.<Timestamp>getSingleton(anyInt())).thenReturn(freezeTimeState);
    }

    @Test
    void blockStreamInfoFromReturnsBlockStreamInfo() {
        // Given
        final var expectedInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(expectedInfo);

        // When
        final var result = TctProbe.blockStreamInfoFrom(state, false);

        // Then
        assertNotNull(result);
        assertEquals(expectedInfo, result);
    }

    @Test
    void blockStreamInfoFromThrowsWhenBlockStreamInfoIsNull() {
        // Given
        given(blockStreamInfoState.get()).willReturn(null);

        // When/Then
        assertThrows(NullPointerException.class, () -> TctProbe.blockStreamInfoFrom(state, false));
    }

    @Test
    void findTctReturnsNullWhenNoStakePeriodAndNoScheduledTransactions() {
        // Given - no staking period (stakePeriodMins = 0) and no freeze time
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(null);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, 0, state);

        // When
        final var result = subject.findTct();

        // Then - should return EPOCH (freeze time defaults to EPOCH when null)
        assertNotNull(result);
        assertEquals(Instant.EPOCH, result);
    }

    @Test
    void findTctReturnsNextStakePeriodStartWhenNoScheduledTransactions() {
        // Given - freeze time is far in the future so stake period wins
        final var farFutureFreezeTime = LAST_HANDLED_TIME.plusSeconds(999999999);
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(asTimestamp(farFutureFreezeTime));
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
        given(freezeTimeState.get()).willReturn(null);

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
        given(freezeTimeState.get()).willReturn(null);

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
        given(freezeTimeState.get()).willReturn(null);

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
        // Given - freeze time is far in the future so stake period wins
        final var farFutureFreezeTime = LAST_HANDLED_TIME.plusSeconds(999999999);
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(asTimestamp(farFutureFreezeTime));

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
        // Given - stake period start is sooner than any scheduled transaction, freeze time is far future
        final var farFutureFreezeTime = LAST_HANDLED_TIME.plusSeconds(999999999);
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(asTimestamp(farFutureFreezeTime));

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
        // Given - BlockStreamInfo with null lastHandleTime, freeze time far in future
        final var farFutureFreezeTime = Instant.ofEpochSecond(999999999);
        final var blockStreamInfo =
                BlockStreamInfo.newBuilder().lastHandleTime((Timestamp) null).build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(asTimestamp(farFutureFreezeTime));
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
        given(freezeTimeState.get()).willReturn(null);

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
        // Given - freeze time is far in the future so stake period wins initially
        final var farFutureFreezeTime = LAST_HANDLED_TIME.plusSeconds(999999999);
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(asTimestamp(farFutureFreezeTime));

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
        given(freezeTimeState.get()).willReturn(null);

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
        given(freezeTimeState.get()).willReturn(null);

        // No scheduled transactions
        given(scheduledCountsState.get(any())).willReturn(null);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, -100, state);

        // When
        final var result = subject.findTct();

        // Then - should return EPOCH (freeze time defaults to EPOCH when null)
        assertNotNull(result);
        assertEquals(Instant.EPOCH, result);
    }

    @Test
    void findTctWithZeroMaxConsecutiveScheduleSecondsStillChecksFirstSecond() {
        // Given - max consecutive schedule seconds is 0, freeze time is far in future
        final var farFutureFreezeTime = LAST_HANDLED_TIME.plusSeconds(999999999);
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(asTimestamp(farFutureFreezeTime));

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

    @Test
    void findTctReturnsFreezeTimeWhenItIsEarliest() {
        // Given - freeze time is set and is earlier than stake period
        final var freezeTime = LAST_HANDLED_TIME.plusSeconds(100);
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(asTimestamp(freezeTime));
        given(scheduledCountsState.get(any())).willReturn(null);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When
        final var result = subject.findTct();

        // Then - should return freeze time
        assertNotNull(result);
        assertEquals(freezeTime, result);
    }

    @Test
    void findTctReturnsStakePeriodWhenFreezeTimeIsLater() {
        // Given - freeze time is set but is later than stake period
        final var freezeTime = LAST_HANDLED_TIME.plusSeconds(999999999);
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(asTimestamp(freezeTime));
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
    void findTctUsesEpochWhenFreezeTimeIsNull() {
        // Given - no freeze time is set (null), so it defaults to EPOCH
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(null);
        given(scheduledCountsState.get(any())).willReturn(null);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When
        final var result = subject.findTct();

        // Then - should return EPOCH (freeze time defaults to EPOCH when null, which is earlier than stake period)
        assertNotNull(result);
        assertEquals(Instant.EPOCH, result);
    }

    @Test
    void findTctReturnsScheduledSecondWhenEarlierThanFreezeTime() {
        // Given - scheduled transaction exists and is earlier than freeze time
        final var freezeTime = LAST_HANDLED_TIME.plusSeconds(1000);
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(asTimestamp(freezeTime));

        // Scheduled transaction exists at the first second
        final var scheduledCounts = ScheduledCounts.newBuilder()
                .numberScheduled(5)
                .numberProcessed(2)
                .build();
        given(scheduledCountsState.get(new TimestampSeconds(LAST_HANDLED_TIME.getEpochSecond())))
                .willReturn(scheduledCounts);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When
        final var result = subject.findTct();

        // Then - should return scheduled second, not freeze time
        assertNotNull(result);
        assertEquals(LAST_HANDLED_TIME, result);
    }

    @Test
    void findTctReturnsFreezeTimeWhenEarlierThanScheduledSecond() {
        // Given - freeze time is earlier than any scheduled transaction
        final var freezeTime = LAST_HANDLED_TIME.plusSeconds(2);
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(asTimestamp(freezeTime));

        // No scheduled transactions in first 2 seconds (up to freeze time)
        given(scheduledCountsState.get(new TimestampSeconds(LAST_HANDLED_TIME.getEpochSecond())))
                .willReturn(null);
        given(scheduledCountsState.get(
                        new TimestampSeconds(LAST_HANDLED_TIME.plusSeconds(1).getEpochSecond())))
                .willReturn(null);
        given(scheduledCountsState.get(
                        new TimestampSeconds(LAST_HANDLED_TIME.plusSeconds(2).getEpochSecond())))
                .willReturn(null);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When
        final var result = subject.findTct();

        // Then - should return freeze time
        assertNotNull(result);
        assertEquals(freezeTime, result);
    }

    @Test
    void findTctWithFreezeTimeAndNoStakePeriod() {
        // Given - freeze time is set but no stake period (stakePeriodMins = 0)
        // When stakePeriodMins = 0, nextStakePeriodStart is set to EPOCH
        final var freezeTime = LAST_HANDLED_TIME.plusSeconds(500);
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(asTimestamp(freezeTime));
        // No scheduled transactions
        for (int i = 0; i < MAX_CONSECUTIVE_SCHEDULE_SECONDS; i++) {
            given(scheduledCountsState.get(new TimestampSeconds(
                            LAST_HANDLED_TIME.plusSeconds(i).getEpochSecond())))
                    .willReturn(null);
        }

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, 0, state);

        // When
        final var result = subject.findTct();

        // Then - should return EPOCH (min of EPOCH for stake period and freezeTime)
        // When stakePeriodMins = 0, nextStakePeriodStart is EPOCH, which is earlier than freezeTime
        assertNotNull(result);
        assertEquals(Instant.EPOCH, result);
    }

    @Test
    void multipleFindTctCallsContinueSearchingWhenNearestTctIsFreezeTime() {
        // Given - freeze time is the nearest TCT initially
        final var freezeTime = LAST_HANDLED_TIME.plusSeconds(100);
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(asTimestamp(freezeTime));

        // First probe: no scheduled transactions found
        given(scheduledCountsState.get(any())).willReturn(null);

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When - first probe
        final var result1 = subject.findTct();

        // Then - should return freeze time
        assertNotNull(result1);
        assertEquals(freezeTime, result1);

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
    void findTctReturnsFreezeTimeWhenAllThreeTctsExist() {
        // Given - all three TCT sources exist: freeze time (earliest), scheduled transaction, and stake period
        final var freezeTime = LAST_HANDLED_TIME.plusSeconds(50);
        final var blockStreamInfo = BlockStreamInfo.newBuilder()
                .lastHandleTime(asTimestamp(LAST_HANDLED_TIME))
                .build();
        given(blockStreamInfoState.get()).willReturn(blockStreamInfo);
        given(freezeTimeState.get()).willReturn(asTimestamp(freezeTime));

        // No scheduled transactions in the first MAX_CONSECUTIVE_SCHEDULE_SECONDS
        for (int i = 0; i < MAX_CONSECUTIVE_SCHEDULE_SECONDS; i++) {
            given(scheduledCountsState.get(new TimestampSeconds(
                            LAST_HANDLED_TIME.plusSeconds(i).getEpochSecond())))
                    .willReturn(null);
        }

        subject = new TctProbe(MAX_CONSECUTIVE_SCHEDULE_SECONDS, STAKE_PERIOD_MINS, state);

        // When
        final var result = subject.findTct();

        // Then - should return freeze time as it's the earliest
        assertNotNull(result);
        assertEquals(freezeTime, result);
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager.DEFAULT_STAKING_PERIOD_MINS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.records.ReadableBlockRecordStore;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUpdater;
import com.hedera.node.app.workflows.handle.record.TokenContextImpl;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class StakePeriodChangesTest {

    private static final Instant CONSENSUS_TIME_1234567 = Instant.ofEpochSecond(1_234_5670L, 1357);

    @Mock
    private EndOfStakingPeriodUpdater stakingPeriodCalculator;

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private TokenContextImpl context;

    @Mock
    private ReadableBlockRecordStore blockStore;

    @Mock
    private ParentTxn parentTxn;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private SavepointStackImpl stack;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private WritableStates writableStates;

    @Mock
    private WritableKVState<EntityNumber, Node> nodesState;

    @Mock
    private WritableSingletonState<EntityCounts> entityCountsState;

    @Mock
    private WritableSingletonState<EntityNumber> entityIdState;

    @Mock
    private BlockRecordManager blockRecordManager;

    @Mock
    private BlockStreamManager blockStreamManager;

    @Mock
    private ConfigProvider configProvider;

    private StakePeriodChanges subject;

    @Test
    void processUpdateSkippedForPreviousPeriod() {
        verifyNoInteractions(stakingPeriodCalculator);
        verifyNoInteractions(exchangeRateManager);
    }

    @Test
    @SuppressWarnings("unchecked")
    void processUpdateCalledForGenesisTxn() {
        setupSubjectWith(newConfig(RECORDS));

        given(exchangeRateManager.exchangeRates()).willReturn(ExchangeRateSet.DEFAULT);
        given(stack.getWritableStates(AddressBookService.NAME)).willReturn(writableStates);
        given(writableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(nodesState);
        given(blockStore.getLastBlockInfo())
                .willReturn(BlockInfo.newBuilder()
                        .consTimeOfLastHandledTxn(Timestamp.newBuilder().seconds(1_234_567L))
                        .build());
        given(context.consensusTime()).willReturn(CONSENSUS_TIME_1234567);

        given(parentTxn.stack()).willReturn(stack);
        given(parentTxn.tokenContextImpl()).willReturn(context);
        given(blockRecordManager.consTimeOfLastHandledTxn()).willReturn(Instant.EPOCH);

        subject.advanceTimeTo(parentTxn, true);

        verify(stakingPeriodCalculator).updateNodes(eq(context), eq(ExchangeRateSet.DEFAULT));
        verify(exchangeRateManager).updateMidnightRates(stack);
    }

    @Test
    void processUpdateSkippedForPreviousConsensusTime() {
        setupSubjectWith(newConfig(RECORDS));

        final var beforeLastConsensusTime = CONSENSUS_TIME_1234567.minusSeconds(1);
        given(context.consensusTime()).willReturn(beforeLastConsensusTime);
        given(blockStore.getLastBlockInfo())
                .willReturn(BlockInfo.newBuilder()
                        .consTimeOfLastHandledTxn(Timestamp.newBuilder()
                                .seconds(CONSENSUS_TIME_1234567.getEpochSecond())
                                .nanos(CONSENSUS_TIME_1234567.getNano()))
                        .build());
        given(parentTxn.stack()).willReturn(stack);
        given(parentTxn.tokenContextImpl()).willReturn(context);
        given(blockRecordManager.consTimeOfLastHandledTxn()).willReturn(Instant.EPOCH);

        subject.advanceTimeTo(parentTxn, true);

        verifyNoInteractions(stakingPeriodCalculator);
        verifyNoInteractions(exchangeRateManager);
    }

    @Test
    @SuppressWarnings("unchecked")
    void processUpdateCalledForNextPeriodWithRecordsStreamMode() {
        setupSubjectWith(newConfig(RECORDS));

        // Use any number of seconds that gets isNextPeriod(...) to return true
        final var currentConsensusTime = CONSENSUS_TIME_1234567.plusSeconds(500_000);
        given(blockStore.getLastBlockInfo())
                .willReturn(BlockInfo.newBuilder()
                        .consTimeOfLastHandledTxn(Timestamp.newBuilder()
                                .seconds(CONSENSUS_TIME_1234567.getEpochSecond())
                                .nanos(CONSENSUS_TIME_1234567.getNano()))
                        .build());
        given(context.consensusTime()).willReturn(currentConsensusTime);
        given(stack.getWritableStates(AddressBookService.NAME)).willReturn(writableStates);
        given(writableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(nodesState);

        // Pre-condition check
        Assertions.assertThat(
                        StakePeriodChanges.isNextStakingPeriod(currentConsensusTime, CONSENSUS_TIME_1234567, context))
                .isTrue();
        given(exchangeRateManager.exchangeRates()).willReturn(ExchangeRateSet.DEFAULT);

        given(parentTxn.stack()).willReturn(stack);
        given(parentTxn.tokenContextImpl()).willReturn(context);
        given(blockRecordManager.consTimeOfLastHandledTxn()).willReturn(Instant.EPOCH);
        subject.advanceTimeTo(parentTxn, true);

        verify(stakingPeriodCalculator)
                .updateNodes(
                        argThat(stakingContext -> currentConsensusTime.equals(stakingContext.consensusTime())),
                        eq(ExchangeRateSet.DEFAULT));
        verify(exchangeRateManager).updateMidnightRates(stack);
    }

    @Test
    @SuppressWarnings("unchecked")
    void processUpdateCalledForNextPeriodWithBlocksStreamMode() {
        setupSubjectWith(DEFAULT_CONFIG);
        // Use any number of seconds that gets isNextPeriod(...) to return true
        final var currentConsensusTime = CONSENSUS_TIME_1234567.plusSeconds(500_000);
        given(context.consensusTime()).willReturn(currentConsensusTime);

        // Pre-condition check
        Assertions.assertThat(
                        StakePeriodChanges.isNextStakingPeriod(currentConsensusTime, CONSENSUS_TIME_1234567, context))
                .isTrue();
        given(exchangeRateManager.exchangeRates()).willReturn(ExchangeRateSet.DEFAULT);
        given(stack.getWritableStates(AddressBookService.NAME)).willReturn(writableStates);
        given(writableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(nodesState);
        given(parentTxn.stack()).willReturn(stack);
        given(parentTxn.tokenContextImpl()).willReturn(context);
        given(blockStreamManager.lastTopLevelConsensusTime()).willReturn(Instant.EPOCH);

        subject.advanceTimeTo(parentTxn, true);

        verify(stakingPeriodCalculator)
                .updateNodes(
                        argThat(stakingContext -> currentConsensusTime.equals(stakingContext.consensusTime())),
                        eq(ExchangeRateSet.DEFAULT));
        verify(exchangeRateManager).updateMidnightRates(stack);
    }

    @Test
    @SuppressWarnings("unchecked")
    void processUpdateExceptionIsCaught() {
        setupSubjectWith(newConfig(RECORDS));
        given(exchangeRateManager.exchangeRates()).willReturn(ExchangeRateSet.DEFAULT);
        doThrow(new RuntimeException("test exception"))
                .when(stakingPeriodCalculator)
                .updateNodes(any(), eq(ExchangeRateSet.DEFAULT));
        given(blockStore.getLastBlockInfo())
                .willReturn(BlockInfo.newBuilder()
                        .consTimeOfLastHandledTxn(new Timestamp(CONSENSUS_TIME_1234567.getEpochSecond(), 0))
                        .build());
        given(context.consensusTime()).willReturn(CONSENSUS_TIME_1234567.plus(Duration.ofDays(2)));
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        given(stack.getWritableStates(AddressBookService.NAME)).willReturn(writableStates);
        given(writableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(nodesState);
        given(parentTxn.stack()).willReturn(stack);
        given(parentTxn.tokenContextImpl()).willReturn(context);
        given(blockRecordManager.consTimeOfLastHandledTxn()).willReturn(Instant.EPOCH);

        Assertions.assertThatNoException().isThrownBy(() -> subject.advanceTimeTo(parentTxn, true));
        verify(stakingPeriodCalculator).updateNodes(eq(context), eq(ExchangeRateSet.DEFAULT));
        verify(exchangeRateManager).updateMidnightRates(stack);
    }

    @Test
    void isNextStakingPeriodNowConsensusTimeBeforeThenConsensusTimeUtcDay() {
        given(context.configuration()).willReturn(newPeriodMinsConfig());

        final var earlierNowConsensus =
                CONSENSUS_TIME_1234567.minusSeconds(Duration.ofDays(1).toSeconds());
        final var result = StakePeriodChanges.isNextStakingPeriod(earlierNowConsensus, CONSENSUS_TIME_1234567, context);

        Assertions.assertThat(result).isFalse();
    }

    @Test
    void isNextStakingPeriodNowConsensusTimeInSameThenConsensusTimeUtcDay() {
        given(context.configuration()).willReturn(newPeriodMinsConfig());

        final var result =
                StakePeriodChanges.isNextStakingPeriod(CONSENSUS_TIME_1234567, CONSENSUS_TIME_1234567, context);

        Assertions.assertThat(result).isFalse();
    }

    @Test
    void isNextStakingPeriodNowConsensusTimeAfterThenConsensusTimeUtcDay() {
        given(context.configuration()).willReturn(newPeriodMinsConfig());

        final var laterNowConsensus =
                CONSENSUS_TIME_1234567.plusSeconds(Duration.ofDays(1).toSeconds());
        final var result = StakePeriodChanges.isNextStakingPeriod(laterNowConsensus, CONSENSUS_TIME_1234567, context);

        Assertions.assertThat(result).isTrue();
    }

    @Test
    void isNextStakingPeriodNowCustomStakingPeriodIsEarlier() {
        final var periodMins = 990;
        given(context.configuration()).willReturn(newPeriodMinsConfig(periodMins));

        final var earlierStakingPeriodTime = CONSENSUS_TIME_1234567.minusSeconds(
                // 1000 min * 60 seconds/min
                1000 * 60);
        final var result =
                StakePeriodChanges.isNextStakingPeriod(earlierStakingPeriodTime, CONSENSUS_TIME_1234567, context);
        Assertions.assertThat(result).isFalse();
    }

    @Test
    void isNextStakingPeriodNowCustomStakingPeriodIsLater() {
        final var periodMins = 990;
        given(context.configuration()).willReturn(newPeriodMinsConfig(periodMins));

        final var laterStakingPeriodTime = CONSENSUS_TIME_1234567.plusSeconds(
                // 1000 min * 60 seconds/min
                1000 * 60);
        final var result =
                StakePeriodChanges.isNextStakingPeriod(laterStakingPeriodTime, CONSENSUS_TIME_1234567, context);
        Assertions.assertThat(result).isTrue();
    }

    private void setupSubjectWith(@NonNull final Configuration config) {
        given(context.readableStore(ReadableBlockRecordStore.class)).willReturn(blockStore);
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        given(context.configuration()).willReturn(config);

        subject = new StakePeriodChanges(
                configProvider, stakingPeriodCalculator, exchangeRateManager, blockRecordManager, blockStreamManager);

        given(stack.getWritableStates(EntityIdService.NAME)).willReturn(writableStates);
        given(writableStates.<EntityCounts>getSingleton(ENTITY_COUNTS_STATE_ID)).willReturn(entityCountsState);
        given(writableStates.<EntityNumber>getSingleton(ENTITY_ID_STATE_ID)).willReturn(entityIdState);
    }

    private Configuration newPeriodMinsConfig() {
        return newPeriodMinsConfig(DEFAULT_STAKING_PERIOD_MINS);
    }

    private Configuration newPeriodMinsConfig(final long periodMins) {
        return newConfig(periodMins, false);
    }

    private Configuration newConfig(final long periodMins, final boolean keyCandidateRoster) {
        return HederaTestConfigBuilder.create()
                .withConfigDataType(StakingConfig.class)
                .withValue("staking.periodMins", periodMins)
                .withValue("tss.keyCandidateRoster", keyCandidateRoster)
                .getOrCreateConfig();
    }

    private Configuration newConfig(@NonNull final StreamMode streamMode) {
        return HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(StakingConfig.class)
                .withValue("blockStream.streamMode", streamMode)
                .getOrCreateConfig();
    }
}

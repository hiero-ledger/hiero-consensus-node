// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.BlockStreamJumpstartConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0750BlockRecordSchemaTest {
    @Mock
    private MigrationContext ctx;

    @Mock
    private Configuration configuration;

    @Mock
    private BlockRecordStreamConfig blockRecordStreamConfig;

    @Mock
    private BlockStreamConfig blockStreamConfig;

    @Mock
    private BlockStreamJumpstartConfig blockStreamJumpstartConfig;

    @Mock
    private VersionConfig versionConfig;

    @Mock
    private HederaConfig hederaConfig;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<BlockInfo> blockInfoState;

    private final V0750BlockRecordSchema subject = new V0750BlockRecordSchema();

    @Test
    void versionIsV0750() {
        assertEquals(new SemanticVersion(0, 75, 0, "", ""), subject.getVersion());
    }

    @Test
    void restartIsNoopOnGenesis() {
        given(ctx.isGenesis()).willReturn(true);

        subject.restart(ctx);

        verify(ctx, never()).appConfig();
        verifyNoInteractions(configuration, blockRecordStreamConfig, writableStates, blockInfoState);
    }

    @Test
    void restartIsNoopWhenLiveWriteAndCutoverDisabled() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(VersionConfig.class)).willReturn(versionConfig);
        given(configuration.getConfigData(HederaConfig.class)).willReturn(hederaConfig);
        given(versionConfig.servicesVersion()).willReturn(new SemanticVersion(0, 74, 0, "", ""));
        given(hederaConfig.configVersion()).willReturn(0);
        given(ctx.isUpgrade(any())).willReturn(true);
        given(configuration.getConfigData(BlockRecordStreamConfig.class)).willReturn(blockRecordStreamConfig);
        given(blockRecordStreamConfig.liveWritePrevWrappedRecordHashes()).willReturn(false);
        given(configuration.getConfigData(BlockStreamConfig.class)).willReturn(blockStreamConfig);
        given(blockStreamConfig.enableCutover()).willReturn(false);

        subject.restart(ctx);

        verify(blockInfoState, never()).put(any());
    }

    @Test
    void restartSkipsVotingBlockWhenBlockInfoSingletonIsNull() {
        givenRestartPreconditions();
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get()).willReturn(null);

        subject.restart(ctx);

        verify(blockInfoState, never()).put(any());
    }

    @Test
    void restartIsNoopWhenNotUpgrade() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configuration);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(configuration.getConfigData(VersionConfig.class)).willReturn(versionConfig);
        given(configuration.getConfigData(HederaConfig.class)).willReturn(hederaConfig);
        given(configuration.getConfigData(BlockStreamConfig.class)).willReturn(blockStreamConfig);
        given(blockStreamConfig.enableCutover()).willReturn(false);
        given(versionConfig.servicesVersion()).willReturn(new SemanticVersion(0, 74, 0, "", ""));
        given(hederaConfig.configVersion()).willReturn(0);
        given(ctx.isUpgrade(any())).willReturn(false);

        subject.restart(ctx);

        verifyNoInteractions(blockInfoState);
    }

    @Test
    void restartReinitializesVotingFieldsWhenJumpstartEnabled() {
        givenRestartPreconditions();
        givenCutoverDisabled();
        given(configuration.getConfigData(BlockStreamJumpstartConfig.class)).willReturn(blockStreamJumpstartConfig);
        given(blockStreamJumpstartConfig.blockNum()).willReturn(1L);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get())
                .willReturn(baseBlockInfo()
                        .copyBuilder()
                        .votingCompletionDeadlineBlockNumber(123)
                        .votingComplete(false)
                        .build());

        subject.restart(ctx);

        verify(blockInfoState)
                .put(baseBlockInfo()
                        .copyBuilder()
                        .votingComplete(false)
                        .votingCompletionDeadlineBlockNumber(baseBlockInfo().lastBlockNumber() + 10)
                        .migrationRootHashVotes(List.of())
                        .build());
    }

    @Test
    void restartInitializesVotingDeadlineWhenJumpstartEnabled() {
        givenRestartPreconditions();
        givenCutoverDisabled();
        given(configuration.getConfigData(BlockStreamJumpstartConfig.class)).willReturn(blockStreamJumpstartConfig);
        given(blockStreamJumpstartConfig.blockNum()).willReturn(1L);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get()).willReturn(baseBlockInfo());

        subject.restart(ctx);

        verify(blockInfoState)
                .put(baseBlockInfo()
                        .copyBuilder()
                        .votingComplete(false)
                        .votingCompletionDeadlineBlockNumber(baseBlockInfo().lastBlockNumber() + 10)
                        .migrationRootHashVotes(List.of())
                        .build());
    }

    @Test
    void restartSkipsInitializationWhenJumpstartNotPositive() {
        givenRestartPreconditions();
        givenCutoverDisabled();
        given(configuration.getConfigData(BlockStreamJumpstartConfig.class)).willReturn(blockStreamJumpstartConfig);
        given(blockStreamJumpstartConfig.blockNum()).willReturn(0L);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get()).willReturn(baseBlockInfo());

        subject.restart(ctx);

        verify(blockInfoState, never()).put(any());
    }

    @Test
    void sharesBlockInfoAndRunningHashesWhenCutoverEnabled() {
        givenRestartPreconditions();
        given(configuration.getConfigData(BlockStreamConfig.class)).willReturn(blockStreamConfig);
        given(blockStreamConfig.enableCutover()).willReturn(true);
        given(configuration.getConfigData(BlockStreamJumpstartConfig.class)).willReturn(blockStreamJumpstartConfig);
        given(blockStreamJumpstartConfig.blockNum()).willReturn(0L);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        final var blockInfo = baseBlockInfo();
        given(blockInfoState.get()).willReturn(blockInfo);
        final var runningHashes = RunningHashes.DEFAULT;
        @SuppressWarnings("unchecked")
        final WritableSingletonState<RunningHashes> runningHashesState =
                (WritableSingletonState<RunningHashes>) org.mockito.Mockito.mock(WritableSingletonState.class);
        doReturn(runningHashesState).when(writableStates).getSingleton(RUNNING_HASHES_STATE_ID);
        given(runningHashesState.get()).willReturn(runningHashes);
        final Map<String, Object> sharedValues = new HashMap<>();
        given(ctx.sharedValues()).willReturn(sharedValues);

        subject.restart(ctx);

        assertSame(blockInfo, sharedValues.get("SHARED_BLOCK_RECORD_INFO"));
        assertSame(runningHashes, sharedValues.get("SHARED_RUNNING_HASHES"));
    }

    @Test
    void doesNotShareValuesWhenCutoverDisabled() {
        givenRestartPreconditions();
        givenCutoverDisabled();
        given(configuration.getConfigData(BlockStreamJumpstartConfig.class)).willReturn(blockStreamJumpstartConfig);
        given(blockStreamJumpstartConfig.blockNum()).willReturn(0L);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get()).willReturn(baseBlockInfo());

        subject.restart(ctx);

        verify(ctx, never()).sharedValues();
    }

    private void givenRestartPreconditions() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(VersionConfig.class)).willReturn(versionConfig);
        given(configuration.getConfigData(HederaConfig.class)).willReturn(hederaConfig);
        given(versionConfig.servicesVersion()).willReturn(new SemanticVersion(0, 74, 0, "", ""));
        given(hederaConfig.configVersion()).willReturn(0);
        given(ctx.isUpgrade(any())).willReturn(true);
        given(configuration.getConfigData(BlockRecordStreamConfig.class)).willReturn(blockRecordStreamConfig);
        given(blockRecordStreamConfig.liveWritePrevWrappedRecordHashes()).willReturn(true);
    }

    private void givenCutoverDisabled() {
        given(configuration.getConfigData(BlockStreamConfig.class)).willReturn(blockStreamConfig);
        given(blockStreamConfig.enableCutover()).willReturn(false);
    }

    private static BlockInfo baseBlockInfo() {
        return BlockInfo.newBuilder()
                .lastBlockNumber(7)
                .firstConsTimeOfLastBlock(EPOCH)
                .blockHashes(Bytes.EMPTY)
                .migrationRecordsStreamed(true)
                .firstConsTimeOfCurrentBlock(EPOCH)
                .lastUsedConsTime(EPOCH)
                .lastIntervalProcessTime(EPOCH)
                .build();
    }
}

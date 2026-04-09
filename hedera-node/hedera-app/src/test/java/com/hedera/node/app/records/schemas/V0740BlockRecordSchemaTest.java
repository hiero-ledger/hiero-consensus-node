// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamJumpstartConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0740BlockRecordSchemaTest {
    @Mock
    private MigrationContext ctx;

    @Mock
    private Configuration configuration;

    @Mock
    private BlockRecordStreamConfig blockRecordStreamConfig;

    @Mock
    private BlockStreamJumpstartConfig blockStreamJumpstartConfig;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<BlockInfo> blockInfoState;

    private final V0740BlockRecordSchema subject = new V0740BlockRecordSchema();

    @Test
    void versionIsV0740() {
        assertEquals(new SemanticVersion(0, 74, 0, "", ""), subject.getVersion());
    }

    @Test
    void restartIsNoopOnGenesis() {
        given(ctx.isGenesis()).willReturn(true);

        subject.restart(ctx);

        verify(ctx, never()).appConfig();
        verifyNoInteractions(configuration, blockRecordStreamConfig, writableStates, blockInfoState);
    }

    @Test
    void restartIsNoopWhenLiveWriteDisabled() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(BlockRecordStreamConfig.class)).willReturn(blockRecordStreamConfig);
        given(blockRecordStreamConfig.liveWritePrevWrappedRecordHashes()).willReturn(false);

        subject.restart(ctx);

        verify(ctx, never()).newStates();
        verifyNoInteractions(blockInfoState);
    }

    @Test
    void restartSkipsWhenBlockInfoSingletonIsNull() {
        givenRestartPreconditions();
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get()).willReturn(null);

        subject.restart(ctx);

        verify(blockInfoState, never()).put(any());
    }

    @Test
    void restartSkipsWhenVotingAlreadyInitialized() {
        givenRestartPreconditions();
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get())
                .willReturn(baseBlockInfo()
                        .copyBuilder()
                        .votingCompletionDeadlineBlockNumber(123)
                        .votingComplete(false)
                        .build());

        subject.restart(ctx);

        verify(blockInfoState, never()).put(any());
    }

    @Test
    void restartInitializesVotingDeadlineWhenJumpstartEnabled() {
        givenRestartPreconditions();
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
                        .build());
    }

    @Test
    void restartSkipsInitializationWhenJumpstartNotPositive() {
        givenRestartPreconditions();
        given(configuration.getConfigData(BlockStreamJumpstartConfig.class)).willReturn(blockStreamJumpstartConfig);
        given(blockStreamJumpstartConfig.blockNum()).willReturn(0L);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get()).willReturn(baseBlockInfo());

        subject.restart(ctx);

        verify(blockInfoState, never()).put(any());
    }

    private void givenRestartPreconditions() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(BlockRecordStreamConfig.class)).willReturn(blockRecordStreamConfig);
        given(blockRecordStreamConfig.liveWritePrevWrappedRecordHashes()).willReturn(true);
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

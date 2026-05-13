// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0740BlockRecordSchemaTest {
    @Mock
    private MigrationContext<SemanticVersion> ctx;

    @Mock
    private Configuration configuration;

    @Mock
    private BlockStreamConfig blockStreamConfig;

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
        verifyNoInteractions(configuration, writableStates, blockInfoState);
    }

    @Test
    void restartDoesNotShareValuesWhenCutoverDisabled() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(BlockStreamConfig.class)).willReturn(blockStreamConfig);
        given(blockStreamConfig.enableCutover()).willReturn(false);

        subject.restart(ctx);

        verify(ctx, never()).newStates();
        verify(ctx, never()).sharedValues();
    }

    @Test
    void sharesBlockInfoAndRunningHashesWhenCutoverEnabled() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(BlockStreamConfig.class)).willReturn(blockStreamConfig);
        given(blockStreamConfig.enableCutover()).willReturn(true);
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
        verify(blockInfoState, never()).put(org.mockito.ArgumentMatchers.any());
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

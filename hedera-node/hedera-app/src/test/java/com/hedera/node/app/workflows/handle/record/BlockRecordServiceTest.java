// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class BlockRecordServiceTest {
    @Test
    void testGetServiceName() {
        BlockRecordService blockRecordService = new BlockRecordService();
        assertEquals(BlockRecordService.NAME, blockRecordService.getServiceName());
    }

    @SuppressWarnings("unchecked")
    @Test
    void doGenesisSetupWithLiveWriteEnabledSetsVotingComplete() {
        final var service = new BlockRecordService();
        final WritableSingletonState<BlockInfo> blockInfoState = mock(WritableSingletonState.class);
        final WritableSingletonState<RunningHashes> runningHashState = mock(WritableSingletonState.class);
        final var writableStates = mock(WritableStates.class);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(writableStates.<RunningHashes>getSingleton(RUNNING_HASHES_STATE_ID))
                .willReturn(runningHashState);

        final var config = mock(Configuration.class);
        given(config.getConfigData(BlockRecordStreamConfig.class))
                .willReturn(new BlockRecordStreamConfig(
                        "/tmp", "sidecar", 2, 5000, 256, 6, 6, 256, "concurrent", true, "/tmp/wrapped", false, true));

        service.doGenesisSetup(writableStates, config);

        final var captor = ArgumentCaptor.forClass(BlockInfo.class);
        verify(blockInfoState).put(captor.capture());
        assertThat(captor.getValue().votingComplete()).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void doGenesisSetupWithLiveWriteDisabledDoesNotSetVotingComplete() {
        final var service = new BlockRecordService();
        final WritableSingletonState<BlockInfo> blockInfoState = mock(WritableSingletonState.class);
        final WritableSingletonState<RunningHashes> runningHashState = mock(WritableSingletonState.class);
        final var writableStates = mock(WritableStates.class);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(writableStates.<RunningHashes>getSingleton(RUNNING_HASHES_STATE_ID))
                .willReturn(runningHashState);

        final var config = mock(Configuration.class);
        given(config.getConfigData(BlockRecordStreamConfig.class))
                .willReturn(new BlockRecordStreamConfig(
                        "/tmp", "sidecar", 2, 5000, 256, 6, 6, 256, "concurrent", true, "/tmp/wrapped", false, false));

        service.doGenesisSetup(writableStates, config);

        final var captor = ArgumentCaptor.forClass(BlockInfo.class);
        verify(blockInfoState).put(captor.capture());
        assertThat(captor.getValue().votingComplete()).isFalse();
    }
}

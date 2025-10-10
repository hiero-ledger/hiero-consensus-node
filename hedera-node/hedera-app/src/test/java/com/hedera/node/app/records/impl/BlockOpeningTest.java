// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockOpeningTest {

    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);

    @Mock
    private State state;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private ReadableSingletonState<BlockInfo> blockInfoState;

    @Mock
    private ReadableSingletonState<PlatformState> platformState;

    @Mock
    private ReadableSingletonState<RunningHashes> runningHashesState;

    @Mock
    private ReadableStates readableStates;

    @Mock
    private BlockRecordStreamProducer streamFileProducer;

    private BlockRecordManagerImpl subject;

    @Test
    void firstTransactionAlwaysOpensBlock() {
        setupBlockInfo(Instant.EPOCH);
        assertTrue(subject.willOpenNewBlock(CONSENSUS_NOW, state));
    }

    @Test
    void newPeriodOpensBlock() {
        setupBlockInfo(CONSENSUS_NOW);
        assertTrue(subject.willOpenNewBlock(CONSENSUS_NOW.plusSeconds(86_400), state));
    }

    @Test
    void samePeriodWithNoFreezeTimeDoesntOpenBlock() {
        setupBlockInfo(CONSENSUS_NOW);
        given(readableStates.<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID))
                .willReturn(platformState);
        given(platformState.get()).willReturn(PlatformState.DEFAULT);
        assertFalse(subject.willOpenNewBlock(CONSENSUS_NOW, state));
    }

    @Test
    void samePeriodWithFreezeTimeOpensBlock() {
        setupBlockInfo(CONSENSUS_NOW);
        given(readableStates.<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID))
                .willReturn(platformState);
        given(platformState.get())
                .willReturn(PlatformState.newBuilder()
                        .freezeTime(Timestamp.DEFAULT)
                        .lastFrozenTime(Timestamp.DEFAULT)
                        .build());
        assertTrue(subject.willOpenNewBlock(CONSENSUS_NOW, state));
    }

    private void setupBlockInfo(@NonNull final Instant firstConsTimeOfCurrentBlock) {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(DEFAULT_CONFIG, 1));
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get())
                .willReturn(BlockInfo.newBuilder()
                        .firstConsTimeOfCurrentBlock(asTimestamp(firstConsTimeOfCurrentBlock))
                        .build());
        given(readableStates.<RunningHashes>getSingleton(RUNNING_HASHES_STATE_ID))
                .willReturn(runningHashesState);
        given(runningHashesState.get()).willReturn(RunningHashes.DEFAULT);

        subject = new BlockRecordManagerImpl(configProvider, state, streamFileProducer);
    }
}

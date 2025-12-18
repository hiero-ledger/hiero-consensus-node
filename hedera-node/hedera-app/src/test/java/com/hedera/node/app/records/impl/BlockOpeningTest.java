// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.DONT_QUIESCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.QUIESCE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.quiescence.QuiescedHeartbeat;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.system.Platform;
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

    /**
     * Config with legacy mode (roundBoundaryClosingEnabled = false)
     */
    private static final Configuration USER_TXN_CONFIG = HederaTestConfigBuilder.create()
            .withValue("hedera.recordStream.roundBoundaryClosingEnabled", false)
            .getOrCreateConfig();

    /**
     * Config with round-boundary mode (roundBoundaryClosingEnabled = true)
     */
    private static final Configuration ROUND_BOUNDARY_CONFIG = HederaTestConfigBuilder.create()
            .withValue("hedera.recordStream.roundBoundaryClosingEnabled", true)
            .getOrCreateConfig();

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

    @Mock
    private QuiescenceController quiescenceController;

    @Mock
    private QuiescedHeartbeat quiescedHeartbeat;

    @Mock
    private Platform platform;

    private BlockRecordManagerImpl subject;

    // ========== (roundBoundaryClosingEnabled = false) ==========

    @Test
    void legacyMode_firstTransactionAlwaysOpensBlock() {
        setupBlockInfo(Instant.EPOCH, USER_TXN_CONFIG);
        assertTrue(subject.willOpenNewBlock(CONSENSUS_NOW, state));
    }

    @Test
    void legacyMode_newPeriodOpensBlock() {
        setupBlockInfo(CONSENSUS_NOW, USER_TXN_CONFIG);
        assertTrue(subject.willOpenNewBlock(CONSENSUS_NOW.plusSeconds(86_400), state));
    }

    @Test
    void legacyMode_samePeriodWithNoFreezeTimeDoesntOpenBlock() {
        setupBlockInfo(CONSENSUS_NOW, USER_TXN_CONFIG);
        given(readableStates.<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID))
                .willReturn(platformState);
        given(platformState.get()).willReturn(PlatformState.DEFAULT);
        assertFalse(subject.willOpenNewBlock(CONSENSUS_NOW, state));
    }

    @Test
    void legacyMode_samePeriodWithFreezeTimeOpensBlock() {
        setupBlockInfo(CONSENSUS_NOW, USER_TXN_CONFIG);
        given(readableStates.<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID))
                .willReturn(platformState);
        given(platformState.get())
                .willReturn(PlatformState.newBuilder()
                        .freezeTime(Timestamp.DEFAULT)
                        .lastFrozenTime(Timestamp.DEFAULT)
                        .build());
        assertTrue(subject.willOpenNewBlock(CONSENSUS_NOW, state));
    }

    // ========== Round-boundary mode tests (roundBoundaryClosingEnabled = true) ==========

    @Test
    void roundBoundaryMode_willOpenNewBlockAlwaysReturnsFalse() {
        // In round-boundary mode, blocks are opened at round start, not on user transactions
        setupBlockInfo(Instant.EPOCH, ROUND_BOUNDARY_CONFIG);
        assertFalse(subject.willOpenNewBlock(CONSENSUS_NOW, state));
    }

    @Test
    void roundBoundaryMode_newPeriodStillReturnsFalse() {
        // Even with a new period, willOpenNewBlock returns false in round-boundary mode
        setupBlockInfo(CONSENSUS_NOW, ROUND_BOUNDARY_CONFIG);
        assertFalse(subject.willOpenNewBlock(CONSENSUS_NOW.plusSeconds(86_400), state));
    }

    // ========== Quiescence tests ==========

    @Test
    void maybeQuiesceStartsHeartbeatOnQuiesceCommandChange() {
        setupBlockInfo(CONSENSUS_NOW, USER_TXN_CONFIG);
        given(quiescenceController.getQuiescenceStatus()).willReturn(QUIESCE);

        subject.maybeQuiesce(state);
        subject.maybeQuiesce(state);

        verify(platform, times(1)).quiescenceCommand(QUIESCE);
        verify(quiescedHeartbeat, times(1)).start(any(), any());
    }

    @Test
    void maybeQuiesceDoesNothingWhenCommandRemainsDontQuiesce() {
        setupBlockInfo(CONSENSUS_NOW, USER_TXN_CONFIG);
        given(quiescenceController.getQuiescenceStatus()).willReturn(DONT_QUIESCE);

        subject.maybeQuiesce(state);

        verify(platform, never()).quiescenceCommand(any());
        verify(quiescedHeartbeat, never()).start(any(), any());
    }

    private void setupBlockInfo(
            @NonNull final Instant firstConsTimeOfCurrentBlock, @NonNull final Configuration config) {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get())
                .willReturn(BlockInfo.newBuilder()
                        .firstConsTimeOfCurrentBlock(asTimestamp(firstConsTimeOfCurrentBlock))
                        .build());
        given(readableStates.<RunningHashes>getSingleton(RUNNING_HASHES_STATE_ID))
                .willReturn(runningHashesState);
        given(runningHashesState.get()).willReturn(RunningHashes.DEFAULT);

        subject = new BlockRecordManagerImpl(
                configProvider, state, streamFileProducer, quiescenceController, quiescedHeartbeat, platform);
    }
}

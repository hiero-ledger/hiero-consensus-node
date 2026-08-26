// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.platformstate.V0540PlatformStateSchema.UNINITIALIZED_PLATFORM_STATE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.quiescence.QuiescedHeartbeat;
import com.hedera.node.app.quiescence.QuiescenceCommands;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.config.data.QuiescenceConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.InitTrigger;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SealRoundRecordClosureTest extends AppTestBase {
    private static final Bytes RUNNING_HASH = Bytes.wrap(new byte[48]);

    @Mock
    private BlockHashSigner blockHashSigner;

    @Test
    void closesOnSealAndReopensOnNextUserTxInBothMode() {
        final var ctx = newContext("BOTH");
        final var t1 = Instant.ofEpochSecond(1_000L, 1);
        final var t2 = t1.plusSeconds(5);

        assertThat(ctx.manager.startUserTransaction(t1, ctx.state)).isTrue();
        verify(ctx.producer).switchBlocks(2L, 3L, t1);

        ctx.manager.closeCurrentRecordFileIfOpen(ctx.state);
        verify(ctx.producer).finishCurrentBlock();
        assertThat(ctx.manager.lastBlockNo()).isEqualTo(3L);
        assertThat(ctx.manager.blockTimestamp()).isEqualTo(EPOCH);

        assertThat(ctx.manager.startUserTransaction(t2, ctx.state)).isTrue();
        verify(ctx.producer).switchBlocks(3L, 4L, t2);
    }

    @Test
    void recordsModeSealsOnlyAfterTwoIdleSeconds() {
        final var ctx = newContext("RECORDS");
        final var firstCons = Instant.ofEpochSecond(2_000L, 10);

        assertThat(ctx.manager.startUserTransaction(firstCons, ctx.state)).isTrue();

        assertThat(ctx.manager.closeCurrentRecordFileIfConsTimeElapsed(ctx.state, firstCons.plusSeconds(1)))
                .isFalse();
        verify(ctx.producer, times(0)).finishCurrentBlock();

        assertThat(ctx.manager.closeCurrentRecordFileIfConsTimeElapsed(ctx.state, firstCons.plusSeconds(2)))
                .isTrue();
        verify(ctx.producer, times(1)).finishCurrentBlock();
        assertThat(ctx.manager.blockTimestamp()).isEqualTo(EPOCH);

        assertThat(ctx.manager.closeCurrentRecordFileIfConsTimeElapsed(ctx.state, firstCons.plusSeconds(3)))
                .isTrue();
        verify(ctx.producer, times(1)).finishCurrentBlock();
    }

    @Test
    void recordsModeInvokesBlockLifecycleOnOpenAndClose() {
        final var ctx = newContext("RECORDS");
        final var firstCons = Instant.ofEpochSecond(2_000L, 10);

        ctx.manager.startUserTransaction(firstCons, ctx.state);
        verify(ctx.lifecycle).onOpenBlock(ctx.state);

        ctx.manager.closeCurrentRecordFileIfConsTimeElapsed(ctx.state, firstCons.plusSeconds(2));
        verify(ctx.lifecycle).onCloseBlock(ctx.state);
    }

    @Test
    void bothModeDoesNotInvokeRecordBlockLifecycle() {
        final var ctx = newContext("BOTH");
        final var firstCons = Instant.ofEpochSecond(2_000L, 10);

        ctx.manager.startUserTransaction(firstCons, ctx.state);
        ctx.manager.closeCurrentRecordFileIfOpen(ctx.state);

        verify(ctx.lifecycle, times(0)).onOpenBlock(ctx.state);
        verify(ctx.lifecycle, times(0)).onCloseBlock(ctx.state);
    }

    private Context newContext(final String streamMode) {
        final var app = appBuilder()
                .withService(new BlockRecordService())
                .withService(new PlatformStateService())
                .withConfigValue("blockStream.streamMode", streamMode)
                .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", false)
                .withConfigValue("hedera.recordStream.liveWritePrevWrappedRecordHashes", false)
                .build();

        final var startingBlockInfo = BlockInfo.newBuilder()
                .lastBlockNumber(2L)
                .firstConsTimeOfLastBlock(new Timestamp(1_000L, 0))
                .blockHashes(RUNNING_HASH)
                .consTimeOfLastHandledTxn(new Timestamp(1_001L, 0))
                .migrationRecordsStreamed(true)
                .firstConsTimeOfCurrentBlock(EPOCH)
                .lastUsedConsTime(EPOCH)
                .lastIntervalProcessTime(EPOCH)
                .previousWrappedRecordBlockRootHash(RUNNING_HASH)
                .wrappedIntermediatePreviousBlockRootHashes(List.of())
                .wrappedIntermediateBlockRootsLeafCount(0L)
                .votingComplete(true)
                .votingCompletionDeadlineBlockNumber(0L)
                .migrationRootHashVotes(List.of())
                .migrationWrappedHashes(List.of())
                .build();

        app.stateMutator(BlockRecordService.NAME)
                .withSingletonState(RUNNING_HASHES_STATE_ID, new RunningHashes(RUNNING_HASH, null, null, null))
                .withSingletonState(BLOCKS_STATE_ID, startingBlockInfo)
                .commit();
        app.stateMutator(PlatformStateService.NAME)
                .withSingletonState(
                        org.hiero.consensus.platformstate.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID,
                        UNINITIALIZED_PLATFORM_STATE)
                .commit();

        final var producer = Mockito.mock(BlockRecordStreamProducer.class);
        when(producer.getRunningHash()).thenReturn(RUNNING_HASH);
        when(producer.finishCurrentBlock()).thenReturn(CompletableFuture.completedFuture(Bytes.EMPTY));
        final var controller = new QuiescenceController(
                new QuiescenceConfig(false, Duration.ofSeconds(5), Duration.ZERO),
                InstantSource.system(),
                () -> 0,
                Instant::now,
                () -> {},
                mock(QuiescenceCommands.class),
                new NoOpMetrics());
        final var heartbeat = new QuiescedHeartbeat(controller, mock(QuiescenceCommands.class), new NoOpMetrics());
        final Supplier<BlockItemWriter> wrbSupplier = () -> Mockito.mock(BlockItemWriter.class);
        final var lifecycle = Mockito.mock(BlockRecordManager.Lifecycle.class);

        final var manager = new BlockRecordManagerImpl(
                app.configProvider(),
                app.workingStateAccessor().getState(),
                producer,
                controller,
                heartbeat,
                Mockito.mock(WrappedRecordFileBlockHashesDiskWriter.class),
                wrbSupplier,
                blockHashSigner,
                InitTrigger.RESTART,
                lifecycle);
        return new Context(manager, producer, app.workingStateAccessor().getState(), lifecycle);
    }

    private record Context(
            BlockRecordManagerImpl manager,
            BlockRecordStreamProducer producer,
            com.swirlds.state.State state,
            BlockRecordManager.Lifecycle lifecycle) {}
}

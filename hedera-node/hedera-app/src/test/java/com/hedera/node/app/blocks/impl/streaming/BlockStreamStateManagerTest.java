// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.node.app.blocks.impl.streaming.BlockStreamStateManager.BlockStreamQueueItem;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.swirlds.config.api.Configuration;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockStreamStateManagerTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle execSvcHandle;
    private static final VarHandle blockBufferHandle;
    private static final VarHandle isStreamingEnabledHandle;
    private static final VarHandle blockStreamItemQueueHandle;
    private static final VarHandle backPressureFutureRefHandle;
    private static final MethodHandle checkBufferHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            blockBufferHandle = MethodHandles.privateLookupIn(BlockStreamStateManager.class, lookup)
                    .findVarHandle(BlockStreamStateManager.class, "blockBuffer", Queue.class);
            execSvcHandle = MethodHandles.privateLookupIn(BlockStreamStateManager.class, lookup)
                    .findVarHandle(BlockStreamStateManager.class, "execSvc", ScheduledExecutorService.class);
            isStreamingEnabledHandle = MethodHandles.privateLookupIn(BlockStreamStateManager.class, lookup)
                    .findStaticVarHandle(BlockStreamStateManager.class, "isStreamingEnabled", AtomicBoolean.class);
            blockStreamItemQueueHandle = MethodHandles.privateLookupIn(BlockStreamStateManager.class, lookup)
                    .findVarHandle(BlockStreamStateManager.class, "blockStreamItemQueue", Queue.class);
            backPressureFutureRefHandle = MethodHandles.privateLookupIn(BlockStreamStateManager.class, lookup)
                    .findStaticVarHandle(
                            BlockStreamStateManager.class, "backpressureCompletableFutureRef", AtomicReference.class);

            final Method checkBufferMethod = BlockStreamStateManager.class.getDeclaredMethod("checkBuffer");
            checkBufferMethod.setAccessible(true);
            checkBufferHandle = lookup.unreflect(checkBufferMethod);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final long TEST_BLOCK_NUMBER = 1L;
    private static final long TEST_BLOCK_NUMBER2 = 2L;
    private static final long TEST_BLOCK_NUMBER3 = 3L;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private BlockNodeConnectionManager connectionManager;

    @Mock
    private BlockStreamMetrics blockStreamMetrics;

    private BlockStreamStateManager blockStreamStateManager;

    @BeforeEach
    void beforeEach() {
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .getOrCreateConfig();

        lenient().when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));
    }

    @AfterEach
    void afterEach() throws InterruptedException {
        final CompletableFuture<Boolean> f = backpressureCompletableFutureRef().getAndSet(null);
        if (f != null) {
            f.complete(false);
        }

        // stop the async pruning thread(s)
        final ScheduledExecutorService execSvc = (ScheduledExecutorService) execSvcHandle.get(blockStreamStateManager);
        execSvc.shutdownNow();
        assertThat(execSvc.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void testOpenNewBlock() {
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        // given
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);
        // when
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);

        // then
        assertAll(
                () -> assertThat(blockStreamStateManager.getBlockNumber()).isNotNull(),
                () -> assertThat(blockStreamStateManager.getBlockNumber()).isEqualTo(TEST_BLOCK_NUMBER),
                () -> assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER))
                        .isNotNull(),
                () -> assertThat(blockStreamStateManager
                                .getBlockState(TEST_BLOCK_NUMBER)
                                .blockNumber())
                        .isEqualTo(TEST_BLOCK_NUMBER));
    }

    @Test
    void testCleanUp_NotCompletedBlockState_ShouldNotBeRemoved() {
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        // given
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);

        // when
        blockStreamStateManager.setLatestAcknowledgedBlock(TEST_BLOCK_NUMBER);

        // then
        // not completed states should not be removed
        assertThat(blockStreamStateManager.isAcked(TEST_BLOCK_NUMBER)).isTrue();
        final BlockState actualBlockState = blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER);
        assertThat(actualBlockState).isNotNull();
        assertFalse(actualBlockState.requestsCompleted());
    }

    @Test
    void testCleanUp_CompletedNotExpiredBlockState_ShouldNotBeRemoved() {
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        // given
        // expiry period set to zero in order for completed state to be cleared
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).setCompletionTimestamp();

        // when
        blockStreamStateManager.setLatestAcknowledgedBlock(TEST_BLOCK_NUMBER);

        // then
        // completed states should be removed
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).isNotNull();
    }

    @Test
    void testMaintainMultipleBlockStates() {
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        // given
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);
        // when
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER2);

        // then
        assertAll(
                () -> assertThat(blockStreamStateManager.getBlockNumber()).isEqualTo(TEST_BLOCK_NUMBER2),
                () -> assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER))
                        .isNotNull(),
                () -> assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER2))
                        .isNotNull(),
                () -> assertThat(blockStreamStateManager
                                .getBlockState(TEST_BLOCK_NUMBER)
                                .blockNumber())
                        .isEqualTo(TEST_BLOCK_NUMBER),
                () -> assertThat(blockStreamStateManager
                                .getBlockState(TEST_BLOCK_NUMBER2)
                                .blockNumber())
                        .isEqualTo(TEST_BLOCK_NUMBER2));
    }

    @Test
    void testHandleNonExistentBlockState() {
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        // when
        final BlockState blockState = blockStreamStateManager.getBlockState(999L);

        // then
        assertThat(blockState).isNull();
    }

    @Test
    void testCompletedExpiredBlockStateIsRemovedUpToSpecificBlockNumber() {
        // given
        // mock the number of batch items by modifying the default config
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.blockItemBatchSize", 5)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER2);
        blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).setCompletionTimestamp();
        blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER2).setCompletionTimestamp();

        // when
        blockStreamStateManager.setLatestAcknowledgedBlock(TEST_BLOCK_NUMBER);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).isNotNull();
        assertThat(blockStreamStateManager.isAcked(TEST_BLOCK_NUMBER)).isTrue();
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER2)).isNotNull();
        assertThat(blockStreamStateManager.isAcked(TEST_BLOCK_NUMBER2)).isFalse();
    }

    @Test
    void testGetCurrentBlockNumberWhenNoNewBlockIsOpened() {
        // given
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);

        // when and then
        // -1 is a sentinel value indicating no block has been opened
        assertThat(blockStreamStateManager.getBlockNumber()).isEqualTo(-1);
    }

    @Test
    void testGetCurrentBlockNumberWhenNewBlockIsOpened() {
        // given
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER2);

        // when and then
        assertThat(blockStreamStateManager.getBlockNumber()).isEqualTo(TEST_BLOCK_NUMBER2);
    }

    // Negative And Edge Test Cases
    @Test
    void testOpenBlockWithNegativeBlockNumber() {
        // given
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);

        // when and then
        assertThatThrownBy(() -> blockStreamStateManager.openBlock(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Block number must be non-negative");

        // -1 is a sentinel value indicating no block has been opened
        assertThat(blockStreamStateManager.getBlockNumber()).isEqualTo(-1L);
    }

    @Test
    void testAddNullBlockItem() {
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        // given
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);

        // when and then
        assertThatThrownBy(() -> blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("blockItem must not be null");
    }

    @Test
    void testAddBlockItemToNonExistentBlockState() {
        // given
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);

        // when and then
        assertThatThrownBy(() -> blockStreamStateManager.addItem(
                        TEST_BLOCK_NUMBER, BlockItem.newBuilder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Block state not found for block " + TEST_BLOCK_NUMBER);
    }

    @Test
    void testGetNonExistentBlockState() {
        // given
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);

        // when and then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).isNull();
    }

    @Test
    void testStreamPreBlockProofItemsForNonExistentBlockState() {
        // given
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);

        // when and then
        assertThatThrownBy(() -> blockStreamStateManager.streamPreBlockProofItems(TEST_BLOCK_NUMBER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Block state not found for block " + TEST_BLOCK_NUMBER);
    }

    @Test
    void testOpenExistingBlock() {
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);
        blockStreamStateManager.openBlock(2L);

        // try to open the same block number
        assertThatThrownBy(() -> blockStreamStateManager.openBlock(2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Attempted to open a new block with number 2, but a block with the same or "
                        + "later number (latest: 2) has already been opened");

        // try to open an older block
        assertThatThrownBy(() -> blockStreamStateManager.openBlock(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Attempted to open a new block with number 1, but a block with the same or "
                        + "later number (latest: 2) has already been opened");
    }

    @Test
    void testBuffer() throws Throwable {
        final Duration blockTtl = Duration.ofSeconds(5);
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.blockPeriod", Duration.ofSeconds(1))
                .withValue("blockStream.blockItemBatchSize", 3)
                .withValue("blockStream.blockBufferTtl", blockTtl)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);
        final Queue<BlockState> buffer = bufferQueue(blockStreamStateManager);

        // IdealMaxBufferSize = BlockTtl (5s) / BlockPeriod (1s) = 5

        // add some blocks, but don't ack them
        blockStreamStateManager.openBlock(1L);
        blockStreamStateManager.closeBlock(1L);
        blockStreamStateManager.openBlock(2L);
        blockStreamStateManager.closeBlock(2L);
        blockStreamStateManager.openBlock(3L);
        blockStreamStateManager.closeBlock(3L);
        blockStreamStateManager.openBlock(4L);
        blockStreamStateManager.closeBlock(4L);

        // wait for the TTL period, with a little padding
        Thread.sleep(blockTtl.plusMillis(250));
        // prune the buffer, nothing should be removed since nothing is acked and we are not yet saturated
        checkBufferHandle.invoke(blockStreamStateManager);
        assertThat(blockStreamStateManager.isBufferSaturated()).isFalse();
        verify(blockStreamMetrics).updateBlockBufferSaturation(80.0); // the buffer is 80% saturated
        long oldestUnackedMillis =
                blockStreamStateManager.getBlockState(1L).completionTimestamp().toEpochMilli();
        verify(blockStreamMetrics).setOldestUnacknowledgedBlockTime(oldestUnackedMillis);
        assertThat(buffer).hasSize(4);

        // reset the block stream metrics mock to capture the next interaction that has the same value as before
        reset(blockStreamMetrics);

        // add another block and prune again, this will cause the buffer to be fully saturated
        blockStreamStateManager.openBlock(5L);
        blockStreamStateManager.closeBlock(5L);
        checkBufferHandle.invoke(blockStreamStateManager);
        // the buffer is now marked as saturated because multiple blocks have not been acked yet and they are expired
        assertThat(blockStreamStateManager.isBufferSaturated()).isTrue();
        verify(blockStreamMetrics).updateBlockBufferSaturation(100.0); // the buffer is 100% saturated
        oldestUnackedMillis =
                blockStreamStateManager.getBlockState(1L).completionTimestamp().toEpochMilli();
        verify(blockStreamMetrics).setOldestUnacknowledgedBlockTime(oldestUnackedMillis);

        // reset the block stream metrics mock to capture the next interaction that has the same value as before
        reset(blockStreamMetrics);

        assertThat(buffer).hasSize(5);

        // "overflow" the buffer
        blockStreamStateManager.openBlock(6L);
        blockStreamStateManager.closeBlock(6L);
        checkBufferHandle.invoke(blockStreamStateManager);
        assertThat(blockStreamStateManager.isBufferSaturated()).isTrue();
        verify(blockStreamMetrics).updateBlockBufferSaturation(120.0); // the buffer is 120% saturated
        oldestUnackedMillis =
                blockStreamStateManager.getBlockState(1L).completionTimestamp().toEpochMilli();
        verify(blockStreamMetrics).setOldestUnacknowledgedBlockTime(oldestUnackedMillis);
        reset(blockStreamMetrics);
        assertThat(buffer).hasSize(6);

        // ack up to block 3
        blockStreamStateManager.setLatestAcknowledgedBlock(3L);
        verify(blockStreamMetrics).setLatestAcknowledgedBlockNumber(3L);

        // now blocks 1-3 are acked
        assertThat(blockStreamStateManager.isAcked(1L)).isTrue();
        assertThat(blockStreamStateManager.isAcked(2L)).isTrue();
        assertThat(blockStreamStateManager.isAcked(3L)).isTrue();

        // now that multiple blocks are acked, run pruning again and verify we are no longer saturated
        checkBufferHandle.invoke(blockStreamStateManager);
        assertThat(blockStreamStateManager.isBufferSaturated()).isFalse();
        verify(blockStreamMetrics).updateBlockBufferSaturation(60.0); // the buffer is 60% saturated
        oldestUnackedMillis =
                blockStreamStateManager.getBlockState(4L).completionTimestamp().toEpochMilli();
        verify(blockStreamMetrics).setOldestUnacknowledgedBlockTime(oldestUnackedMillis);
        reset(blockStreamMetrics);
        assertThat(buffer).hasSize(3);

        // ack up to block 6, run pruning, and verify the buffer is not saturated
        blockStreamStateManager.setLatestAcknowledgedBlock(6L);
        Thread.sleep(blockTtl.plusMillis(250));
        checkBufferHandle.invoke(blockStreamStateManager);
        assertThat(blockStreamStateManager.isBufferSaturated()).isFalse();
        verify(blockStreamMetrics).updateBlockBufferSaturation(0.0); // the buffer is 0% saturated
        verify(blockStreamMetrics).setOldestUnacknowledgedBlockTime(-1); // there is no unacked block
        reset(blockStreamMetrics);
        assertThat(buffer).isEmpty();

        // now add another block without acking and ensure the buffer is partially saturated
        blockStreamStateManager.openBlock(7L);
        blockStreamStateManager.closeBlock(7L);
        checkBufferHandle.invoke(blockStreamStateManager);
        assertThat(blockStreamStateManager.isBufferSaturated()).isFalse();
        verify(blockStreamMetrics).updateBlockBufferSaturation(20.0); // the buffer is 20% saturated
        oldestUnackedMillis =
                blockStreamStateManager.getBlockState(7L).completionTimestamp().toEpochMilli();
        verify(blockStreamMetrics).setOldestUnacknowledgedBlockTime(oldestUnackedMillis);
        reset(blockStreamMetrics);
        assertThat(buffer).hasSize(1);
    }

    @Test
    void testFutureBlockAcked() throws Throwable {
        /*
         * There is a scenario where a block node (BN) may have a later block than what the active consensus node (CN)
         * has. For example, if a CN goes down then then another CN node may send blocks to the BN. When the original
         * CN reconnects to the BN, the BN may indicate that is has later blocks from another CN.
         */

        final Duration blockTtl = Duration.ofSeconds(1);
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.blockItemBatchSize", 3)
                .withValue("blockStream.blockBufferTtl", blockTtl)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);
        blockStreamStateManager.openBlock(1L);

        // Block 1 has been added. Now lets ack up to block 5
        blockStreamStateManager.setLatestAcknowledgedBlock(5L);

        // Since we've acked up to block 5, the block we opened _and_ any blocks we've yet to process up to 5 should
        // be considered acked
        assertThat(blockStreamStateManager.isAcked(1L)).isTrue();
        assertThat(blockStreamStateManager.isAcked(2L)).isTrue();
        assertThat(blockStreamStateManager.isAcked(3L)).isTrue();
        assertThat(blockStreamStateManager.isAcked(4L)).isTrue();
        assertThat(blockStreamStateManager.isAcked(5L)).isTrue();
        assertThat(blockStreamStateManager.isAcked(6L)).isFalse(); // only blocks up to 5 have been acked

        // Since we've acked up to block 5, that also means any blocks up to 5 will also be pruned as soon as they
        // expire
        // Add some more blocks, then check after pruning
        blockStreamStateManager.openBlock(2L);
        blockStreamStateManager.openBlock(3L);
        blockStreamStateManager.openBlock(4L);
        blockStreamStateManager.openBlock(5L);
        blockStreamStateManager.openBlock(6L);

        // close the blocks
        blockStreamStateManager.closeBlock(1L);
        blockStreamStateManager.closeBlock(2L);
        blockStreamStateManager.closeBlock(3L);
        blockStreamStateManager.closeBlock(4L);
        blockStreamStateManager.closeBlock(5L);
        blockStreamStateManager.closeBlock(6L);

        // wait for the TTL period, with a little padding
        Thread.sleep(blockTtl.plusMillis(250));
        checkBufferHandle.invoke(blockStreamStateManager);

        // Add another block to trigger the prune, then verify the state... there should only be blocks 6 and 7 buffered
        blockStreamStateManager.openBlock(7L);

        final Queue<BlockState> buffer = bufferQueue(blockStreamStateManager);
        assertThat(buffer).hasSize(2);
        assertThat(blockStreamStateManager.getBlockState(6L)).isNotNull();
        assertThat(blockStreamStateManager.getBlockState(7L)).isNotNull();
    }

    @Test
    void testBufferBackpressure() throws Throwable {
        // ensure block TTL is greater than prune interval for this test to work as expected
        final Duration blockTtl = Duration.ofSeconds(2);
        final Duration pruneInterval = Duration.ofSeconds(1);
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 3)
                .withValue("blockStream.blockBufferTtl", blockTtl)
                .withValue("blockStream.blockBufferPruneInterval", pruneInterval)
                .withValue("blockStream.writerMode", BlockStreamWriterMode.FILE_AND_GRPC)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);

        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicLong waitDurationMs = new AtomicLong(0L);
        final AtomicReference<Throwable> exceptionRef = new AtomicReference<>(null);

        ForkJoinPool.commonPool().execute(() -> {
            try {
                startLatch.await();

                final long start = System.currentTimeMillis();
                BlockStreamStateManager.ensureNewBlocksPermitted();
                final long durationMs = System.currentTimeMillis() - start;
                waitDurationMs.set(durationMs);
            } catch (final Exception e) {
                exceptionRef.set(e);
            } finally {
                doneLatch.countDown();
            }
        });

        // create some blocks such that the buffer will be saturated
        blockStreamStateManager.openBlock(1L);
        blockStreamStateManager.closeBlock(1L);
        blockStreamStateManager.openBlock(2L);
        blockStreamStateManager.closeBlock(2L);
        blockStreamStateManager.openBlock(3L);
        blockStreamStateManager.closeBlock(3L);

        // Auto-pruning is enabled and since the prune internal is less than the block TTL, by waiting for the block TTL
        // period, plus some extra time, the pruning should detect that the buffer is saturated and enable backpressure
        Thread.sleep(blockTtl.plusMillis(250));
        // Now start the thread we spawned earlier and have this current thread sleep for a couple seconds to prove the
        // other thread is blocked
        startLatch.countDown();
        Thread.sleep(2_000);
        // ack the blocks and wait for some more time... this should allow the
        blockStreamStateManager.setLatestAcknowledgedBlock(3L);
        Thread.sleep(1_000);
        // wait for the spawned thread to complete
        assertThat(doneLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // the spawned thread has completed, now verify state
        assertThat(exceptionRef).hasNullValue(); // no exception should have occurred
        // between the time the spawned thread was started and the time the buffer was marked as not being saturated
        // should be at least 2 seconds - since we slept for that long before doing the ack
        assertThat(waitDurationMs).hasValueGreaterThan(2_000L);
    }

    @Test
    void testSetLatestAcknowledgedBlock() {
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);

        blockStreamStateManager.setLatestAcknowledgedBlock(1L);
        verify(blockStreamMetrics).setLatestAcknowledgedBlockNumber(1L);
        reset(blockStreamMetrics);

        blockStreamStateManager.setLatestAcknowledgedBlock(0L);
        verify(blockStreamMetrics).setLatestAcknowledgedBlockNumber(1L);
        reset(blockStreamMetrics);

        blockStreamStateManager.setLatestAcknowledgedBlock(100L);
        verify(blockStreamMetrics).setLatestAcknowledgedBlockNumber(100L);
    }

    @Test
    void constructorShouldNotSchedulePruningWhenStreamingToBlockNodesDisabled() {
        // Configure streamToBlockNodes to return false
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.writerMode", BlockStreamWriterMode.FILE)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // Create a new instance
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        // Get the executor service via reflection
        final ScheduledExecutorService execSvc = (ScheduledExecutorService) execSvcHandle.get(blockStreamStateManager);

        // Verify that no tasks were scheduled (the executor should be empty)
        assertThat(execSvc.shutdownNow()).isEmpty();
    }

    @Test
    void openBlockShouldNotNotifyBlockNodeConnectionManagerWhenStreamingToBlockNodesDisabled() {
        // Configure streamToBlockNodes to return false
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.writerMode", BlockStreamWriterMode.FILE)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // Create a new instance
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(connectionManager);

        // Call openBlock
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);

        // Verify that blockNodeConnectionManager.openBlock was not called
        verify(connectionManager, never()).openBlock(TEST_BLOCK_NUMBER);
    }

    @Test
    void testOpenBlock_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        final Queue<BlockState> buffer = bufferQueue(blockStreamStateManager);

        isStreamingEnabled.set(false);

        blockStreamStateManager.openBlock(10L);

        assertThat(buffer).isEmpty();

        verifyNoInteractions(blockStreamMetrics);
        verifyNoInteractions(connectionManager);
    }

    @Test
    void testAddItem_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        final Queue<BlockStreamQueueItem> itemQueue = blockStreamItemQueue(blockStreamStateManager);

        isStreamingEnabled.set(false);

        final BlockItem item = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(10L).build())
                .build();

        blockStreamStateManager.addItem(10L, item);

        assertThat(itemQueue).isEmpty();

        verifyNoInteractions(blockStreamMetrics);
        verifyNoInteractions(connectionManager);
    }

    @Test
    void testCloseBlock_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        isStreamingEnabled.set(false);

        blockStreamStateManager.closeBlock(10L);

        verifyNoInteractions(blockStreamMetrics);
        verifyNoInteractions(connectionManager);
    }

    @Test
    void testStreamPreBlockProofItems_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        isStreamingEnabled.set(false);

        blockStreamStateManager.streamPreBlockProofItems(10L);

        verifyNoInteractions(blockStreamMetrics);
        verifyNoInteractions(connectionManager);
    }

    @Test
    void testSetLatestAcknowledgedBlock_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        isStreamingEnabled.set(false);

        blockStreamStateManager.setLatestAcknowledgedBlock(10L);

        verifyNoInteractions(blockStreamMetrics);
        verifyNoInteractions(connectionManager);
    }

    @Test
    void testEnsureNewBlocksPermitted_streamingDisabled() throws InterruptedException {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef = backpressureCompletableFutureRef();
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        isStreamingEnabled.set(false);
        backPressureFutureRef.set(new CompletableFuture<>());

        final CountDownLatch doneLatch = new CountDownLatch(1);

        ForkJoinPool.commonPool().execute(() -> {
            BlockStreamStateManager.ensureNewBlocksPermitted();
            doneLatch.countDown();
        });

        assertThat(doneLatch.await(1, TimeUnit.SECONDS)).isTrue();

        verifyNoInteractions(blockStreamMetrics);
        verifyNoInteractions(connectionManager);
    }

    // Utilities

    private Queue<BlockStreamQueueItem> blockStreamItemQueue(final BlockStreamStateManager stateManager) {
        return (Queue<BlockStreamQueueItem>) blockStreamItemQueueHandle.get(stateManager);
    }

    private AtomicBoolean isStreamingEnabled() {
        return (AtomicBoolean) isStreamingEnabledHandle.get();
    }

    private AtomicReference<CompletableFuture<Boolean>> backpressureCompletableFutureRef() {
        return (AtomicReference<CompletableFuture<Boolean>>) backPressureFutureRefHandle.get();
    }

    private Queue<BlockState> bufferQueue(final BlockStreamStateManager stateManager) {
        return (Queue<BlockState>) blockBufferHandle.get(stateManager);
    }
}

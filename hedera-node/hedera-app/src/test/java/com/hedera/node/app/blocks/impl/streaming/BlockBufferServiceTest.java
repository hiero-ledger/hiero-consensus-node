// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.generateBlockItems;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.generateRandomBlocks;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.writeBlockToDisk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService.PruneResult;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockBufferConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.config.api.Configuration;
import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hiero.block.api.PublishStreamRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockBufferServiceTest extends BlockNodeCommunicationTestBase {

    private static final String testDir = "testDir";
    private static final File testDirFile = new File(testDir);

    private static final VarHandle execSvcHandle;
    private static final VarHandle blockBufferHandle;
    private static final VarHandle backPressureFutureRefHandle;
    private static final VarHandle highestAckedBlockNumberHandle;
    private static final VarHandle lastPruningResultHandle;
    private static final MethodHandle checkBufferHandle;
    private static final MethodHandle persistBufferHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            blockBufferHandle = MethodHandles.privateLookupIn(BlockBufferService.class, lookup)
                    .findVarHandle(BlockBufferService.class, "blockBuffer", ConcurrentMap.class);
            execSvcHandle = MethodHandles.privateLookupIn(BlockBufferService.class, lookup)
                    .findVarHandle(BlockBufferService.class, "execSvc", ScheduledExecutorService.class);
            backPressureFutureRefHandle = MethodHandles.privateLookupIn(BlockBufferService.class, lookup)
                    .findVarHandle(BlockBufferService.class, "backpressureCompletableFutureRef", AtomicReference.class);
            highestAckedBlockNumberHandle = MethodHandles.privateLookupIn(BlockBufferService.class, lookup)
                    .findVarHandle(BlockBufferService.class, "highestAckedBlockNumber", AtomicLong.class);
            lastPruningResultHandle = MethodHandles.privateLookupIn(BlockBufferService.class, lookup)
                    .findVarHandle(BlockBufferService.class, "lastPruningResult", PruneResult.class);

            final Method checkBufferMethod = BlockBufferService.class.getDeclaredMethod("checkBuffer");
            checkBufferMethod.setAccessible(true);
            checkBufferHandle = lookup.unreflect(checkBufferMethod);

            final Method persisBufferMethod = BlockBufferService.class.getDeclaredMethod("persistBuffer");
            persisBufferMethod.setAccessible(true);
            persistBufferHandle = lookup.unreflect(persisBufferMethod);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final long TEST_BLOCK_NUMBER = 1L;
    private static final long TEST_BLOCK_NUMBER2 = 2L;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfiguration;

    @Mock
    private BlockStreamConfig blockStreamConfig;

    @Mock
    private BlockBufferConfig blockBufferConfig;

    @Mock
    private BlockNodeConnectionManager connectionManager;

    @Mock
    private BlockStreamMetrics blockStreamMetrics;

    private BlockBufferService blockBufferService;

    @BeforeEach
    void beforeEach() throws IOException {
        cleanupDirectory();

        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", false)
                .getOrCreateConfig();

        lenient().when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));
    }

    @AfterEach
    void afterEach() throws InterruptedException, IOException {
        final CompletableFuture<Boolean> f =
                backpressureCompletableFutureRef(blockBufferService).getAndSet(null);
        if (f != null) {
            f.complete(false);
        }

        // stop the async pruning thread(s)
        final ScheduledExecutorService execSvc = (ScheduledExecutorService) execSvcHandle.get(blockBufferService);
        execSvc.shutdownNow();
        assertThat(execSvc.awaitTermination(3, TimeUnit.SECONDS)).isTrue();

        cleanupDirectory();
    }

    @Test
    void testOpenNewBlock() {
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        // given
        blockBufferService.setBlockNodeConnectionManager(connectionManager);
        // when
        blockBufferService.openBlock(TEST_BLOCK_NUMBER);

        // then
        assertAll(
                () -> assertThat(blockBufferService.getLastBlockNumberProduced())
                        .isNotNull(),
                () -> assertThat(blockBufferService.getLastBlockNumberProduced())
                        .isEqualTo(TEST_BLOCK_NUMBER),
                () -> assertThat(blockBufferService.getBlockState(TEST_BLOCK_NUMBER))
                        .isNotNull(),
                () -> assertThat(blockBufferService
                                .getBlockState(TEST_BLOCK_NUMBER)
                                .blockNumber())
                        .isEqualTo(TEST_BLOCK_NUMBER),
                () -> assertThat(blockBufferService.getEarliestAvailableBlockNumber())
                        .isEqualTo(TEST_BLOCK_NUMBER));
    }

    @Test
    void testCleanUp_NotCompletedBlockState_ShouldNotBeRemoved() {
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        // given
        blockBufferService.setBlockNodeConnectionManager(connectionManager);
        blockBufferService.openBlock(TEST_BLOCK_NUMBER);

        // when
        blockBufferService.setLatestAcknowledgedBlock(TEST_BLOCK_NUMBER);

        // then
        // not completed states should not be removed
        assertThat(blockBufferService.isAcked(TEST_BLOCK_NUMBER)).isTrue();
        final BlockState actualBlockState = blockBufferService.getBlockState(TEST_BLOCK_NUMBER);
        assertThat(actualBlockState).isNotNull();
        assertThat(actualBlockState.isBlockProofSent()).isFalse();
    }

    @Test
    void testCleanUp_CompletedNotExpiredBlockState_ShouldNotBeRemoved() {
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        // given
        // expiry period set to zero in order for completed state to be cleared
        blockBufferService.setBlockNodeConnectionManager(connectionManager);
        blockBufferService.openBlock(TEST_BLOCK_NUMBER);
        blockBufferService.getBlockState(TEST_BLOCK_NUMBER).closeBlock();

        // when
        blockBufferService.setLatestAcknowledgedBlock(TEST_BLOCK_NUMBER);

        // then
        // completed states should be removed
        assertThat(blockBufferService.getBlockState(TEST_BLOCK_NUMBER)).isNotNull();
    }

    @Test
    void testMaintainMultipleBlockStates() {
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        // given
        blockBufferService.setBlockNodeConnectionManager(connectionManager);
        // when
        blockBufferService.openBlock(TEST_BLOCK_NUMBER);
        blockBufferService.openBlock(TEST_BLOCK_NUMBER2);

        // then
        assertAll(
                () -> assertThat(blockBufferService.getLastBlockNumberProduced())
                        .isEqualTo(TEST_BLOCK_NUMBER2),
                () -> assertThat(blockBufferService.getBlockState(TEST_BLOCK_NUMBER))
                        .isNotNull(),
                () -> assertThat(blockBufferService.getBlockState(TEST_BLOCK_NUMBER2))
                        .isNotNull(),
                () -> assertThat(blockBufferService
                                .getBlockState(TEST_BLOCK_NUMBER)
                                .blockNumber())
                        .isEqualTo(TEST_BLOCK_NUMBER),
                () -> assertThat(blockBufferService
                                .getBlockState(TEST_BLOCK_NUMBER2)
                                .blockNumber())
                        .isEqualTo(TEST_BLOCK_NUMBER2),
                () -> assertThat(blockBufferService.getEarliestAvailableBlockNumber())
                        .isEqualTo(TEST_BLOCK_NUMBER));
    }

    @Test
    void testHandleNonExistentBlockState() {
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        // when
        final BlockState blockState = blockBufferService.getBlockState(999L);

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
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", false)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make BlockBufferService use the mocked config
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);

        blockBufferService.setBlockNodeConnectionManager(connectionManager);
        blockBufferService.openBlock(TEST_BLOCK_NUMBER);
        blockBufferService.openBlock(TEST_BLOCK_NUMBER2);
        blockBufferService.getBlockState(TEST_BLOCK_NUMBER).closeBlock();
        blockBufferService.getBlockState(TEST_BLOCK_NUMBER2).closeBlock();

        // when
        blockBufferService.setLatestAcknowledgedBlock(TEST_BLOCK_NUMBER);

        // then
        assertThat(blockBufferService.getBlockState(TEST_BLOCK_NUMBER)).isNotNull();
        assertThat(blockBufferService.isAcked(TEST_BLOCK_NUMBER)).isTrue();
        assertThat(blockBufferService.getBlockState(TEST_BLOCK_NUMBER2)).isNotNull();
        assertThat(blockBufferService.isAcked(TEST_BLOCK_NUMBER2)).isFalse();
    }

    @Test
    void testGetCurrentBlockNumberWhenNoNewBlockIsOpened() {
        // given
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        // when and then
        // -1 is a sentinel value indicating no block has been opened
        assertThat(blockBufferService.getLastBlockNumberProduced()).isEqualTo(-1);
    }

    @Test
    void testGetCurrentBlockNumberWhenNewBlockIsOpened() {
        // given
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);
        blockBufferService.openBlock(TEST_BLOCK_NUMBER2);

        // when and then
        assertThat(blockBufferService.getLastBlockNumberProduced()).isEqualTo(TEST_BLOCK_NUMBER2);
    }

    // Negative And Edge Test Cases
    @Test
    void testOpenBlockWithNegativeBlockNumber() {
        // given
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        // when and then
        assertThatThrownBy(() -> blockBufferService.openBlock(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Block number must be non-negative");

        // -1 is a sentinel value indicating no block has been opened
        assertThat(blockBufferService.getLastBlockNumberProduced()).isEqualTo(-1L);
    }

    @Test
    void testAddNullBlockItem() {
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        // given
        blockBufferService.setBlockNodeConnectionManager(connectionManager);
        blockBufferService.openBlock(TEST_BLOCK_NUMBER);

        // when and then
        assertThatThrownBy(() -> blockBufferService.addItem(TEST_BLOCK_NUMBER, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("blockItem must not be null");
    }

    @Test
    void testAddBlockItemToNonExistentBlockState() {
        // given
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        // when and then
        assertThatThrownBy(() -> blockBufferService.addItem(
                        TEST_BLOCK_NUMBER, BlockItem.newBuilder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Block state not found for block " + TEST_BLOCK_NUMBER);
    }

    @Test
    void testGetNonExistentBlockState() {
        // given
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        // when and then
        assertThat(blockBufferService.getBlockState(TEST_BLOCK_NUMBER)).isNull();
    }

    @Test
    void testOpenBlock_existingBlock_proofNotSent() {
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        blockBufferService.openBlock(10);
        blockBufferService.addItem(10, newBlockProofItem());
        final BlockState block = blockBufferService.getBlockState(10);
        assertThat(block).isNotNull();
        assertThat(block.isBlockProofSent()).isFalse();

        // we've created the block and it has the proof, but it hasn't been sent yet so re-opening is permitted

        blockBufferService.openBlock(10);

        final BlockState newBlock = blockBufferService.getBlockState(10);
        assertThat(newBlock).isNotEqualTo(block);
    }

    @Test
    void testOpenBlock_existingBlock_proofSent() {
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        blockBufferService.openBlock(10);
        blockBufferService.addItem(10, newBlockProofItem());
        final BlockState block = blockBufferService.getBlockState(10);
        assertThat(block).isNotNull();
        block.processPendingItems(10); // process the items to create a request
        block.markRequestSent(0); // mark the request that was created as sent
        assertThat(block.isBlockProofSent()).isTrue();

        // we've sent the block proof, re-opening is not permitted
        assertThatThrownBy(() -> blockBufferService.openBlock(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Attempted to open block 10, but this block already has the block proof sent");
    }

    @Test
    void testBuffer() throws Throwable {
        final Duration blockTtl = Duration.ofSeconds(5);
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("blockStream.blockPeriod", Duration.ofSeconds(1))
                .withValue("blockStream.blockItemBatchSize", 3)
                .withValue("blockStream.buffer.blockTtl", blockTtl)
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", false)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);
        final ConcurrentMap<Long, BlockState> buffer = blockBuffer(blockBufferService);

        // IdealMaxBufferSize = BlockTtl (5s) / BlockPeriod (1s) = 5

        // add some blocks, but don't ack them
        blockBufferService.openBlock(1L);
        blockBufferService.closeBlock(1L);
        blockBufferService.openBlock(2L);
        blockBufferService.closeBlock(2L);
        blockBufferService.openBlock(3L);
        blockBufferService.closeBlock(3L);
        blockBufferService.openBlock(4L);
        blockBufferService.closeBlock(4L);

        // wait for the TTL period, with a little padding
        Thread.sleep(blockTtl.plusMillis(250));
        // prune the buffer, nothing should be removed since nothing is acked and we are not yet saturated
        checkBufferHandle.invoke(blockBufferService);
        assertThat(lastPruningResult(blockBufferService).isSaturated).isFalse();
        verify(blockStreamMetrics).updateBlockBufferSaturation(80.0); // the buffer is 80% saturated
        long oldestUnackedMillis = buffer.get(1L).closedTimestamp().toEpochMilli();
        verify(blockStreamMetrics).setOldestUnacknowledgedBlockTime(oldestUnackedMillis);
        assertThat(buffer).hasSize(4);

        // reset the block stream metrics mock to capture the next interaction that has the same value as before
        reset(blockStreamMetrics);

        // add another block and prune again, this will cause the buffer to be fully saturated
        blockBufferService.openBlock(5L);
        blockBufferService.closeBlock(5L);
        checkBufferHandle.invoke(blockBufferService);
        // the buffer is now marked as saturated because multiple blocks have not been acked yet and they are expired
        assertThat(lastPruningResult(blockBufferService).isSaturated).isTrue();
        verify(blockStreamMetrics).updateBlockBufferSaturation(100.0); // the buffer is 100% saturated
        oldestUnackedMillis = buffer.get(1L).closedTimestamp().toEpochMilli();
        verify(blockStreamMetrics).setOldestUnacknowledgedBlockTime(oldestUnackedMillis);

        // reset the block stream metrics mock to capture the next interaction that has the same value as before
        reset(blockStreamMetrics);

        assertThat(buffer).hasSize(5);

        // "overflow" the buffer
        blockBufferService.openBlock(6L);
        blockBufferService.closeBlock(6L);
        checkBufferHandle.invoke(blockBufferService);
        assertThat(lastPruningResult(blockBufferService).isSaturated).isTrue();
        verify(blockStreamMetrics).updateBlockBufferSaturation(120.0); // the buffer is 120% saturated
        oldestUnackedMillis = buffer.get(1L).closedTimestamp().toEpochMilli();
        verify(blockStreamMetrics).setOldestUnacknowledgedBlockTime(oldestUnackedMillis);
        reset(blockStreamMetrics);
        assertThat(buffer).hasSize(6);

        assertThat(blockBufferService.getEarliestAvailableBlockNumber()).isEqualTo(1L);
        // ack up to block 3
        blockBufferService.setLatestAcknowledgedBlock(3L);
        verify(blockStreamMetrics).setLatestAcknowledgedBlockNumber(3L);

        // now blocks 1-3 are acked
        assertThat(blockBufferService.isAcked(1L)).isTrue();
        assertThat(blockBufferService.isAcked(2L)).isTrue();
        assertThat(blockBufferService.isAcked(3L)).isTrue();

        // now that multiple blocks are acked, run pruning again and verify we are no longer saturated
        checkBufferHandle.invoke(blockBufferService);
        assertThat(lastPruningResult(blockBufferService).isSaturated).isFalse();
        verify(blockStreamMetrics).updateBlockBufferSaturation(60.0); // the buffer is 60% saturated
        oldestUnackedMillis = buffer.get(4L).closedTimestamp().toEpochMilli();
        verify(blockStreamMetrics).setOldestUnacknowledgedBlockTime(oldestUnackedMillis);
        reset(blockStreamMetrics);
        assertThat(buffer).hasSize(3);
        assertThat(blockBufferService.getEarliestAvailableBlockNumber()).isEqualTo(4L);

        // ack up to block 6, run pruning, and verify the buffer is not saturated
        blockBufferService.setLatestAcknowledgedBlock(6L);
        Thread.sleep(blockTtl.plusMillis(250));
        checkBufferHandle.invoke(blockBufferService);
        assertThat(lastPruningResult(blockBufferService).isSaturated).isFalse();
        verify(blockStreamMetrics).updateBlockBufferSaturation(0.0); // the buffer is 0% saturated
        verify(blockStreamMetrics).setOldestUnacknowledgedBlockTime(-1); // there is no unacked block
        reset(blockStreamMetrics);
        assertThat(buffer).isEmpty();

        // indicates that there are no blocks available in the buffer
        assertThat(blockBufferService.getEarliestAvailableBlockNumber()).isEqualTo(-1L);

        // now add another block without acking and ensure the buffer is partially saturated
        blockBufferService.openBlock(7L);
        blockBufferService.closeBlock(7L);
        checkBufferHandle.invoke(blockBufferService);
        assertThat(lastPruningResult(blockBufferService).isSaturated).isFalse();
        verify(blockStreamMetrics).updateBlockBufferSaturation(20.0); // the buffer is 20% saturated
        oldestUnackedMillis = buffer.get(7L).closedTimestamp().toEpochMilli();
        verify(blockStreamMetrics).setOldestUnacknowledgedBlockTime(oldestUnackedMillis);
        reset(blockStreamMetrics);
        assertThat(buffer).hasSize(1);
        assertThat(blockBufferService.getEarliestAvailableBlockNumber()).isEqualTo(7L);
    }

    @Test
    @Disabled("TBD if this is a valid scenario any more")
    void testFutureBlockAcked() throws Throwable {
        /*
         * There is a scenario where a block node (BN) may have a later block than what the active consensus node (CN)
         * has. For example, if a CN goes down then another CN node may send blocks to the BN. When the original
         * CN reconnects to the BN, the BN may indicate that it has later blocks from another CN.
         */

        final Duration blockTtl = Duration.ofSeconds(1);
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.blockItemBatchSize", 3)
                .withValue("blockStream.buffer.blockTtl", blockTtl)
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", false)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);

        blockBufferService.setBlockNodeConnectionManager(connectionManager);
        blockBufferService.openBlock(1L);

        // Block 1 has been added. Now lets ack up to block 5
        blockBufferService.setLatestAcknowledgedBlock(5L);

        // Since we've acked up to block 5, the block we opened _and_ any blocks we've yet to process up to 5 should
        // be considered acked
        assertThat(blockBufferService.isAcked(1L)).isTrue();
        assertThat(blockBufferService.isAcked(2L)).isTrue();
        assertThat(blockBufferService.isAcked(3L)).isTrue();
        assertThat(blockBufferService.isAcked(4L)).isTrue();
        assertThat(blockBufferService.isAcked(5L)).isTrue();
        assertThat(blockBufferService.isAcked(6L)).isFalse(); // only blocks up to 5 have been acked

        // Since we've acked up to block 5, that also means any blocks up to 5 will also be pruned as soon as they
        // expire
        // Add some more blocks, then check after pruning
        blockBufferService.openBlock(2L);
        blockBufferService.openBlock(3L);
        blockBufferService.openBlock(4L);
        blockBufferService.openBlock(5L);
        blockBufferService.openBlock(6L);

        // verify the earliest block in the buffer is 1
        assertThat(blockBufferService.getEarliestAvailableBlockNumber()).isEqualTo(1L);

        // close the blocks
        blockBufferService.closeBlock(1L);
        blockBufferService.closeBlock(2L);
        blockBufferService.closeBlock(3L);
        blockBufferService.closeBlock(4L);
        blockBufferService.closeBlock(5L);
        blockBufferService.closeBlock(6L);

        // wait for the TTL period, with a little padding
        Thread.sleep(blockTtl.plusMillis(250));

        // Add another block to trigger the prune, then verify the state... there should only be blocks 6 and 7 buffered
        blockBufferService.openBlock(7L);

        checkBufferHandle.invoke(blockBufferService);

        final ConcurrentMap<Long, BlockState> buffer = blockBuffer(blockBufferService);
        assertThat(buffer).hasSize(2);
        assertThat(buffer.get(6L)).isNotNull();
        assertThat(buffer.get(7L)).isNotNull();

        // verify the earliest block in the buffer is 6 after pruning the acked ones
        assertThat(blockBufferService.getEarliestAvailableBlockNumber()).isEqualTo(6L);
    }

    @Test
    void testBufferBackpressure() throws Throwable {
        // ensure block TTL is greater than prune interval for this test to work as expected
        final Duration blockTtl = Duration.ofSeconds(2);
        final Duration workerInterval = Duration.ofSeconds(1);
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.blockItemBatchSize", 3)
                .withValue("blockStream.buffer.blockTtl", blockTtl)
                .withValue("blockStream.buffer.workerInterval", workerInterval)
                .withValue("blockStream.writerMode", BlockStreamWriterMode.FILE_AND_GRPC)
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", false)
                .withValue("blockStream.streamMode", StreamMode.BLOCKS)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);
        blockBufferService.start();

        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicLong waitDurationMs = new AtomicLong(0L);
        final AtomicReference<Throwable> exceptionRef = new AtomicReference<>(null);

        ForkJoinPool.commonPool().execute(() -> {
            try {
                startLatch.await();

                final long start = System.currentTimeMillis();
                blockBufferService.ensureNewBlocksPermitted();
                final long durationMs = System.currentTimeMillis() - start;
                waitDurationMs.set(durationMs);
            } catch (final Exception e) {
                exceptionRef.set(e);
            } finally {
                doneLatch.countDown();
            }
        });

        // create some blocks such that the buffer will be saturated
        blockBufferService.openBlock(1L);
        blockBufferService.closeBlock(1L);
        blockBufferService.openBlock(2L);
        blockBufferService.closeBlock(2L);
        blockBufferService.openBlock(3L);
        blockBufferService.closeBlock(3L);

        assertThat(blockBufferService.getEarliestAvailableBlockNumber()).isEqualTo(1L);

        // Auto-pruning is enabled and since the prune internal is less than the block TTL, by waiting for the block TTL
        // period, plus some extra time, the pruning should detect that the buffer is saturated and enable backpressure
        Thread.sleep(blockTtl.plusMillis(250));
        // Now start the thread we spawned earlier and have this current thread sleep for a couple seconds to prove the
        // other thread is blocked
        startLatch.countDown();
        Thread.sleep(2_000);
        // ack the blocks and wait for some more time... this should allow the
        blockBufferService.setLatestAcknowledgedBlock(3L);
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
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        blockBufferService.setLatestAcknowledgedBlock(1L);
        verify(blockStreamMetrics).setLatestAcknowledgedBlockNumber(1L);
        reset(blockStreamMetrics);

        blockBufferService.setLatestAcknowledgedBlock(0L);
        verify(blockStreamMetrics).setLatestAcknowledgedBlockNumber(1L);
        reset(blockStreamMetrics);

        blockBufferService.setLatestAcknowledgedBlock(100L);
        verify(blockStreamMetrics).setLatestAcknowledgedBlockNumber(100L);
    }

    @Test
    void constructorShouldNotSchedulePruningWhenStreamingToBlockNodesDisabled() {
        // Configure streamToBlockNodes to return false
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.writerMode", BlockStreamWriterMode.FILE)
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", false)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // Create a new instance
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);

        // Get the executor service via reflection
        final ScheduledExecutorService execSvc = (ScheduledExecutorService) execSvcHandle.get(blockBufferService);

        // Verify that no tasks were scheduled (the executor should be empty)
        assertThat(execSvc.shutdownNow()).isEmpty();
    }

    @Test
    void openBlockShouldNotNotifyBlockNodeConnectionManagerWhenStreamingToBlockNodesDisabled() {
        // Configure streamToBlockNodes to return false
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.writerMode", BlockStreamWriterMode.FILE)
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", false)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // Create a new instance
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        // Call openBlock
        blockBufferService.openBlock(TEST_BLOCK_NUMBER);

        // Verify that blockNodeConnectionManager.openBlock was not called
        verify(connectionManager, never()).openBlock(TEST_BLOCK_NUMBER);
    }

    @Test
    void testBlockBufferNoBackpressureWhenStreamModeNotBlocksAndStreaming() throws Throwable {
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue("blockStream.streamMode", "BOTH")
                .withValue("blockStream.blockPeriod", Duration.ofSeconds(1))
                .withValue("blockStream.buffer.blockTtl", Duration.ofSeconds(10))
                .withValue("blockStream.buffer.pruneInterval", Duration.ZERO)
                .withValue("blockStream.buffer.actionStageThreshold", 50.0)
                .withValue("blockStream.buffer.actionGracePeriod", Duration.ofSeconds(2))
                .withValue("blockStream.buffer.recoveryThreshold", 100.0)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        // The buffer will become fully saturated after 10 blocks
        for (int i = 1; i <= 10; ++i) {
            blockBufferService.openBlock(i);
            blockBufferService.closeBlock(i);
        }

        checkBufferHandle.invoke(blockBufferService);

        final PruneResult initialPruningResult = lastPruningResult(blockBufferService);
        assertThat(initialPruningResult.isSaturated).isEqualTo(true);
        assertThat(initialPruningResult.numBlocksPruned).isZero();
        assertThat(initialPruningResult.numBlocksPendingAck).isEqualTo(10);

        // back pressure should NOT be enabled
        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef =
                backpressureCompletableFutureRef(blockBufferService);

        assertThat(backPressureFutureRef).hasNullValue();
    }

    @Test
    void testOpenBlock_streamingDisabled() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(blockStreamConfig.streamMode()).thenReturn(StreamMode.BOTH);
        when(blockBufferConfig.bufferDirectory()).thenReturn(testDir);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(blockBufferConfig);
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        final ConcurrentMap<Long, BlockState> buffer = blockBuffer(blockBufferService);

        blockBufferService.openBlock(10L);

        assertThat(buffer).isEmpty();

        verifyNoInteractions(blockStreamMetrics);
        verifyNoInteractions(connectionManager);
    }

    @Test
    void testAddItem_streamingDisabled() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(blockStreamConfig.streamMode()).thenReturn(StreamMode.BOTH);
        when(blockBufferConfig.bufferDirectory()).thenReturn(testDir);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(blockBufferConfig);
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        final ConcurrentMap<Long, BlockState> buffer = blockBuffer(blockBufferService);

        final BlockItem item = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(10L).build())
                .build();

        blockBufferService.addItem(10L, item);

        assertThat(buffer).isEmpty();

        verifyNoInteractions(blockStreamMetrics);
        verifyNoInteractions(connectionManager);
    }

    @Test
    void testCloseBlock_streamingDisabled() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(blockStreamConfig.streamMode()).thenReturn(StreamMode.BOTH);
        when(blockBufferConfig.bufferDirectory()).thenReturn(testDir);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(blockBufferConfig);
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);

        blockBufferService.closeBlock(10L);

        verifyNoInteractions(blockStreamMetrics);
        verifyNoInteractions(connectionManager);
    }

    @Test
    void testSetLatestAcknowledgedBlock_streamingDisabled() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(blockStreamConfig.streamMode()).thenReturn(StreamMode.BOTH);
        when(blockBufferConfig.bufferDirectory()).thenReturn(testDir);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(blockBufferConfig);
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);

        blockBufferService.setLatestAcknowledgedBlock(10L);

        verifyNoInteractions(blockStreamMetrics);
        verifyNoInteractions(connectionManager);
    }

    @Test
    void testEnsureNewBlocksPermitted_streamingDisabled() throws InterruptedException {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(blockStreamConfig.streamMode()).thenReturn(StreamMode.BOTH);
        when(blockBufferConfig.bufferDirectory()).thenReturn(testDir);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(blockBufferConfig);
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef =
                backpressureCompletableFutureRef(blockBufferService);

        backPressureFutureRef.set(new CompletableFuture<>());

        final CountDownLatch doneLatch = new CountDownLatch(1);

        ForkJoinPool.commonPool().execute(() -> {
            blockBufferService.ensureNewBlocksPermitted();
            doneLatch.countDown();
        });

        assertThat(doneLatch.await(1, TimeUnit.SECONDS)).isTrue();

        verifyNoInteractions(blockStreamMetrics);
        verifyNoInteractions(connectionManager);
    }

    @Test
    void testCheckBuffer_fromBelowActionStageToSaturated() throws Throwable {
        setupState(2, false);

        // 2 blocks are unacked, add 8 more to fill the buffer
        for (int i = 3; i <= 10; ++i) {
            blockBufferService.openBlock(i);
            blockBufferService.closeBlock(i);
        }

        // sleep for a couple seconds so we are beyond the "action grace period"
        Thread.sleep(2_500);

        checkBufferHandle.invoke(blockBufferService);

        final PruneResult pruneResult = lastPruningResult(blockBufferService);
        assertThat(pruneResult.isSaturated).isTrue();
        assertThat(pruneResult.numBlocksPendingAck).isEqualTo(10);

        // back pressure should be enabled
        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef =
                backpressureCompletableFutureRef(blockBufferService);
        assertThat(backPressureFutureRef).doesNotHaveNullValue();
        assertThat(backPressureFutureRef.get()).isNotCompleted();

        verify(connectionManager, times(8)).openBlock(anyLong());
        verify(connectionManager).selectNewBlockNodeForStreaming(true);
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testCheckBuffer_fromBelowActionStageToActionStage() throws Throwable {
        setupState(2, false);

        // 2 blocks are unacked, add 5 more to trigger the action stage
        for (int i = 3; i <= 7; ++i) {
            blockBufferService.openBlock(i);
            blockBufferService.closeBlock(i);
        }

        // sleep for a couple seconds so we are beyond the "action grace period"
        Thread.sleep(2_500);

        checkBufferHandle.invoke(blockBufferService);

        final PruneResult pruneResult = lastPruningResult(blockBufferService);
        assertThat(pruneResult.isSaturated).isFalse();
        assertThat(pruneResult.numBlocksPendingAck).isEqualTo(7);

        // back pressure should NOT be enabled
        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef =
                backpressureCompletableFutureRef(blockBufferService);
        assertThat(backPressureFutureRef).hasNullValue();

        verify(connectionManager, times(5)).openBlock(anyLong());
        verify(connectionManager).selectNewBlockNodeForStreaming(true);
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testCheckBuffer_fromBelowActionStageToBelowActionStage() throws Throwable {
        setupState(2, false);

        // 2 blocks are unacked, add 2 more to stay below the action stage
        for (int i = 3; i <= 4; ++i) {
            blockBufferService.openBlock(i);
            blockBufferService.closeBlock(i);
        }

        // sleep for a couple seconds so we are beyond the "action grace period"
        Thread.sleep(2_500);

        checkBufferHandle.invoke(blockBufferService);

        final PruneResult pruneResult = lastPruningResult(blockBufferService);
        assertThat(pruneResult.isSaturated).isFalse();
        assertThat(pruneResult.numBlocksPendingAck).isEqualTo(4);

        // back pressure should NOT be enabled
        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef =
                backpressureCompletableFutureRef(blockBufferService);
        assertThat(backPressureFutureRef).hasNullValue();

        verify(connectionManager, times(2)).openBlock(anyLong());
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testCheckBuffer_fromActionStageToSaturated() throws Throwable {
        setupState(7, true);

        // 7 blocks are unacked, add 3 more to fill the buffer
        blockBufferService.openBlock(8);
        blockBufferService.closeBlock(8);
        blockBufferService.openBlock(9);
        blockBufferService.closeBlock(9);
        blockBufferService.openBlock(10);
        blockBufferService.closeBlock(10);

        // sleep for a couple seconds so we are beyond the "action grace period"
        Thread.sleep(2_500);

        checkBufferHandle.invoke(blockBufferService);

        final PruneResult pruneResult = lastPruningResult(blockBufferService);
        assertThat(pruneResult.isSaturated).isTrue();
        assertThat(pruneResult.numBlocksPendingAck).isEqualTo(10);

        // back pressure should be enabled
        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef =
                backpressureCompletableFutureRef(blockBufferService);
        assertThat(backPressureFutureRef).doesNotHaveNullValue();
        assertThat(backPressureFutureRef.get()).isNotCompleted();

        verify(connectionManager, times(3)).openBlock(anyLong());
        verify(connectionManager).selectNewBlockNodeForStreaming(true);
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testCheckBuffer_fromActionStageToActionStage() throws Throwable {
        setupState(7, true);

        // 7 blocks are unacked, add 1 more but don't fill the buffer
        blockBufferService.openBlock(8);
        blockBufferService.closeBlock(8);

        // sleep for a couple seconds so we are beyond the "action grace period"
        Thread.sleep(2_500);

        checkBufferHandle.invoke(blockBufferService);

        final PruneResult pruneResult = lastPruningResult(blockBufferService);
        assertThat(pruneResult.isSaturated).isFalse();
        assertThat(pruneResult.numBlocksPendingAck).isEqualTo(8);

        // back pressure should NOT be enabled
        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef =
                backpressureCompletableFutureRef(blockBufferService);
        assertThat(backPressureFutureRef).hasNullValue();

        verify(connectionManager, times(1)).openBlock(anyLong());
        verify(connectionManager).selectNewBlockNodeForStreaming(true);
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testCheckBuffer_fromActionStageToBelowActionStage() throws Throwable {
        setupState(7, true);

        // 7 blocks are unacked, ack up to block 5 so we will fall below the action stage
        blockBufferService.setLatestAcknowledgedBlock(5L);

        // sleep for a couple seconds so we are beyond the "action grace period"
        Thread.sleep(2_500);

        checkBufferHandle.invoke(blockBufferService);

        final PruneResult pruneResult = lastPruningResult(blockBufferService);
        assertThat(pruneResult.isSaturated).isFalse();
        assertThat(pruneResult.numBlocksPendingAck).isEqualTo(2);

        // back pressure should NOT be enabled
        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef =
                backpressureCompletableFutureRef(blockBufferService);
        assertThat(backPressureFutureRef).hasNullValue();

        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testCheckBuffer_fromSaturatedToSaturated() throws Throwable {
        setupState(10, true);

        // sleep for a couple seconds so we are beyond the "action grace period"
        Thread.sleep(2_500);

        checkBufferHandle.invoke(blockBufferService);

        final PruneResult pruneResult = lastPruningResult(blockBufferService);
        assertThat(pruneResult.isSaturated).isTrue();
        assertThat(pruneResult.numBlocksPendingAck).isEqualTo(10);

        // back pressure should be enabled
        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef =
                backpressureCompletableFutureRef(blockBufferService);
        assertThat(backPressureFutureRef).doesNotHaveNullValue();
        assertThat(backPressureFutureRef.get()).isNotCompleted();

        verify(connectionManager).selectNewBlockNodeForStreaming(true);
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testCheckBuffer_fromSaturatedToActionStage() throws Throwable {
        setupState(10, true);

        // ack block 4 to be between the action stage and being saturated
        blockBufferService.setLatestAcknowledgedBlock(4);

        // sleep for a couple seconds so we are beyond the "action grace period"
        Thread.sleep(2_500);

        checkBufferHandle.invoke(blockBufferService);

        final PruneResult pruneResult = lastPruningResult(blockBufferService);
        assertThat(pruneResult.isSaturated).isFalse();
        assertThat(pruneResult.numBlocksPendingAck).isEqualTo(6);

        // back pressure should be enabled
        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef =
                backpressureCompletableFutureRef(blockBufferService);
        assertThat(backPressureFutureRef).doesNotHaveNullValue();
        final CompletableFuture<Boolean> backPressureFuture = backPressureFutureRef.get();
        assertThat(backPressureFuture).isCompleted();
        assertThat(backPressureFuture.get()).isTrue(); // back pressure is not enabled

        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testCheckBuffer_fromSaturatedToBelowActionStage() throws Throwable {
        setupState(10, true);

        // ack block 10 to allow the buffer to fall below the action stage
        blockBufferService.setLatestAcknowledgedBlock(10);

        // sleep for a couple seconds so we are beyond the "action grace period"
        Thread.sleep(2_500);

        checkBufferHandle.invoke(blockBufferService);

        final PruneResult pruneResult = lastPruningResult(blockBufferService);
        assertThat(pruneResult.isSaturated).isFalse();
        assertThat(pruneResult.numBlocksPendingAck).isZero();

        // back pressure should be enabled
        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef =
                backpressureCompletableFutureRef(blockBufferService);
        assertThat(backPressureFutureRef).doesNotHaveNullValue();
        final CompletableFuture<Boolean> backPressureFuture = backPressureFutureRef.get();
        assertThat(backPressureFuture).isCompleted();
        assertThat(backPressureFuture.get()).isTrue(); // back pressure is not enabled

        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testCheckBuffer_switchBlockNodeIfPermitted() throws Throwable {
        setupState(10, true);

        /*
        During the setup phase, a reconnect attempt would have been triggered. If we check/prune the buffer again
        immediately, another attempt will be made, but because we are still within the grace period it actually won't
        be triggered. If we wait until we are out of the grace period (2 seconds), checking the buffer again should
        trigger the reconnect.
         */

        checkBufferHandle.invoke(blockBufferService);

        verify(connectionManager, times(0)).selectNewBlockNodeForStreaming(anyBoolean());

        Thread.sleep(2_500);

        checkBufferHandle.invoke(blockBufferService);

        verify(connectionManager, times(1)).selectNewBlockNodeForStreaming(true);
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testCheckBuffer_disableBackPressureIfRecovered() throws Throwable {
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("blockStream.blockPeriod", Duration.ofSeconds(1))
                .withValue("blockStream.buffer.blockTtl", Duration.ofSeconds(10))
                .withValue("blockStream.buffer.isPruningEnabled", false)
                .withValue("blockStream.buffer.recoveryThreshold", 70.0)
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", false)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        // saturate the buffer
        for (int i = 0; i < 10; ++i) {
            blockBufferService.openBlock(i);
            blockBufferService.closeBlock(i);
        }

        checkBufferHandle.invoke(blockBufferService);

        final PruneResult pruneResult1 = lastPruningResult(blockBufferService);
        assertThat(pruneResult1.isSaturated).isTrue();
        assertThat(pruneResult1.saturationPercent).isEqualTo(100.0);

        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef1 =
                backpressureCompletableFutureRef(blockBufferService);
        assertThat(backPressureFutureRef1).doesNotHaveNullValue();
        assertThat(backPressureFutureRef1.get()).isNotCompleted();

        // ACK two blocks, which should bring us to 80% saturation... still above the recovery threshold
        blockBufferService.setLatestAcknowledgedBlock(1); // ACK blocks 0 and 1

        checkBufferHandle.invoke(blockBufferService);

        final PruneResult pruneResult2 = lastPruningResult(blockBufferService);
        assertThat(pruneResult2.isSaturated).isFalse();
        assertThat(pruneResult2.saturationPercent).isEqualTo(80.0);

        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef2 =
                backpressureCompletableFutureRef(blockBufferService);
        assertThat(backPressureFutureRef2).doesNotHaveNullValue();
        assertThat(backPressureFutureRef2.get()).isNotCompleted();

        // ACK one more block to get to the recovery threshold
        blockBufferService.setLatestAcknowledgedBlock(2);

        checkBufferHandle.invoke(blockBufferService);

        final PruneResult pruneResult3 = lastPruningResult(blockBufferService);
        assertThat(pruneResult3.isSaturated).isFalse();
        assertThat(pruneResult3.saturationPercent).isEqualTo(70.0);

        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef3 =
                backpressureCompletableFutureRef(blockBufferService);
        assertThat(backPressureFutureRef3).doesNotHaveNullValue();
        assertThat(backPressureFutureRef3.get()).isCompletedWithValue(true);
    }

    @Test
    void testStartupLoadBufferFromDisk() throws Exception {
        final int batchSize = 50;
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.blockItemBatchSize", batchSize)
                .withValue("blockStream.blockPeriod", Duration.ofSeconds(1))
                .withValue("blockStream.buffer.blockTtl", Duration.ofSeconds(10))
                .withValue("blockStream.buffer.actionStageThreshold", 50.0)
                .withValue("blockStream.buffer.actionGracePeriod", Duration.ofSeconds(2))
                .withValue("blockStream.buffer.recoveryThreshold", 100.0)
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", true)
                .withValue("blockStream.buffer.bufferDirectory", testDir)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        final File blockDir = new File(testDirFile, Long.toString(Instant.now().toEpochMilli()));
        Files.createDirectories(blockDir.toPath());
        final List<BlockState> blocks = generateRandomBlocks(10, batchSize);
        for (final BlockState block : blocks) {
            writeBlockToDisk(block, true, new File(blockDir, "block-" + block.blockNumber() + ".bin"));
        }

        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);
        blockBufferService.start();

        final ConcurrentMap<Long, BlockState> buffer = blockBuffer(blockBufferService);
        assertThat(buffer).hasSize(10);

        for (final BlockState expectedBlock : blocks) {
            final BlockState actualBlock = buffer.get(expectedBlock.blockNumber());
            assertThat(actualBlock).isNotNull();
            assertThat(actualBlock.numRequestsCreated()).isEqualTo(expectedBlock.numRequestsCreated());
            assertThat(actualBlock.closedTimestamp()).isEqualTo(expectedBlock.closedTimestamp());
            assertThat(actualBlock.isBlockProofSent()).isEqualTo(expectedBlock.isBlockProofSent());

            for (int i = 0; i < expectedBlock.numRequestsCreated(); ++i) {
                final PublishStreamRequest expectedRequest = expectedBlock.getRequest(i);
                final PublishStreamRequest actualRequest = actualBlock.getRequest(i);
                assertThat(actualRequest).isEqualTo(expectedRequest);
            }
        }
    }

    @Test
    void testStartupWithNoBlocksOnDisk() {
        final int batchSize = 50;
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.blockItemBatchSize", batchSize)
                .withValue("blockStream.blockPeriod", Duration.ofSeconds(1))
                .withValue("blockStream.buffer.blockTtl", Duration.ofSeconds(10))
                .withValue("blockStream.buffer.actionStageThreshold", 50.0)
                .withValue("blockStream.buffer.actionGracePeriod", Duration.ofSeconds(2))
                .withValue("blockStream.buffer.recoveryThreshold", 100.0)
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", true)
                .withValue("blockStream.buffer.bufferDirectory", testDir)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        final ConcurrentMap<Long, BlockState> buffer = blockBuffer(blockBufferService);
        assertThat(buffer).isEmpty();
    }

    @Test
    void testPersistBuffer() throws Throwable {
        final int batchSize = 7;
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("blockStream.blockPeriod", Duration.ofSeconds(1))
                .withValue("blockStream.buffer.blockTtl", Duration.ofSeconds(10))
                .withValue("blockStream.buffer.actionStageThreshold", 50.0)
                .withValue("blockStream.buffer.actionGracePeriod", Duration.ofSeconds(2))
                .withValue("blockStream.buffer.recoveryThreshold", 100.0)
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", true)
                .withValue("blockStream.buffer.bufferDirectory", testDir)
                .withValue("blockStream.blockItemBatchSize", batchSize)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        Files.createDirectories(testDirFile.toPath());

        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        // Setup block 1
        final long BLOCK_1 = 1L;
        blockBufferService.openBlock(BLOCK_1);
        final List<BlockItem> block1Items = generateBlockItems(10, BLOCK_1, Set.of(1L));
        block1Items.forEach(item -> blockBufferService.addItem(BLOCK_1, item));
        blockBufferService.closeBlock(BLOCK_1);
        blockBufferService.getBlockState(BLOCK_1).processPendingItems(batchSize);

        // Setup block 2
        final long BLOCK_2 = 2L;
        blockBufferService.openBlock(BLOCK_2);
        final List<BlockItem> block2Items = generateBlockItems(35, BLOCK_2, Set.of());
        block2Items.forEach(item -> blockBufferService.addItem(BLOCK_2, item));
        blockBufferService.closeBlock(BLOCK_2);
        blockBufferService.getBlockState(BLOCK_2).processPendingItems(batchSize);

        // Setup block 3
        final long BLOCK_3 = 3L;
        blockBufferService.openBlock(BLOCK_3);
        final List<BlockItem> block3Items = generateBlockItems(38, BLOCK_3, Set.of(2L, 3L, 4L));
        block3Items.forEach(item -> blockBufferService.addItem(BLOCK_3, item));
        blockBufferService.closeBlock(BLOCK_3);
        blockBufferService.getBlockState(BLOCK_3).processPendingItems(batchSize);

        // Setup block 4, don't close it
        final long BLOCK_4 = 4L;
        blockBufferService.openBlock(BLOCK_4);
        final List<BlockItem> block4Items = generateBlockItems(19, BLOCK_4, Set.of(5L, 6L));
        block4Items.forEach(item -> blockBufferService.addItem(BLOCK_4, item));
        blockBufferService.getBlockState(BLOCK_4).processPendingItems(batchSize);

        // request the buffer be persisted
        blockBufferService.persistBuffer();

        // attempt to persist the buffer... this should work for only block 1, 2, and 3 since block 4 is not closed
        persistBufferHandle.invoke(blockBufferService);

        // verify blocks 1-3 on disk
        try (final Stream<Path> stream = Files.list(testDirFile.toPath())) {
            final List<Path> subDirs = stream.toList();
            assertThat(subDirs).hasSize(1);
            final Path subDir = subDirs.getFirst();

            try (final Stream<Path> subStream = Files.list(subDir)) {
                final List<Path> files = subStream.toList();
                assertThat(files).hasSize(3);
                final Set<String> expectedFileNames =
                        new HashSet<>(Set.of("block-1.bin", "block-2.bin", "block-3.bin"));
                final Set<String> actualFileNames =
                        files.stream().map(Path::toFile).map(File::getName).collect(Collectors.toSet());
                assertThat(actualFileNames).isEqualTo(expectedFileNames);
            }
        }

        // close block 4
        blockBufferService.getBlockState(BLOCK_4).closeBlock();

        // add another block with new rounds
        final long BLOCK_5 = 5L;
        blockBufferService.openBlock(BLOCK_5);
        final List<BlockItem> block5Items = generateBlockItems(12, BLOCK_5, Set.of(7L));
        block5Items.forEach(item -> blockBufferService.addItem(BLOCK_5, item));
        blockBufferService.closeBlock(BLOCK_5);
        blockBufferService.getBlockState(BLOCK_5).processPendingItems(batchSize);

        // attempt to persist the buffer again, this time blocks 1-5 should be persisted since they are all closed
        persistBufferHandle.invoke(blockBufferService);
        try (final Stream<Path> stream = Files.list(testDirFile.toPath())) {
            final List<Path> subDirs = stream.toList();
            assertThat(subDirs).hasSize(1);
            final Path subDir = subDirs.getFirst();

            try (final Stream<Path> subStream = Files.list(subDir)) {
                final List<Path> files = subStream.toList();
                assertThat(files).hasSize(5);
                final Set<String> expectedFileNames = new HashSet<>(
                        Set.of("block-1.bin", "block-2.bin", "block-3.bin", "block-4.bin", "block-5.bin"));
                final Set<String> actualFileNames =
                        files.stream().map(Path::toFile).map(File::getName).collect(Collectors.toSet());
                assertThat(actualFileNames).isEqualTo(expectedFileNames);
            }
        }
    }

    @Test
    void testPersistBuffer_notEnabled() throws Throwable {
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("blockStream.blockPeriod", Duration.ofSeconds(1))
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", false)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        Files.createDirectories(testDirFile.toPath());

        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        // create a block
        final long BLOCK_1 = 1L;
        blockBufferService.openBlock(BLOCK_1);
        final List<BlockItem> block1Items = generateBlockItems(60, BLOCK_1, Set.of(10L, 11L));
        block1Items.forEach(item -> blockBufferService.addItem(BLOCK_1, item));
        blockBufferService.closeBlock(BLOCK_1);
        blockBufferService.getBlockState(BLOCK_1).processPendingItems(25);

        blockBufferService.persistBuffer();

        persistBufferHandle.invoke(blockBufferService);

        // verify nothing on disk
        try (final Stream<Path> stream = Files.list(testDirFile.toPath())) {
            assertThat(stream.count()).isZero();
        }
    }

    // Utilities

    void setupState(final int numBlockUnacked, final boolean reconnectExpected) throws Throwable {
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("blockStream.blockPeriod", Duration.ofSeconds(1))
                .withValue("blockStream.buffer.blockTtl", Duration.ofSeconds(10))
                .withValue("blockStream.buffer.actionStageThreshold", 50.0)
                .withValue("blockStream.buffer.actionGracePeriod", Duration.ofSeconds(2))
                .withValue("blockStream.buffer.recoveryThreshold", 100.0)
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", false)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        // the action stage is at 50%, so open+close 7 blocks but don't ack them to get above the threshold
        for (int i = 1; i <= numBlockUnacked; ++i) {
            blockBufferService.openBlock(i);
            blockBufferService.closeBlock(i);
        }

        checkBufferHandle.invoke(blockBufferService);

        final boolean expectedSaturated = numBlockUnacked == 10; // ideal max size is 10

        final PruneResult initialPruningResult = lastPruningResult(blockBufferService);
        assertThat(initialPruningResult.isSaturated).isEqualTo(expectedSaturated);
        assertThat(initialPruningResult.numBlocksPruned).isZero();
        assertThat(initialPruningResult.numBlocksPendingAck).isEqualTo(numBlockUnacked);

        // back pressure should NOT be enabled
        final AtomicReference<CompletableFuture<Boolean>> backPressureFutureRef =
                backpressureCompletableFutureRef(blockBufferService);
        if (expectedSaturated) {
            assertThat(backPressureFutureRef).doesNotHaveNullValue();
            assertThat(backPressureFutureRef.get()).isNotCompleted();
        } else {
            assertThat(backPressureFutureRef).hasNullValue();
        }

        verify(connectionManager, times(numBlockUnacked)).openBlock(anyLong());
        verify(connectionManager, times(reconnectExpected ? 1 : 0)).selectNewBlockNodeForStreaming(true);
        verifyNoMoreInteractions(connectionManager); // no other calls should be made
        reset(connectionManager);
    }

    private PruneResult lastPruningResult(final BlockBufferService bufferService) {
        return (PruneResult) lastPruningResultHandle.getVolatile(bufferService);
    }

    private AtomicLong highestAckedBlockNumber(final BlockBufferService bufferService) {
        return (AtomicLong) highestAckedBlockNumberHandle.get(bufferService);
    }

    private AtomicReference<CompletableFuture<Boolean>> backpressureCompletableFutureRef(
            final BlockBufferService bufferService) {
        return (AtomicReference<CompletableFuture<Boolean>>) backPressureFutureRefHandle.get(bufferService);
    }

    private ConcurrentMap<Long, BlockState> blockBuffer(final BlockBufferService bufferService) {
        return (ConcurrentMap<Long, BlockState>) blockBufferHandle.get(bufferService);
    }

    private static void cleanupDirectory() throws IOException {
        if (!Files.exists(testDirFile.toPath())) {
            return;
        }

        Files.walkFileTree(testDirFile.toPath(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

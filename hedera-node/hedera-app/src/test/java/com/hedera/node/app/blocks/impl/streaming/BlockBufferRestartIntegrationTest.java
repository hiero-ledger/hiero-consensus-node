// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.generateBlockItems;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Integration tests for BlockBufferService restart scenarios, covering:
 * - Buffer is restart-safe
 * - Buffer is full before restart (when the node is restarted, at least one block which is unacknowledged is >= 5 minutes old)
 * - Buffer allows successful Block Node catch-up
 */
@ExtendWith(MockitoExtension.class)
class BlockBufferRestartIntegrationTest extends BlockNodeCommunicationTestBase {

    private static final String TEST_DIR = "testBlockBufferRestart";
    private static final File TEST_DIR_FILE = new File(TEST_DIR);
    private static final Duration BLOCK_TTL = Duration.ofMinutes(5);
    private static final Duration BLOCK_PERIOD = Duration.ofSeconds(2);
    private static final int BATCH_SIZE = 10;

    // Reflection handles for accessing private fields
    private static final VarHandle blockBufferHandle;
    private static final VarHandle execSvcHandle;
    private static final VarHandle backPressureFutureRefHandle;
    private static final VarHandle isStartedHandle;
    private static final MethodHandle persistBufferHandle;
    private static final MethodHandle checkBufferHandle;

    static {
        try {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            blockBufferHandle = MethodHandles.privateLookupIn(BlockBufferService.class, lookup)
                    .findVarHandle(BlockBufferService.class, "blockBuffer", ConcurrentMap.class);
            execSvcHandle = MethodHandles.privateLookupIn(BlockBufferService.class, lookup)
                    .findVarHandle(BlockBufferService.class, "execSvc", ScheduledExecutorService.class);
            backPressureFutureRefHandle = MethodHandles.privateLookupIn(BlockBufferService.class, lookup)
                    .findVarHandle(BlockBufferService.class, "backpressureCompletableFutureRef", AtomicReference.class);
            isStartedHandle = MethodHandles.privateLookupIn(BlockBufferService.class, lookup)
                    .findVarHandle(BlockBufferService.class, "isStarted", AtomicBoolean.class);

            final var persistBufferMethod = BlockBufferService.class.getDeclaredMethod("persistBuffer");
            persistBufferMethod.setAccessible(true);
            persistBufferHandle = lookup.unreflect(persistBufferMethod);

            final var checkBufferMethod = BlockBufferService.class.getDeclaredMethod("checkBuffer");
            checkBufferMethod.setAccessible(true);
            checkBufferHandle = lookup.unreflect(checkBufferMethod);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private BlockStreamMetrics blockStreamMetrics;

    @Mock
    private BlockNodeConnectionManager connectionManager;

    private BlockBufferService blockBufferService;

    @BeforeEach
    void beforeEach() throws IOException {
        cleanupDirectory();
    }

    @AfterEach
    void afterEach() throws InterruptedException, IOException {
        if (blockBufferService != null) {
            // Clean up any background futures
            final AtomicReference<CompletableFuture<Boolean>> futureRef = backpressureCompletableFutureRef();
            final CompletableFuture<Boolean> future = futureRef.getAndSet(null);
            if (future != null) {
                future.complete(false);
            }

            // Stop the executor service
            final ScheduledExecutorService execSvc = (ScheduledExecutorService) execSvcHandle.get(blockBufferService);
            if (execSvc != null) {
                execSvc.shutdownNow();
                assertThat(execSvc.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            }
        }
        cleanupDirectory();
    }

    /**
     * Test Case: Buffer is restart-safe
     *
     * Scenario:
     * 1. CN has a few minutes of Blocks in its buffer
     * 2. Restart CN
     * 3. CN starts streaming to Block Node (simulator). Simulator responds with STREAM_ITEMS_BEHIND
     *    with block number (last verified) of some number which is x minutes behind the current block number.
     * 4. CN is able to restart stream at that block number + 1 and the Block Node can catch up from the CN.
     */
    @Test
    void testBlockBufferDurabilityWithRestart() throws Throwable {
        // Setup: Create buffer service with persistence enabled
        final Configuration config = createConfigWithPersistence();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockBufferService = initBufferService(configProvider);

        // Step 1: Create several blocks in buffer
        final int numBlocks = 10;
        final long startBlockNumber = 100L;

        for (long blockNum = startBlockNumber; blockNum < startBlockNumber + numBlocks; blockNum++) {
            blockBufferService.openBlock(blockNum);

            // Add some items to each block to make it realistic
            final List<BlockItem> items = generateBlockItems(5, blockNum, Set.of());
            long finalBlockNum = blockNum;
            items.forEach(item -> blockBufferService.addItem(finalBlockNum, item));

            blockBufferService.closeBlock(blockNum);

            // Process items to create requests (simulate streaming preparation)
            final BlockState blockState = blockBufferService.getBlockState(blockNum);
            assertThat(blockState).isNotNull();
            blockState.processPendingItems(BATCH_SIZE);
        }

        // Verify blocks are in buffer
        final ConcurrentMap<Long, BlockState> buffer = blockBuffer();
        assertThat(buffer).hasSize(numBlocks);
        assertThat(blockBufferService.getLastBlockNumberProduced()).isEqualTo(startBlockNumber + numBlocks - 1);

        // Step 2: Simulate shutdown - persist buffer to disk
        persistBufferHandle.invoke(blockBufferService);

        // Simulate service shutdown
        shutdownService();

        // Step 3: Simulate restart - create new service instance
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);
        blockBufferService.start(); // This should load buffer from disk

        // Verify buffer was restored from disk
        final ConcurrentMap<Long, BlockState> restoredBuffer = blockBuffer();
        assertThat(restoredBuffer).hasSize(numBlocks);

        // Verify all blocks were restored correctly
        for (long blockNum = startBlockNumber; blockNum < startBlockNumber + numBlocks; blockNum++) {
            final BlockState restoredBlock = blockBufferService.getBlockState(blockNum);
            assertThat(restoredBlock).isNotNull();
            assertThat(restoredBlock.blockNumber()).isEqualTo(blockNum);
            assertThat(restoredBlock.numRequestsCreated()).isGreaterThan(0);
        }

        // Step 4: Simulate Block Node responding with STREAM_ITEMS_BEHIND
        // Mock connection manager to simulate block node catching up scenario
        final long lastVerifiedBlock = startBlockNumber + 5; // Block node is 5 blocks behind

        // Simulate block node acknowledgment up to the last verified block
        blockBufferService.setLatestAcknowledgedBlock(lastVerifiedBlock);

        // Verify that blocks up to lastVerifiedBlock are marked as acknowledged
        for (long blockNum = startBlockNumber; blockNum <= lastVerifiedBlock; blockNum++) {
            assertThat(blockBufferService.isAcked(blockNum)).isTrue();
        }

        // Verify that blocks after lastVerifiedBlock are not yet acknowledged
        for (long blockNum = lastVerifiedBlock + 1; blockNum < startBlockNumber + numBlocks; blockNum++) {
            assertThat(blockBufferService.isAcked(blockNum)).isFalse();
        }

        // Step 5: Verify streaming can continue from lastVerifiedBlock + 1
        // The buffer should still contain unacknowledged blocks for streaming
        assertThat(blockBufferService.getEarliestAvailableBlockNumber()).isLessThanOrEqualTo(lastVerifiedBlock + 1);

        // Verify connection manager was notified about block openings during restoration
        verify(connectionManager, times(numBlocks)).openBlock(anyLong());
    }

    /**
     * Test Case: Buffer is full (startup scenario)
     *
     * Scenario:
     * When the node is restarted, at least one block which is unacknowledged is 5 minutes old.
     * Node will startup, attempt to stream to a block node in order to get a BlockAcknowledgement
     * which would free up space in the buffer of unacknowledged blocks before starting the platform.
     */
    @Test
    void testStartupWithFullBufferRequiresAcknowledgment() throws Throwable {
        // Setup: Create buffer service with persistence and shorter TTL for testing
        final Configuration config = createConfigWithPersistence();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockBufferService = initBufferService(configProvider);

        // Step 1: Create enough blocks to saturate the buffer
        final int maxBufferSize = (int) BLOCK_TTL.dividedBy(BLOCK_PERIOD); // Should be 150 blocks
        final long startBlockNumber = 200L;

        // Create blocks with timestamps that make some of them > 5 minutes old
        final Instant now = Instant.now();
        final Instant sixMinutesAgo = now.minus(Duration.ofMinutes(6)); // Make them older than TTL

        for (long blockNum = startBlockNumber; blockNum < startBlockNumber + maxBufferSize; blockNum++) {
            blockBufferService.openBlock(blockNum);

            final List<BlockItem> items = generateBlockItems(3, blockNum, Set.of());
            long finalBlockNum = blockNum;
            items.forEach(item -> blockBufferService.addItem(finalBlockNum, item));

            blockBufferService.closeBlock(blockNum);

            final BlockState blockState = blockBufferService.getBlockState(blockNum);
            assertThat(blockState).isNotNull();
            blockState.processPendingItems(BATCH_SIZE);

            // Make first half of blocks "old" by manipulating their timestamps
            if (blockNum < startBlockNumber + maxBufferSize / 2) {
                // Use reflection to set older timestamp (simulating blocks that are 5+ minutes old)
                setBlockTimestamp(blockState, sixMinutesAgo);
            }
        }

        // Verify buffer is full
        final ConcurrentMap<Long, BlockState> buffer = blockBuffer();
        assertThat(buffer).hasSize(maxBufferSize);

        // Step 2: Persist buffer and simulate shutdown
        persistBufferHandle.invoke(blockBufferService);
        shutdownService();

        // Step 3: Simulate restart with full buffer containing old blocks
        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);

        // Mock the connection manager to simulate that streaming must happen before platform startup
        final AtomicBoolean platformStartupBlocked = new AtomicBoolean(true);
        final CountDownLatch acknowledgmentLatch = new CountDownLatch(1);

        // Setup mock to simulate block node providing acknowledgments
        doAnswer(invocation -> {
                    // Simulate that when we try to open a block, we need acknowledgments first
                    if (platformStartupBlocked.get()) {
                        // Block until we get some acknowledgments
                        acknowledgmentLatch.await(5, TimeUnit.SECONDS);
                    }
                    return null;
                })
                .when(connectionManager)
                .openBlock(anyLong());

        // Step 4: Start the service (this should load the full buffer from disk)
        blockBufferService.start();

        // Verify buffer was restored and is saturated
        final ConcurrentMap<Long, BlockState> restoredBuffer = blockBuffer();
        assertThat(restoredBuffer).hasSize(maxBufferSize);

        // Step 5: Simulate getting acknowledgments from block node to free up buffer space
        ForkJoinPool.commonPool().execute(() -> {
            try {
                // Wait a bit to simulate the "startup blocked" scenario
                Thread.sleep(100);

                // Simulate block node acknowledging half the blocks to free up space
                final long ackedUpTo = startBlockNumber + maxBufferSize / 2;
                blockBufferService.setLatestAcknowledgedBlock(ackedUpTo);

                // Trigger buffer check to process acknowledgments and potentially enable backpressure relief
                try {
                    checkBufferHandle.invoke(blockBufferService);
                } catch (Throwable t) {
                    throw new RuntimeException("Failed to check buffer", t);
                }

                // Signal that acknowledgments have been received
                platformStartupBlocked.set(false);
                acknowledgmentLatch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Step 6: Simulate attempting to open a new block (this should be blocked initially)
        final long newBlockNumber = startBlockNumber + maxBufferSize;
        blockBufferService.openBlock(newBlockNumber);

        // Verify that acknowledgments were processed
        final long expectedAckedUpTo = startBlockNumber + maxBufferSize / 2;
        for (long blockNum = startBlockNumber; blockNum <= expectedAckedUpTo; blockNum++) {
            assertThat(blockBufferService.isAcked(blockNum)).isTrue();
        }

        // Verify that buffer contains the expected blocks after acknowledgments
        // After acknowledging half the blocks and adding one new block, we should have:
        // - Original maxBufferSize blocks + 1 new block = maxBufferSize + 1
        // - But acknowledged blocks should eventually be pruned during buffer management
        final int currentBufferSize = restoredBuffer.size();
        assertThat(currentBufferSize).isGreaterThan(maxBufferSize / 2); // Should have more than just unacked blocks
        // maxBufferSize + 1 due to a race condition between when a new block is added to the buffer and when
        // acknowledged blocks are pruned
        assertThat(currentBufferSize).isLessThanOrEqualTo(maxBufferSize + 1); // Should not grow indefinitely

        // Verify connection manager was involved in the acknowledgment process
        verify(connectionManager).openBlock(eq(newBlockNumber));
    }

    /**
     * Test Case: Block Node catch-up scenario
     *
     * Simulates the scenario where a Block Node responds with STREAM_ITEMS_BEHIND and
     * the CN needs to restart streaming from a specific block number.
     */
    @Test
    void testBlockNodeCatchUpAfterRestart() throws Throwable {
        final Configuration config = createConfigWithPersistence();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockBufferService = initBufferService(configProvider);

        // Step 1: Create blocks and simulate normal operation
        final long startBlockNumber = 300L;
        final int numBlocks = 20;

        for (long blockNum = startBlockNumber; blockNum < startBlockNumber + numBlocks; blockNum++) {
            blockBufferService.openBlock(blockNum);

            final List<BlockItem> items = generateBlockItems(4, blockNum, Set.of());
            long finalBlockNum = blockNum;
            items.forEach(item -> blockBufferService.addItem(finalBlockNum, item));

            blockBufferService.closeBlock(blockNum);
            Objects.requireNonNull(blockBufferService.getBlockState(blockNum)).processPendingItems(BATCH_SIZE);
        }

        // Step 2: Simulate partial acknowledgment (Block Node has some but not all blocks)
        final long blockNodeLastVerified = startBlockNumber + 12; // Block Node is 8 blocks behind
        blockBufferService.setLatestAcknowledgedBlock(blockNodeLastVerified);

        // Step 3: Persist and restart
        persistBufferHandle.invoke(blockBufferService);
        shutdownService();

        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        blockBufferService.setBlockNodeConnectionManager(connectionManager);
        blockBufferService.start();

        // Step 4: Simulate Block Node responding with STREAM_ITEMS_BEHIND
        // This would typically happen when the connection manager tries to stream
        // (No additional mocking needed - we're testing the buffer state directly)

        // Step 5: Verify that CN can restart streaming from the correct block
        final long nextBlockToStream = blockNodeLastVerified + 1;

        // Verify that unacknowledged blocks are available for streaming
        for (long blockNum = nextBlockToStream; blockNum < startBlockNumber + numBlocks; blockNum++) {
            final BlockState blockState = blockBufferService.getBlockState(blockNum);
            assertThat(blockState).isNotNull();
            assertThat(blockState.numRequestsCreated()).isGreaterThan(0);

            // These blocks should not be acknowledged yet
            assertThat(blockBufferService.isAcked(blockNum)).isFalse();
        }

        // Step 6: Simulate successful catch-up by acknowledging remaining blocks
        blockBufferService.setLatestAcknowledgedBlock(startBlockNumber + numBlocks - 1);

        // Verify all blocks are now acknowledged
        for (long blockNum = startBlockNumber; blockNum < startBlockNumber + numBlocks; blockNum++) {
            assertThat(blockBufferService.isAcked(blockNum)).isTrue();
        }

        // Verify connection manager was called for the unacknowledged blocks during restart
        verify(connectionManager, times(numBlocks)).openBlock(anyLong());
    }

    // Helper methods

    private Configuration createConfigWithPersistence() {
        return HederaTestConfigBuilder.create()
                .withConfigDataType(com.hedera.node.config.data.BlockStreamConfig.class)
                .withConfigDataType(com.hedera.node.config.data.BlockBufferConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("blockStream.blockPeriod", BLOCK_PERIOD)
                .withValue("blockStream.blockItemBatchSize", BATCH_SIZE)
                .withValue("blockStream.buffer.blockTtl", BLOCK_TTL)
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", true)
                .withValue("blockStream.buffer.bufferDirectory", TEST_DIR)
                .withValue("blockStream.buffer.actionStageThreshold", 50.0)
                .withValue("blockStream.buffer.actionGracePeriod", Duration.ofSeconds(2))
                .withValue("blockStream.buffer.recoveryThreshold", 70.0)
                .getOrCreateConfig();
    }

    private ConcurrentMap<Long, BlockState> blockBuffer() {
        return (ConcurrentMap<Long, BlockState>) blockBufferHandle.get(blockBufferService);
    }

    private AtomicReference<CompletableFuture<Boolean>> backpressureCompletableFutureRef() {
        return (AtomicReference<CompletableFuture<Boolean>>) backPressureFutureRefHandle.get(blockBufferService);
    }

    private void shutdownService() throws InterruptedException {
        if (blockBufferService != null) {
            final ScheduledExecutorService execSvc = (ScheduledExecutorService) execSvcHandle.get(blockBufferService);
            if (execSvc != null) {
                execSvc.shutdownNow();
                execSvc.awaitTermination(3, TimeUnit.SECONDS);
            }
        }
    }

    private void setBlockTimestamp(final BlockState blockState, final Instant timestamp) {
        try {
            // Use reflection to access the private closedTimestamp field
            final var closedTimestampField = BlockState.class.getDeclaredField("closedTimestamp");
            closedTimestampField.setAccessible(true);

            @SuppressWarnings("unchecked")
            final AtomicReference<Instant> closedTimestampRef =
                    (AtomicReference<Instant>) closedTimestampField.get(blockState);

            // Set the timestamp to simulate an "old" block
            closedTimestampRef.set(timestamp);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to set block timestamp for testing", e);
        }
    }

    private static void cleanupDirectory() throws IOException {
        if (!Files.exists(TEST_DIR_FILE.toPath())) {
            return;
        }

        Files.walkFileTree(TEST_DIR_FILE.toPath(), new SimpleFileVisitor<>() {
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

    private AtomicBoolean isStarted(final BlockBufferService bufferService) {
        return (AtomicBoolean) isStartedHandle.get(bufferService);
    }

    private BlockBufferService initBufferService(final ConfigProvider configProvider) {
        final BlockBufferService svc = new BlockBufferService(configProvider, blockStreamMetrics);
        svc.setBlockNodeConnectionManager(connectionManager);

        // "fake" starting the service
        final AtomicBoolean isStarted = isStarted(svc);
        isStarted.set(true);

        return svc;
    }
}

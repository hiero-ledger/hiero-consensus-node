// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.TransactionOutput;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockStreamStateManagerTest {

    private static final VarHandle execSvcHandle;
    private static final VarHandle blockBufferHandle;
    private static final MethodHandle checkBufferHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            blockBufferHandle = MethodHandles.privateLookupIn(BlockStreamStateManager.class, lookup)
                    .findVarHandle(BlockStreamStateManager.class, "blockBuffer", BlockingQueue.class);
            execSvcHandle = MethodHandles.privateLookupIn(BlockStreamStateManager.class, lookup)
                    .findVarHandle(BlockStreamStateManager.class, "execSvc", ScheduledExecutorService.class);
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
    private BlockNodeConnectionManager blockNodeConnectionManager;

    @Mock
    private BlockStreamMetrics blockStreamMetrics;

    private BlockStreamStateManager blockStreamStateManager;

    @BeforeEach
    void setUp() {
        lenient().when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(DEFAULT_CONFIG, 1));
    }

    @AfterEach
    void afterEach() throws InterruptedException {
        // stop the async pruning thread(s)
        final ScheduledExecutorService execSvc = (ScheduledExecutorService) execSvcHandle.get(blockStreamStateManager);
        execSvc.shutdownNow();
        assertThat(execSvc.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void testOpenNewBlock() {
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        // given
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
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
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);

        // when
        blockStreamStateManager.setLatestAcknowledgedBlock(TEST_BLOCK_NUMBER);

        // then
        // not completed states should not be removed
        assertThat(blockStreamStateManager.isAcked(TEST_BLOCK_NUMBER)).isTrue();
        final BlockState actualBlockState = blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER);
        assertThat(actualBlockState).isNotNull();
        assertFalse(actualBlockState.isComplete());
    }

    @Test
    void testCleanUp_CompletedNotExpiredBlockState_ShouldNotBeRemoved() {
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        // given
        // expiry period set to zero in order for completed state to be cleared
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).setComplete();

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
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
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
    void testPublishStreamRequestIsNotCreatedWhenBatchSizeIsNotMet() {
        // given
        // mock the number of batch items by modifying the default config
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 4)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        final var blockItem1 = newBlockHeaderItem();
        final var blockItem2 = newBlockTxItem();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem1);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem2);

        // then
        // verify that request is still not created and block items are added to state
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .isEmpty();
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).items())
                .hasSize(2);
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).items())
                .containsExactly(blockItem1, blockItem2);
    }

    @Test
    void testPublishStreamRequestIsCreatedWhenBatchSizeIsMet() {
        // given
        // mock the number of batch items by modifying the default config
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 2)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        final var blockItem1 = newBlockHeaderItem();
        final var blockItem2 = newBlockTxItem();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem1);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem2);

        // then
        // verify that only one request is created and the block state is cleared
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .hasSize(1);
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).items())
                .isEmpty();
    }

    @Test
    void testWithMoreBlockItemsThanBlockItemBatchSize() {
        // given
        // mock the number of batch items by modifying the default config
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 2)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        final var blockItem1 = newBlockHeaderItem();
        final var blockItem2 = newBlockTxItem();
        final var blockItem3 = newBlockTxItem();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem1);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem2);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem3);

        // then
        // assert that one request is created and the last block item remained in block state
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .hasSize(1);
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).items())
                .hasSize(1);
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).items())
                .containsExactly(blockItem3);
    }

    @Test
    void testPublishStreamRequestIsCreatedWithRemainingItemsAndBlockProof() {
        // given
        // mock the number of batch items by modifying the default config
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 5)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        final var blockItem1 = newBlockHeaderItem();
        final var blockItem2 = newBlockTxItem();
        final var blockProof = newBlockHeaderItem();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem1);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem2);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockProof);
        final var blockState = blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER);
        blockStreamStateManager.createRequestFromCurrentItems(blockState, true);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .hasSize(1);
    }

    @Test
    void testPublishStreamRequestIsCreatedWithBlockProofOnly() {
        // given
        // mock the number of batch items by modifying the default config
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 5)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        final var blockProof = newBlockProofItem();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockProof);
        final var blockState = blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER);
        blockStreamStateManager.createRequestFromCurrentItems(blockState, true);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .hasSize(1);
    }

    @Test
    void testPublishStreamRequestCreatedWithRemainingBlockItemsOnBlockCLose() {
        // given
        // mock the number of batch items by modifying the default config
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 5)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        final var blockItem1 = newBlockHeaderItem();
        final var blockItem2 = newBlockTxItem();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem1);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem2);
        blockStreamStateManager.closeBlock(TEST_BLOCK_NUMBER);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .hasSize(1);
        assertThat(blockStreamStateManager
                        .getBlockState(TEST_BLOCK_NUMBER)
                        .requests()
                        .getFirst()
                        .hasBlockItems())
                .isTrue();
    }

    @Test
    void testCompletedExpiredBlockStateIsRemovedUpToSpecificBlockNumber() {
        // given
        // mock the number of batch items by modifying the default config
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 5)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER2);
        blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).setComplete();
        blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER2).setComplete();

        // when
        blockStreamStateManager.setLatestAcknowledgedBlock(TEST_BLOCK_NUMBER);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).isNotNull();
        assertThat(blockStreamStateManager.isAcked(TEST_BLOCK_NUMBER)).isTrue();
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER2)).isNotNull();
        assertThat(blockStreamStateManager.isAcked(TEST_BLOCK_NUMBER2)).isFalse();
    }

    @Test
    void testPublishStreamRequestsCreatedForMultipleBLocks() {
        // given
        // mock the number of batch items by modifying the default config
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 2)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER2);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER3);
        final var blockItem1 = newBlockHeaderItem();
        final var blockItem2 = newBlockTxItem();
        final var blockItem3 = newBlockTxItem();
        final var blockItem4 = newBlockTxItem();
        final var blockItem5 = newBlockTxItem();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem1);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem2);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER2, blockItem3);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER2, blockItem4);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER3, blockItem5);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .hasSize(1);
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER2).requests())
                .hasSize(1);
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER3).requests())
                .isEmpty();
    }

    @Test
    void testGetCurrentBlockNumberWhenNoNewBlockIsOpened() {
        // given
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // when and then
        // -1 is a sentinel value indicating no block has been opened
        assertThat(blockStreamStateManager.getBlockNumber()).isEqualTo(-1);
    }

    @Test
    void testGetCurrentBlockNumberWhenNewBlockIsOpened() {
        // given
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER2);

        // when and then
        assertThat(blockStreamStateManager.getBlockNumber()).isEqualTo(TEST_BLOCK_NUMBER2);
    }

    // Negative And Edge Test Cases
    @Test
    void testOpenBlockWithNegativeBlockNumber() {
        // given
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

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
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
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
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // when and then
        assertThatThrownBy(() -> blockStreamStateManager.addItem(
                        TEST_BLOCK_NUMBER, BlockItem.newBuilder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Block state not found for block " + TEST_BLOCK_NUMBER);
    }

    @Test
    void testCloseBlockForNonExistentBlockState() {
        // given
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // when and then
        assertThatThrownBy(() -> blockStreamStateManager.closeBlock(TEST_BLOCK_NUMBER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Block state not found for block " + TEST_BLOCK_NUMBER);
    }

    @Test
    void testGetNonExistentBlockState() {
        // given
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // when and then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).isNull();
    }

    @Test
    void testStreamPreBlockProofItemsForNonExistentBlockState() {
        // given
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // when and then
        assertThatThrownBy(() -> blockStreamStateManager.streamPreBlockProofItems(TEST_BLOCK_NUMBER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Block state not found for block " + TEST_BLOCK_NUMBER);
    }

    @Test
    void testSetBlockItemBatchSizeToZero() {
        // given
        // mock the number of batch items by modifying the default config
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 0)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        final var blockItem1 = newBlockHeaderItem();
        final var blockItem2 = newBlockTxItem();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem1);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem2);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .hasSize(2);
    }

    @Test
    void testSetBlockItemBatchSizeToOne() {
        // given
        // mock the number of batch items by modifying the default config
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 1)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        final var blockItem1 = newBlockHeaderItem();
        final var blockItem2 = newBlockTxItem();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem1);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem2);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .hasSize(2);
    }

    @Test
    void testMultiBatch() {
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 3)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        assertThat(blockStreamStateManager.isBufferSaturated()).isFalse();

        // batch size is 3, add 8 items
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, newBlockHeaderItem());
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, newBlockTxItem());
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, newBlockTxItem());
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, newBlockTxItem());
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, newBlockTxItem());
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, newBlockTxItem());
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, newBlockTxItem());
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, newBlockProofItem());

        final BlockState blockState = blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER);

        // #addItem(...) will attempt to create a new request if there are enough items. With a batch size of 3, we
        // should expect 2 requests created with 2 more pending items
        assertThat(blockState.requests()).hasSize(2);
        assertThat(blockState.items()).hasSize(2);

        // now force the creation of the final request
        blockStreamStateManager.createRequestFromCurrentItems(blockState, true);

        // there should be 3 requests now and no outstanding items
        assertThat(blockState.requests()).hasSize(3);
        assertThat(blockState.items()).isEmpty();
    }

    @Test
    void testOpenExistingBlock() {
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
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
                .withValue("blockStream.blockPeriod", Duration.ofSeconds(1))
                .withValue("blockStream.blockItemBatchSize", 3)
                .withValue("blockStream.blockBufferTtl", blockTtl)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        final BlockingQueue<?> buffer = (BlockingQueue<?>) blockBufferHandle.get(blockStreamStateManager);

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
                .withValue("blockStream.blockItemBatchSize", 3)
                .withValue("blockStream.blockBufferTtl", blockTtl)
                .withValue("blockStream.blockBufferPruneInterval", Duration.ZERO) // disable auto pruning
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
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

        final BlockingQueue<?> buffer = (BlockingQueue<?>) blockBufferHandle.get(blockStreamStateManager);
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
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

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
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

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
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // Call openBlock
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);

        // Verify that blockNodeConnectionManager.openBlock was not called
        verify(blockNodeConnectionManager, never()).openBlock(TEST_BLOCK_NUMBER);
    }

    @Test
    void createRequestFromCurrentItemsShouldNotNotifyConnectionsWhenStreamingToBlockNodesDisabled() {
        // Configure streamToBlockNodes to return false
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.writerMode", BlockStreamWriterMode.FILE)
                .withValue("blockStream.blockItemBatchSize", 3)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // Create a new instance
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // Open a block and add items
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, newBlockHeaderItem());
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, newBlockTxItem());

        // Get the block state
        final BlockState blockState = blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER);

        // Call createRequestFromCurrentItems
        blockStreamStateManager.createRequestFromCurrentItems(blockState, true);

        // Verify that blockNodeConnectionManager.notifyConnectionsOfNewRequest was not called
        verify(blockNodeConnectionManager, never()).notifyConnectionsOfNewRequest();
    }

    private static BlockItem newBlockHeaderItem() {
        return BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().build())
                .build();
    }

    private static BlockItem newBlockTxItem() {
        return BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder().build())
                .build();
    }

    private static BlockItem newBlockProofItem() {
        return BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().build())
                .build();
    }
}

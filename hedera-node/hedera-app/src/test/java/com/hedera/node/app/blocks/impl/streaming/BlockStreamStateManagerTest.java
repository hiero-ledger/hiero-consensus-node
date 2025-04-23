// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
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
import com.swirlds.config.api.Configuration;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockStreamStateManagerTest {

    private static final VarHandle blockBufferHandle;

    static {
        try {
            blockBufferHandle = MethodHandles.privateLookupIn(BlockStreamStateManager.class, MethodHandles.lookup())
                    .findVarHandle(BlockStreamStateManager.class, "blockBuffer", BlockingQueue.class);
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
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
    }

    @Test
    void testOpenNewBlock() {
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
    void testCleanUpBlockState() {
        // given
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);

        // when
        blockStreamStateManager.setAckWatermark(TEST_BLOCK_NUMBER);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).isNotNull();
        assertThat(blockStreamStateManager.isAcked(TEST_BLOCK_NUMBER)).isTrue();
    }

    @Test
    void testMaintainMultipleBlockStates() {
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
    void testBLockStateIsRemovedUpToSpecificBlockNumber() {
        // given
        // mock the number of batch items by modifying the default config
        final var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 5)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER2);

        // when
        blockStreamStateManager.setAckWatermark(TEST_BLOCK_NUMBER);

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
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // when and then
        assertThat(blockStreamStateManager.getBlockNumber()).isZero();
    }

    @Test
    void testGetCurrentBlockNumberWhenNewBlockIsOpened() {
        // given
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER2);

        // when and then
        assertThat(blockStreamStateManager.getBlockNumber()).isEqualTo(TEST_BLOCK_NUMBER2);
    }

    // Negative And Edge Test Cases
    @Test
    void testOpenBlockWithNegativeBlockNumber() {
        // given
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // when and then
        assertThatThrownBy(() -> blockStreamStateManager.openBlock(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Block number must be non-negative");
        assertThat(blockStreamStateManager.getBlockNumber()).isZero();
    }

    @Test
    void testAddNullBlockItem() {
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
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // when and then
        assertThatThrownBy(() -> blockStreamStateManager.closeBlock(TEST_BLOCK_NUMBER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Block state not found for block " + TEST_BLOCK_NUMBER);
    }

    @Test
    void testGetNonExistentBlockState() {
        // given
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // when and then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).isNull();
    }

    @Test
    void testStreamPreBlockProofItemsForNonExistentBlockState() {
        // given
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
    void testBuffer() throws Exception {
        final Duration blockTtl = Duration.ofSeconds(1);
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 3)
                .withValue("blockStream.blockBufferTtl", blockTtl)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // add some blocks, but don't ack them
        blockStreamStateManager.openBlock(1L);
        assertThat(blockStreamStateManager.isBufferSaturated()).isFalse();
        blockStreamStateManager.openBlock(2L);
        assertThat(blockStreamStateManager.isBufferSaturated()).isFalse();

        // wait for the TTL period, with a little padding
        Thread.sleep(blockTtl.plusMillis(250));

        // try to add another block; it should be created but `false` will be returned indicating the buffer has old
        // blocks that can't be removed due to lack of ack
        blockStreamStateManager.openBlock(3L);
        assertThat(blockStreamStateManager.isBufferSaturated()).isTrue();

        // verify that there are three blocks in the buffer
        final BlockingQueue<?> buffer = (BlockingQueue<?>) blockBufferHandle.get(blockStreamStateManager);
        assertThat(buffer).hasSize(3);

        // ack up to block 3
        blockStreamStateManager.setAckWatermark(3L);

        // now blocks 1-3 are acked, future attempts to open another block should be successful and return `true`
        // since there are no old blocks waiting for acks
        assertThat(blockStreamStateManager.isAcked(1L)).isTrue();
        assertThat(blockStreamStateManager.isAcked(2L)).isTrue();
        assertThat(blockStreamStateManager.isAcked(3L)).isTrue();

        blockStreamStateManager.openBlock(4L);
        assertThat(blockStreamStateManager.isBufferSaturated()).isFalse();

        // there should now be 2 blocks in the buffer
        assertThat(buffer).hasSize(2);

        // only blocks 3 and 4 should be available, with block 4 yet to be acked
        assertThat(blockStreamStateManager.getBlockState(1L)).isNull();
        assertThat(blockStreamStateManager.getBlockState(2L)).isNull();
        assertThat(blockStreamStateManager.getBlockState(3L)).isNotNull();
        assertThat(blockStreamStateManager.getBlockState(4L)).isNotNull();
        assertThat(blockStreamStateManager.isAcked(3L)).isTrue();
        assertThat(blockStreamStateManager.isAcked(4L)).isFalse();
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

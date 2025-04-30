// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockStreamStateManagerTest {
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

    private static final Duration EXPIRY_PERIOD = Duration.ofMinutes(5L);

    @BeforeEach
    void setUp() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(DEFAULT_CONFIG, 1));
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics, EXPIRY_PERIOD);
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
    void testCleanUp_NotCompletedBlockState_ShouldNotBeRemoved() {
        // given
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);

        // when
        blockStreamStateManager.removeBlockStatesUpTo(TEST_BLOCK_NUMBER);

        // then
        // not completed states should not be removed
        final BlockState actualBlockState = blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER);
        assertThat(actualBlockState).isNotNull();
        assertFalse(actualBlockState.isComplete());
    }

    @Test
    void testCleanUp_CompletedNotExpiredBlockState_ShouldNotBeRemoved() {
        // given
        // expiry period set to zero in order for completed state to be cleared
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).setComplete();

        // when
        blockStreamStateManager.removeBlockStatesUpTo(TEST_BLOCK_NUMBER);

        // then
        // completed states should be removed
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).isNotNull();
    }

    @Test
    void testCleanUp_CompletedExpiredBlockState_ShouldBeRemoved() {
        // given
        // expiry period set to zero in order for completed state to be cleared
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics, Duration.ofNanos(0L));
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).setComplete();

        // when
        blockStreamStateManager.removeBlockStatesUpTo(TEST_BLOCK_NUMBER);

        // then
        // completed states should be removed
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).isNull();
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
        BlockState blockState = blockStreamStateManager.getBlockState(999L);

        // then
        assertThat(blockState).isNull();
    }

    @Test
    void testPublishStreamRequestIsNotCreatedWhenBatchSizeIsNotMet() {
        // given
        // mock the number of batch items by modifying the default config
        var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 4)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics, EXPIRY_PERIOD);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        var blockItem1 = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().build())
                .build();
        var blockItem2 = BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder().build())
                .build();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem1);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem2);

        // then
        // verify that request is still not created and block items are added to state
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .hasSize(0);
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).items())
                .hasSize(2);
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).items())
                .containsExactly(blockItem1, blockItem2);
    }

    @Test
    void testPublishStreamRequestIsCreatedWhenBatchSizeIsMet() {
        // given
        // mock the number of batch items by modifying the default config
        var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 2)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics, EXPIRY_PERIOD);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        var blockItem1 = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().build())
                .build();
        var blockItem2 = BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder().build())
                .build();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem1);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem2);

        // then
        // verify that only one request is created and the block state is cleared
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .hasSize(1);
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).items())
                .hasSize(0);
    }

    @Test
    void testWithMoreBlockItemsThanBlockItemBatchSize() {
        // given
        // mock the number of batch items by modifying the default config
        var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 2)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics, EXPIRY_PERIOD);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        var blockItem1 = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().build())
                .build();
        var blockItem2 = BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder().build())
                .build();
        var blockItem3 = BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder().build())
                .build();

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
        var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 5)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics, EXPIRY_PERIOD);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        var blockItem1 = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().build())
                .build();
        var blockItem2 = BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder().build())
                .build();
        var blockProof = BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().build())
                .build();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem1);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem2);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockProof);
        var blockState = blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER);
        blockStreamStateManager.createRequestFromCurrentItems(blockState);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .hasSize(1);
    }

    @Test
    void testPublishStreamRequestIsCreatedWithBlockProofOnly() {
        // given
        // mock the number of batch items by modifying the default config
        var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 5)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics, EXPIRY_PERIOD);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        var blockProof = BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().build())
                .build();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockProof);
        var blockState = blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER);
        blockStreamStateManager.createRequestFromCurrentItems(blockState);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .hasSize(1);
    }

    @Test
    void testPublishStreamRequestCreatedWithRemainingBlockItemsOnBlockCLose() {
        // given
        // mock the number of batch items by modifying the default config
        var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 5)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics, EXPIRY_PERIOD);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        var blockItem1 = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().build())
                .build();
        var blockItem2 = BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder().build())
                .build();

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
        var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 5)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics, Duration.ofNanos(0L));

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER2);
        blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).setComplete();
        blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER2).setComplete();

        // when
        blockStreamStateManager.removeBlockStatesUpTo(TEST_BLOCK_NUMBER);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).isNull();
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER2)).isNotNull();
    }

    @Test
    void testPublishStreamRequestsCreatedForMultipleBLocks() {
        // given
        // mock the number of batch items by modifying the default config
        var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 2)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics, EXPIRY_PERIOD);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER2);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER3);
        var blockItem1 = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().build())
                .build();
        var blockItem2 = BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder().build())
                .build();
        var blockItem3 = BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder().build())
                .build();
        var blockItem4 = BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder().build())
                .build();
        var blockItem5 = BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder().build())
                .build();

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
                .hasSize(0);
    }

    @Test
    void testGetCurrentBlockNumberWhenNoNewBlockIsOpened() {
        // given
        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // when and then
        assertThat(blockStreamStateManager.getBlockNumber()).isEqualTo(0L);
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
        assertThat(blockStreamStateManager.getBlockNumber()).isEqualTo(0L);
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
        var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 0)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics, EXPIRY_PERIOD);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        var blockItem1 = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().build())
                .build();
        var blockItem2 = BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder().build())
                .build();

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
        var mockConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockItemBatchSize", 1)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(mockConfig, 1));

        // make blockStreamStateManager use the mocked config
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics, EXPIRY_PERIOD);

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
        blockStreamStateManager.openBlock(TEST_BLOCK_NUMBER);
        var blockItem1 = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().build())
                .build();
        var blockItem2 = BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder().build())
                .build();

        // when
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem1);
        blockStreamStateManager.addItem(TEST_BLOCK_NUMBER, blockItem2);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER).requests())
                .hasSize(2);
    }
}

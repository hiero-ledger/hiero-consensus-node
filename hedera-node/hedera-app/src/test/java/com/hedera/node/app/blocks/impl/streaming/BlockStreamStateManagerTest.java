// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockStreamStateManagerTest {
    private static final long TEST_BLOCK_NUMBER = 1L;
    private static final long TEST_BLOCK_NUMBER2 = 2L;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfigImpl versionedConfig;

    @Mock
    private BlockNodeConfigExtractor blockNodeConfigExtractor;

    @Mock
    private BlockNodeConnectionManager blockNodeConnectionManager;

    private BlockStreamStateManager blockStreamStateManager;

    @BeforeEach
    void setUp() {
        blockStreamStateManager = new BlockStreamStateManager(blockNodeConfigExtractor);
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
        blockStreamStateManager.removeBlockStatesUpTo(TEST_BLOCK_NUMBER);

        // then
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
}

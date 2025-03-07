// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BlockStreamStateManagerTest {
    private static final long TEST_BLOCK_NUMBER = 1L;
    private static final long TEST_BLOCK_NUMBER2 = 2L;

    private BlockStreamStateManager blockStreamStateManager;

    @BeforeEach
    void setUp() {
        blockStreamStateManager = new BlockStreamStateManager();
    }

    @Test
    void testRegisterNewBlock() {
        // when
        blockStreamStateManager.registerBlock(TEST_BLOCK_NUMBER);

        // then
        assertAll(
                () -> assertThat(blockStreamStateManager.getCurrentBlockState()).isNotNull(),
                () -> assertThat(blockStreamStateManager.getCurrentBlockState().blockNumber())
                        .isEqualTo(TEST_BLOCK_NUMBER),
                () -> assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER))
                        .isNotNull(),
                () -> assertThat(blockStreamStateManager
                                .getBlockState(TEST_BLOCK_NUMBER)
                                .blockNumber())
                        .isEqualTo(TEST_BLOCK_NUMBER));
    }

    @Test
    void testNullifyCurrentBlockState() {
        // given
        blockStreamStateManager.registerBlock(TEST_BLOCK_NUMBER);

        // when
        blockStreamStateManager.nullifyCurrentBlockState();

        // then
        assertThat(blockStreamStateManager.getCurrentBlockState()).isNull();
    }

    @Test
    void testCleanUpBlockState() {
        // given
        blockStreamStateManager.registerBlock(TEST_BLOCK_NUMBER);

        // when
        blockStreamStateManager.cleanUpBlockState(TEST_BLOCK_NUMBER);

        // then
        assertThat(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).isNull();
    }

    @Test
    void testMaintainMultipleBlockStates() {
        // when
        blockStreamStateManager.registerBlock(TEST_BLOCK_NUMBER);
        blockStreamStateManager.registerBlock(TEST_BLOCK_NUMBER2);

        // then
        assertAll(
                () -> assertThat(blockStreamStateManager.getCurrentBlockState().blockNumber())
                        .isEqualTo(TEST_BLOCK_NUMBER2),
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
    void testHandleConcurrentOperations() throws InterruptedException {
        // given
        final int numThreads = 10;
        final int operationsPerThread = 100;
        final ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch completionLatch = new CountDownLatch(numThreads);

        try {
            // when
            IntStream.range(0, numThreads)
                    .forEach(i -> executorService.submit(() -> {
                        try {
                            startLatch.await();
                            for (int j = 0; j < operationsPerThread; j++) {
                                long blockNumber = (long) i * operationsPerThread + j;
                                blockStreamStateManager.registerBlock(blockNumber);
                                blockStreamStateManager.cleanUpBlockState(blockNumber);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        } finally {
                            completionLatch.countDown();
                        }
                    }));

            startLatch.countDown();

            // then
            assertThat(completionLatch.await(10, SECONDS)).isTrue();

            // verify results
            IntStream.range(0, numThreads).forEach(i -> {
                assertThat(blockStreamStateManager.getCurrentBlockState()).isNull();
            });
        } finally {
            executorService.shutdown();
            boolean terminated = executorService.awaitTermination(5, SECONDS);
            if (!terminated) {
                executorService.shutdownNow();
            }
        }
    }
}

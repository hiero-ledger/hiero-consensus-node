// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockStreamStateCleanUpTrackerTest {
    private static final String NODE1 = "node1:50211";
    private static final String NODE2 = "node2:50211";
    private static final String NODE3 = "node3:50211";
    private static final int REQUIRED_ACKNOWLEDGEMENTS = 2;

    @Mock
    private BlockStreamStateManager blockStreamStateManager;

    private BlockStreamStateCleanUpTracker blockStreamStateCleanUpTracker;

    @Test
    void testAcknowledgmentTrackerCreation() {
        blockStreamStateCleanUpTracker =
                new BlockStreamStateCleanUpTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, false);
        // then
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE1)).isEqualTo(0L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE2)).isEqualTo(0L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE3)).isEqualTo(0L);
    }

    @Test
    void testAckForSingleNode() {
        blockStreamStateCleanUpTracker =
                new BlockStreamStateCleanUpTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, false);

        // when
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE1, 1L);

        // then
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE1)).isEqualTo(1L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE2)).isEqualTo(0L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE3)).isEqualTo(0L);
    }

    @Test
    void testUpdateLastVerifiedBlockWhenNewAckReceived() {
        blockStreamStateCleanUpTracker =
                spy(new BlockStreamStateCleanUpTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, false));

        // when
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE1, 1L);
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE1, 2L);

        verify(blockStreamStateCleanUpTracker, times(2)).checkBlockDeletion(anyLong());
        verify(blockStreamStateCleanUpTracker, times(0)).onBlockReadyForCleanup(anyLong());
        verify(blockStreamStateManager, times(0)).cleanUpBlockState(1L);

        // then
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE1)).isEqualTo(2L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE2)).isEqualTo(0L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE3)).isEqualTo(0L);
    }

    @Test
    void testTrackerDoesNotDeleteFilesOnDiskWhenFalse() {
        blockStreamStateCleanUpTracker =
                spy(new BlockStreamStateCleanUpTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, false));
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE1, 1L);
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE2, 1L);

        verify(blockStreamStateCleanUpTracker, times(2)).checkBlockDeletion(anyLong());
        verify(blockStreamStateCleanUpTracker, times(0)).onBlockReadyForCleanup(anyLong());
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);

        // then
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE1)).isEqualTo(1L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE2)).isEqualTo(1L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE3)).isEqualTo(0L);
    }

    @Test
    void testTrackerDeleteFilesOnDiskWhenTrue() {
        blockStreamStateCleanUpTracker =
                spy(new BlockStreamStateCleanUpTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, true));
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE1, 1L);
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE2, 1L);

        verify(blockStreamStateCleanUpTracker, times(2)).checkBlockDeletion(1L);
        verify(blockStreamStateCleanUpTracker, times(1)).onBlockReadyForCleanup(1L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);

        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE1)).isEqualTo(1L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE2)).isEqualTo(1L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE3)).isEqualTo(0L);
    }

    @Test
    void testTrackerDoesNotDeleteFilesOnDiskWhenInsufficientAcks() {
        blockStreamStateCleanUpTracker =
                spy(new BlockStreamStateCleanUpTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, true));
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE1, 1L);

        verify(blockStreamStateCleanUpTracker, times(1)).checkBlockDeletion(1L);
        verify(blockStreamStateCleanUpTracker, times(0)).onBlockReadyForCleanup(1L);

        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE1)).isEqualTo(1L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE2)).isEqualTo(0L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE3)).isEqualTo(0L);
    }

    @Test
    void shouldTestDifferentBlocksForDifferentNodes() {
        blockStreamStateCleanUpTracker =
                spy(new BlockStreamStateCleanUpTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, true));

        // when
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE1, 1L);
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE2, 2L);
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE3, 3L);

        // then
        verify(blockStreamStateCleanUpTracker, times(3)).checkBlockDeletion(anyLong());
        verify(blockStreamStateCleanUpTracker, times(0)).onBlockReadyForCleanup(anyLong());
        verify(blockStreamStateManager, times(0)).cleanUpBlockState(1L);
        verify(blockStreamStateManager, times(0)).cleanUpBlockState(2L);
        verify(blockStreamStateManager, times(0)).cleanUpBlockState(3L);

        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE1)).isEqualTo(1L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE2)).isEqualTo(2L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE3)).isEqualTo(3L);
    }

    @Test
    void shouldTriggerCleanupOnlyOnceForSameBlock() {
        blockStreamStateCleanUpTracker =
                spy(new BlockStreamStateCleanUpTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, true));

        // when
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE1, 1L);
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE2, 1L);
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE3, 1L);

        // then
        verify(blockStreamStateCleanUpTracker, times(1)).onBlockReadyForCleanup(1L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);

        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE1)).isEqualTo(1L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE2)).isEqualTo(1L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE3)).isEqualTo(1L);
    }

    @Test
    void shouldHandleMultipleBlocksSimultaneously() {
        blockStreamStateCleanUpTracker =
                spy(new BlockStreamStateCleanUpTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, true));

        // when
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE1, 1L);
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE1, 2L);
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE2, 1L);
        blockStreamStateCleanUpTracker.trackBlockRecognition(NODE2, 2L);

        // then
        verify(blockStreamStateCleanUpTracker, times(1)).onBlockReadyForCleanup(1L);
        verify(blockStreamStateCleanUpTracker, times(1)).onBlockReadyForCleanup(2L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(2L);

        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE1)).isEqualTo(2L);
        assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock(NODE2)).isEqualTo(2L);
    }

    @Test
    void testHandleConcurrentAcknowledgments() throws InterruptedException, ExecutionException, TimeoutException {
        blockStreamStateCleanUpTracker =
                new BlockStreamStateCleanUpTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, true);

        // given
        final int numThreads = 10;
        final int numBlocks = 100;
        final ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final var futures = new ArrayList<Future<?>>();

        try {
            // when
            IntStream.range(0, numThreads).forEach(i -> {
                final String nodeId = "node" + i;
                futures.add(executorService.submit(() -> {
                    try {
                        for (int j = 1; j <= numBlocks; j++) {
                            blockStreamStateCleanUpTracker.trackBlockRecognition(nodeId, j);
                        }
                    } finally {
                        latch.countDown();
                    }
                }));
            });

            // then
            assertThat(latch.await(10, SECONDS)).isTrue();
            for (Future<?> future : futures) {
                future.get(1, SECONDS);
            }

            // verify results
            IntStream.range(0, numThreads).forEach(i -> {
                assertThat(blockStreamStateCleanUpTracker.getLastVerifiedBlock("node" + i))
                        .isEqualTo(numBlocks);
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

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
class BlockAcknowledgementTrackerTest {
    private static final String NODE1 = "node1:50211";
    private static final String NODE2 = "node2:50211";
    private static final String NODE3 = "node3:50211";
    private static final int REQUIRED_ACKNOWLEDGEMENTS = 2;

    @Mock
    private BlockStreamStateManager blockStreamStateManager;

    private BlockAcknowledgementTracker blockAcknowledgementTracker;

    @Test
    void testAcknowledgmentTrackerCreation() {
        blockAcknowledgementTracker =
                new BlockAcknowledgementTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, false);
        // then
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE1)).isEqualTo(0L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE2)).isEqualTo(0L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE3)).isEqualTo(0L);
    }

    @Test
    void testAckForSingleNode() {
        blockAcknowledgementTracker =
                new BlockAcknowledgementTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, false);

        // when
        blockAcknowledgementTracker.trackAcknowledgment(NODE1, 1L);

        // then
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE1)).isEqualTo(1L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE2)).isEqualTo(0L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE3)).isEqualTo(0L);
    }

    @Test
    void testUpdateLastVerifiedBlockWhenNewAckReceived() {
        blockAcknowledgementTracker =
                spy(new BlockAcknowledgementTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, false));

        // when
        blockAcknowledgementTracker.trackAcknowledgment(NODE1, 1L);
        blockAcknowledgementTracker.trackAcknowledgment(NODE1, 2L);

        verify(blockAcknowledgementTracker, times(2)).checkBlockDeletion(anyLong());
        verify(blockAcknowledgementTracker, times(0)).onBlockReadyForCleanup(anyLong());
        verify(blockStreamStateManager, times(0)).cleanUpBlockState(1L);

        // then
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE1)).isEqualTo(2L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE2)).isEqualTo(0L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE3)).isEqualTo(0L);
    }

    @Test
    void testTrackerDoesNotDeleteFilesOnDiskWhenFalse() {
        blockAcknowledgementTracker =
                spy(new BlockAcknowledgementTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, false));
        blockAcknowledgementTracker.trackAcknowledgment(NODE1, 1L);
        blockAcknowledgementTracker.trackAcknowledgment(NODE2, 1L);

        verify(blockAcknowledgementTracker, times(2)).checkBlockDeletion(anyLong());
        verify(blockAcknowledgementTracker, times(0)).onBlockReadyForCleanup(anyLong());
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);

        // then
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE1)).isEqualTo(1L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE2)).isEqualTo(1L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE3)).isEqualTo(0L);
    }

    @Test
    void testTrackerDeleteFilesOnDiskWhenTrue() {
        blockAcknowledgementTracker =
                spy(new BlockAcknowledgementTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, true));
        blockAcknowledgementTracker.trackAcknowledgment(NODE1, 1L);
        blockAcknowledgementTracker.trackAcknowledgment(NODE2, 1L);

        verify(blockAcknowledgementTracker, times(2)).checkBlockDeletion(1L);
        verify(blockAcknowledgementTracker, times(1)).onBlockReadyForCleanup(1L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);

        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE1)).isEqualTo(1L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE2)).isEqualTo(1L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE3)).isEqualTo(0L);
    }

    @Test
    void testTrackerDoesNotDeleteFilesOnDiskWhenInsufficientAcks() {
        blockAcknowledgementTracker =
                spy(new BlockAcknowledgementTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, true));
        blockAcknowledgementTracker.trackAcknowledgment(NODE1, 1L);

        verify(blockAcknowledgementTracker, times(1)).checkBlockDeletion(1L);
        verify(blockAcknowledgementTracker, times(0)).onBlockReadyForCleanup(1L);

        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE1)).isEqualTo(1L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE2)).isEqualTo(0L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE3)).isEqualTo(0L);
    }

    @Test
    void shouldTestDifferentBlocksForDifferentNodes() {
        blockAcknowledgementTracker =
                spy(new BlockAcknowledgementTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, true));

        // when
        blockAcknowledgementTracker.trackAcknowledgment(NODE1, 1L);
        blockAcknowledgementTracker.trackAcknowledgment(NODE2, 2L);
        blockAcknowledgementTracker.trackAcknowledgment(NODE3, 3L);

        // then
        verify(blockAcknowledgementTracker, times(3)).checkBlockDeletion(anyLong());
        verify(blockAcknowledgementTracker, times(0)).onBlockReadyForCleanup(anyLong());
        verify(blockStreamStateManager, times(0)).cleanUpBlockState(1L);
        verify(blockStreamStateManager, times(0)).cleanUpBlockState(2L);
        verify(blockStreamStateManager, times(0)).cleanUpBlockState(3L);

        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE1)).isEqualTo(1L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE2)).isEqualTo(2L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE3)).isEqualTo(3L);
    }

    @Test
    void shouldTriggerCleanupOnlyOnceForSameBlock() {
        blockAcknowledgementTracker =
                spy(new BlockAcknowledgementTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, true));

        // when
        blockAcknowledgementTracker.trackAcknowledgment(NODE1, 1L);
        blockAcknowledgementTracker.trackAcknowledgment(NODE2, 1L);
        blockAcknowledgementTracker.trackAcknowledgment(NODE3, 1L);

        // then
        verify(blockAcknowledgementTracker, times(1)).onBlockReadyForCleanup(1L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);

        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE1)).isEqualTo(1L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE2)).isEqualTo(1L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE3)).isEqualTo(1L);
    }

    @Test
    void shouldHandleMultipleBlocksSimultaneously() {
        blockAcknowledgementTracker =
                spy(new BlockAcknowledgementTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, true));

        // when
        blockAcknowledgementTracker.trackAcknowledgment(NODE1, 1L);
        blockAcknowledgementTracker.trackAcknowledgment(NODE1, 2L);
        blockAcknowledgementTracker.trackAcknowledgment(NODE2, 1L);
        blockAcknowledgementTracker.trackAcknowledgment(NODE2, 2L);

        // then
        verify(blockAcknowledgementTracker, times(1)).onBlockReadyForCleanup(1L);
        verify(blockAcknowledgementTracker, times(1)).onBlockReadyForCleanup(2L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(2L);

        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE1)).isEqualTo(2L);
        assertThat(blockAcknowledgementTracker.getLastVerifiedBlock(NODE2)).isEqualTo(2L);
    }

    @Test
    void testHandleConcurrentAcknowledgments() throws InterruptedException, ExecutionException, TimeoutException {
        blockAcknowledgementTracker =
                new BlockAcknowledgementTracker(blockStreamStateManager, REQUIRED_ACKNOWLEDGEMENTS, true);

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
                            blockAcknowledgementTracker.trackAcknowledgment(nodeId, j);
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
                assertThat(blockAcknowledgementTracker.getLastVerifiedBlock("node" + i))
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

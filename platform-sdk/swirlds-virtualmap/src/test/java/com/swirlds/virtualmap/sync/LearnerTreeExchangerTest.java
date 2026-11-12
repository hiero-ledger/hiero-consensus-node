// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync;

import static com.swirlds.virtualmap.MerklePathUtils.INVALID_PATH;
import static com.swirlds.virtualmap.internal.reconnect.NodeTraversalOrder.PATH_NOT_AVAILABLE_YET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapLearner;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.reconnect.NodeTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.PullVirtualTreeResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LearnerTreeExchangerTest {

    private static final long FIRST_LEAF_PATH = 7;
    private static final long LAST_LEAF_PATH = 14;

    // The callback the exchanger passes to the learner, to be called when a leaf is hashed
    private final AtomicReference<Runnable> onLeafHashed = new AtomicReference<>();

    private VirtualMapLearner learner;
    private NodeTraversalOrder traversalOrder;

    @BeforeEach
    void setUp() {
        learner = mock(VirtualMapLearner.class);
        when(learner.getOriginalMetadata()).thenReturn(new VirtualMap.Metadata());
        doAnswer(invocation -> {
                    onLeafHashed.set(invocation.getArgument(2));
                    return null;
                })
                .when(learner)
                .init(anyLong(), anyLong(), any());

        // No internal nodes to request, leaves are requested in path order
        traversalOrder = mock(NodeTraversalOrder.class);
        when(traversalOrder.getNextInternalPathToSend()).thenReturn(INVALID_PATH);
        final AtomicLong nextLeafPath = new AtomicLong(FIRST_LEAF_PATH);
        when(traversalOrder.getNextLeafPathToSend()).thenAnswer(_ -> {
            final long path = nextLeafPath.getAndIncrement();
            return (path <= LAST_LEAF_PATH) ? path : INVALID_PATH;
        });
    }

    private LearnerTreeExchanger createExchanger(final int leafBacklogThrottleThreshold) {
        final LearnerTreeExchanger exchanger = new LearnerTreeExchanger(
                learner, traversalOrder, mock(LearnerSyncMetrics.class), leafBacklogThrottleThreshold);
        exchanger.init(new PullVirtualTreeResponse(0, false, FIRST_LEAF_PATH, LAST_LEAF_PATH, null));
        return exchanger;
    }

    // Polls the exchanger till it returns a path, since the applier frees up the backlog on its own thread
    private static long awaitNextPath(final LearnerTreeExchanger exchanger) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            final long path = exchanger.getNextPathToSend();
            if (path != PATH_NOT_AVAILABLE_YET) {
                return path;
            }
            Thread.onSpinWait();
        }
        return fail("No path to send after the in-flight leaves were applied");
    }

    @Test
    @DisplayName("A throttle threshold that isn't positive is rejected")
    void nonPositiveThresholdRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LearnerTreeExchanger(learner, traversalOrder, mock(LearnerSyncMetrics.class), 0));
    }

    @Test
    @DisplayName("Leaves stop being requested while the requested ones aren't applied, and resume once they are")
    void leavesNotAppliedCountTowardsBacklog() throws InterruptedException {
        final LearnerTreeExchanger exchanger = createExchanger(2);
        assertEquals(7, exchanger.getNextPathToSend());
        assertEquals(8, exchanger.getNextPathToSend());
        assertEquals(PATH_NOT_AVAILABLE_YET, exchanger.getNextPathToSend());
        // The traversal order isn't asked for a leaf while the backlog is at the threshold, so no leaf is skipped
        verify(traversalOrder, times(2)).getNextLeafPathToSend();

        final Thread applier = new Thread(() -> exchanger.applierLoop(new CountDownLatch(0)));
        applier.start();
        try {
            exchanger.responseReceived(new PullVirtualTreeResponse(7, true, -1, -1, null));
            assertEquals(9, awaitNextPath(exchanger));
            assertEquals(PATH_NOT_AVAILABLE_YET, exchanger.getNextPathToSend());
        } finally {
            applier.interrupt();
            applier.join();
        }
    }

    @Test
    @DisplayName("Dirty leaves count towards the backlog after they are applied, till they are hashed")
    void dirtyLeavesCountTowardsBacklogTillHashed() throws InterruptedException {
        final LearnerTreeExchanger exchanger = createExchanger(2);
        assertEquals(7, exchanger.getNextPathToSend());
        assertEquals(8, exchanger.getNextPathToSend());

        final Thread applier = new Thread(() -> exchanger.applierLoop(new CountDownLatch(0)));
        applier.start();
        try {
            final VirtualLeafBytes<?> leaf = new VirtualLeafBytes<>(7, Bytes.wrap(new byte[] {7}), Bytes.EMPTY);
            exchanger.responseReceived(new PullVirtualTreeResponse(7, false, -1, -1, leaf));
            verify(learner, timeout(10_000)).dirtyLeafReceived(leaf);
            // Applied, but not hashed yet
            assertEquals(PATH_NOT_AVAILABLE_YET, exchanger.getNextPathToSend());

            onLeafHashed.get().run();
            assertEquals(9, exchanger.getNextPathToSend());
            assertEquals(PATH_NOT_AVAILABLE_YET, exchanger.getNextPathToSend());
            verify(traversalOrder, times(3)).getNextLeafPathToSend();
        } finally {
            applier.interrupt();
            applier.join();
        }
    }
}

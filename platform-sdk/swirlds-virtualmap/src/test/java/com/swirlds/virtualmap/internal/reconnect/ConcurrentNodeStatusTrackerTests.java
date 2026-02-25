// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.virtualmap.internal.ConcurrentNodeStatusTracker.Status;
import static com.swirlds.virtualmap.internal.ConcurrentNodeStatusTracker.Status.KNOWN;
import static com.swirlds.virtualmap.internal.ConcurrentNodeStatusTracker.Status.NOT_KNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.virtualmap.internal.ConcurrentNodeStatusTracker;
import com.swirlds.virtualmap.internal.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConcurrentNodeStatusTrackerTests {

    /**
     * This test validates that if the learner knows the two immediate nodes below the main
     * root, then all of its descendants up to certain value have the status set as
     * <strong>KNOWN</strong>. Currently, we just check up to 50M, but we could have selected
     * a bigger value. With 50M, we finish the test in less than 15 seconds, which is already
     * too long, but it gives us confidence on the correctness of {@link ConcurrentNodeStatusTracker}
     */
    @Test
    void createAndSetAll() throws InterruptedException, ExecutionException, TimeoutException {
        final long capacity = Integer.MAX_VALUE;
        final ConcurrentNodeStatusTracker tracker = new ConcurrentNodeStatusTracker(capacity);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<?> future = executor.submit(() -> producer(tracker, 1, 3));
        // Wait for both nodes to be set
        future.get(500, TimeUnit.MILLISECONDS);
        for (long index = 1; index < 50_000_000; index++) {
            final long finalIndex = index;
            assertEventuallyEquals(
                    KNOWN,
                    () -> tracker.getStatus(finalIndex),
                    Duration.ofSeconds(1),
                    "expected status to eventually be KNOWN");
        }
        executor.shutdown();
    }

    /**
     * This test validates that after acknowledging setting a big number, its descendants
     * have also their status as <strong>KNOWN</strong>
     *
     * @throws InterruptedException
     * 		if the checking (current) thread is interrupted
     */
    @Test
    void acknowledgeBigNumbers() throws InterruptedException, ExecutionException, TimeoutException {
        final long capacity = 4L * Integer.MAX_VALUE;
        final ConcurrentNodeStatusTracker tracker = new ConcurrentNodeStatusTracker(capacity);
        final long value = Integer.MAX_VALUE / 2 + 100_000;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<?> future = executor.submit(() -> producer(tracker, value, value + 1));
        final long leftChild = Path.getLeftChildPath(value);
        final long rightChild = Path.getRightChildPath(value);

        future.get(500, TimeUnit.MILLISECONDS);

        assertEquals(KNOWN, tracker.getStatus(leftChild), "Its parent is notified as known");
        assertEquals(KNOWN, tracker.getStatus(rightChild), "Its parent is notified as known");
        final long leftGrandChild01 = Path.getLeftChildPath(leftChild);
        final long leftGrandChild02 = Path.getRightChildPath(leftChild);
        assertEquals(KNOWN, tracker.getStatus(leftGrandChild01), "Its grandparent is notified as known");
        assertEquals(KNOWN, tracker.getStatus(leftGrandChild02), "Its grandparent is notified as known");
        final long rightGrandChild01 = Path.getLeftChildPath(rightChild);
        final long rightGrandChild02 = Path.getRightChildPath(rightChild);
        assertEquals(KNOWN, tracker.getStatus(rightGrandChild01), "Its grandparent is notified as known");
        assertEquals(KNOWN, tracker.getStatus(rightGrandChild02), "Its grandparent is notified as known");
        final long leftGrandGrandChild01 = Path.getLeftChildPath(leftGrandChild01);
        final Exception exception = assertThrows(
                IllegalArgumentException.class,
                () -> tracker.getStatus(leftGrandGrandChild01),
                "We reach the maximum capacity");
        assertEquals(
                "Value can only be between [0, 8589934588), 8590734591 is illegal",
                exception.getMessage(),
                "Validates checks doesn't go beyond the capacity");
        executor.shutdown();
    }

    /**
     * This test validates that if the learner knows the two immediate nodes below the main
     * root, then a descendant half way and the last possible descendant have a status of
     * <strong>KNOWN</strong>.
     */
    @Test
    void checksDescendant() throws ExecutionException, InterruptedException, TimeoutException {
        final long capacity = 4L * Integer.MAX_VALUE;
        final ConcurrentNodeStatusTracker tracker = new ConcurrentNodeStatusTracker(capacity);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<?> future = executor.submit(() -> producer(tracker, 1, 3));
        future.get(500, TimeUnit.MILLISECONDS);

        Status status = tracker.getStatus(2L * Integer.MAX_VALUE);
        assertEquals(KNOWN, status, "Valid descendant half way");
        status = tracker.getStatus(4L * Integer.MAX_VALUE - 1);
        assertEquals(KNOWN, status, "Last valid descendant");
        executor.shutdown();
    }

    /**
     * This test validates that if we set the last possible value,
     * no exceptions happen and the status is read as expected.
     *
     * @throws InterruptedException
     * 		if the checking (current) thread is interrupted
     */
    @Test
    void setsBoundValue() throws InterruptedException, ExecutionException, TimeoutException {
        final long capacity = Long.MAX_VALUE;
        final long value = Long.MAX_VALUE - 1;
        final ConcurrentNodeStatusTracker tracker = new ConcurrentNodeStatusTracker(capacity);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<?> future = executor.submit(() -> producer(tracker, value, value + 1));
        future.get(1, TimeUnit.SECONDS);

        assertEquals(KNOWN, tracker.getStatus(value), "The capacity - 1 is a valid value");
        executor.shutdown();
    }

    /**
     * This method simulate the receiving thread by setting a range of values to
     * status <strong>KNOWN</strong>
     *
     * @param tracker
     * 		a {@link ConcurrentNodeStatusTracker}
     * @param startIndex
     * 		start node inclusive index to set to <strong>KNOWN</strong>
     * @param endIndex
     * 		end node exclusive index to set to <strong>KNOWN</strong>
     */
    private void producer(final ConcurrentNodeStatusTracker tracker, final long startIndex, final long endIndex) {
        for (long index = startIndex; index < endIndex; index++) {
            tracker.set(index, KNOWN);
        }
    }

    /**
     * There once existed a bug where incorrect parents were computed when walking up the tree. This test
     * would fail prior to that issue being fixed.
     */
    @Test
    @DisplayName("Incorrect Parent Bug")
    void incorrectParentBug() {
        final ConcurrentNodeStatusTracker tracker = new ConcurrentNodeStatusTracker(Integer.MAX_VALUE);

        tracker.set(1, KNOWN);

        assertEquals(NOT_KNOWN, tracker.getStatus(0), "root has a child that is not known");
        assertEquals(KNOWN, tracker.getStatus(1), "this node has been marked as known");
        assertEquals(NOT_KNOWN, tracker.getStatus(2), "this node has been marked as not known");
        assertEquals(KNOWN, tracker.getStatus(3), "this node has a known ancestor");
        assertEquals(KNOWN, tracker.getStatus(4), "this node has a known ancestor");
        assertEquals(NOT_KNOWN, tracker.getStatus(5), "this node has no known ancestors");
    }
}

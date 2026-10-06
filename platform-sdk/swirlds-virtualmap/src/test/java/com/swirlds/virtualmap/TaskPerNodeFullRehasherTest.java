// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskPerNodeFullRehasherTest extends VirtualTestBase {

    @Test
    @DisplayName("Pools in async mode and shut down pools are rejected")
    void wrongPoolsRejected() {
        final ForkJoinPool asyncPool = new ForkJoinPool(2, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);
        try {
            assertThrows(IllegalArgumentException.class, () -> new TaskPerNodeFullRehasher(asyncPool));
        } finally {
            asyncPool.shutdownNow();
        }
        final ForkJoinPool shutDownPool = new ForkJoinPool(2);
        shutDownPool.shutdown();
        assertThrows(IllegalArgumentException.class, () -> new TaskPerNodeFullRehasher(shutDownPool));
    }

    @Test
    @DisplayName("Wrong leaf path range is rejected, empty tree hash is null")
    void wrongAndEmptyRanges() {
        final ForkJoinPool pool = new ForkJoinPool(2);
        try {
            final TaskPerNodeFullRehasher rehasher = new TaskPerNodeFullRehasher(pool);
            final LongFunction<VirtualLeafBytes<?>> reader = path -> {
                throw new AssertionError("Leaves must not be read, path = " + path);
            };
            assertThrows(IllegalArgumentException.class, () -> rehasher.hash(10, 5, reader, null, 60_000));
            assertNull(rehasher.hash(-1, -1, reader, null, 60_000));
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest(name = "leaves={0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 7, 8, 9, 100, 1000})
    @DisplayName("Every node is reported once, after its children, and hashes match the reference")
    void allNodesReportedAfterChildren(final int leafCount) {
        final long firstLeafPath = leafCount == 1 ? 1 : leafCount - 1L;
        final long lastLeafPath = leafCount == 1 ? 1 : 2L * leafCount - 2;
        final LongFunction<VirtualLeafBytes<?>> reader = path -> leaf(path, path, path * 31);
        final byte[][] expected = referenceHashes(firstLeafPath, lastLeafPath, reader);
        final Map<Long, byte[]> reported = new ConcurrentHashMap<>();
        final AtomicInteger orderViolations = new AtomicInteger();
        final TaskPerNodeFullRehasher.Listener listener = (path, hash) -> {
            if (path < firstLeafPath) {
                final long left = MerklePathUtils.getLeftChildPath(path);
                final long right = MerklePathUtils.getRightChildPath(path);
                if (!reported.containsKey(left) || ((right <= lastLeafPath) && !reported.containsKey(right))) {
                    orderViolations.incrementAndGet();
                }
            }
            if (reported.put(path, hash) != null) {
                orderViolations.incrementAndGet();
            }
        };
        final ForkJoinPool pool = new ForkJoinPool(8);
        try {
            final Hash rootHash =
                    new TaskPerNodeFullRehasher(pool).hash(firstLeafPath, lastLeafPath, reader, listener, 60_000);
            assertEquals(new Hash(expected[0], Cryptography.DEFAULT_DIGEST_TYPE), rootHash);
            assertEquals(0, orderViolations.get(), "Nodes must be reported once, after their children");
            assertEquals(lastLeafPath + 1, reported.size(), "All nodes must be reported");
            for (int path = 0; path <= lastLeafPath; path++) {
                assertArrayEquals(expected[path], reported.get((long) path), "Hash mismatch, path = " + path);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Root hash is the same for any parallelism")
    void rootHashIndependentOfParallelism() {
        final LongFunction<VirtualLeafBytes<?>> reader = path -> leaf(path, path, path * 31);
        final Hash expected = new Hash(referenceHashes(999, 1998, reader)[0], Cryptography.DEFAULT_DIGEST_TYPE);
        for (final int parallelism : new int[] {1, 2, 4, 16}) {
            final ForkJoinPool pool = new ForkJoinPool(parallelism);
            try {
                final Hash hash = new TaskPerNodeFullRehasher(pool).hash(999, 1998, reader, null, 60_000);
                assertEquals(expected, hash, "Root hash mismatch, parallelism = " + parallelism);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("Hashing times out and stops when leaf reads are stuck")
    void timeoutStopsHashing() throws InterruptedException {
        final ForkJoinPool pool = new ForkJoinPool(4);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean hashedAfterTimeout = new AtomicBoolean(false);
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        try {
            final LongFunction<VirtualLeafBytes<?>> blockingReader = path -> {
                try {
                    release.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return leaf(path, path, path);
            };
            final TaskPerNodeFullRehasher.Listener listener = (path, hash) -> {
                if (timedOut.get()) {
                    hashedAfterTimeout.set(true);
                }
            };
            final RuntimeException e = assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () -> assertThrows(RuntimeException.class, () -> new TaskPerNodeFullRehasher(pool)
                            .hash(999, 1998, blockingReader, listener, 100)));
            assertInstanceOf(TimeoutException.class, e.getCause());
            timedOut.set(true);
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Pool must terminate after timeout");
            assertFalse(hashedAfterTimeout.get(), "No nodes must be reported after timeout");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Hashing is stopped and the interrupted flag is restored if the caller thread is interrupted")
    void callerInterrupted() throws InterruptedException {
        final ForkJoinPool pool = new ForkJoinPool(4);
        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            final LongFunction<VirtualLeafBytes<?>> blockingReader = path -> {
                readStarted.countDown();
                try {
                    release.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return leaf(path, path, path);
            };
            final Thread caller = Thread.currentThread();
            final Thread interrupter = new Thread(() -> {
                try {
                    readStarted.await();
                } catch (final InterruptedException e) {
                    return;
                }
                caller.interrupt();
            });
            interrupter.start();
            final RuntimeException e = assertThrows(RuntimeException.class, () -> new TaskPerNodeFullRehasher(pool)
                    .hash(999, 1998, blockingReader, null, 60_000));
            assertInstanceOf(InterruptedException.class, e.getCause());
            // Also clears the flag
            assertTrue(Thread.interrupted(), "Interrupted flag must be restored");
            interrupter.join();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Hashing fails fast if a leaf can't be read")
    void leafReadFailure() {
        final ForkJoinPool pool = new ForkJoinPool(4);
        try {
            final UncheckedIOException failure = new UncheckedIOException(new IOException("test"));
            final LongFunction<VirtualLeafBytes<?>> failingReader = path -> {
                if (path == 1500) {
                    throw failure;
                }
                return leaf(path, path, path);
            };
            final RuntimeException e = assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () -> assertThrows(RuntimeException.class, () -> new TaskPerNodeFullRehasher(pool)
                            .hash(999, 1998, failingReader, null, 60_000)));
            assertSame(failure, e.getCause());

            final LongFunction<VirtualLeafBytes<?>> nullReader = path -> path == 1500 ? null : leaf(path, path, path);
            final RuntimeException e2 = assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () -> assertThrows(RuntimeException.class, () -> new TaskPerNodeFullRehasher(pool)
                            .hash(999, 1998, nullReader, null, 60_000)));
            assertInstanceOf(IllegalStateException.class, e2.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Hashing fails fast if the listener throws")
    void listenerFailure() {
        final ForkJoinPool pool = new ForkJoinPool(4);
        try {
            final IllegalStateException failure = new IllegalStateException("test");
            // Fail on an internal node, so hash tasks failures are covered, too
            final TaskPerNodeFullRehasher.Listener failingListener = (path, hash) -> {
                if (path == 100) {
                    throw failure;
                }
            };
            final RuntimeException e = assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () -> assertThrows(RuntimeException.class, () -> new TaskPerNodeFullRehasher(pool)
                            .hash(999, 1998, path -> leaf(path, path, path), failingListener, 60_000)));
            assertSame(failure, e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }
}

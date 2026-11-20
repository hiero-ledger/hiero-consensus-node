// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.throttle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StackTraceDeduplicatorTest {

    // Line numbers are a part of the stack trace signature, so "the same exception" thrown multiple times is simulated
    // by creating it from the very same line (inside a loop), while "a different exception" is created on another line.

    private static IOException createAtSiteA(final String message) {
        return new IOException(message);
    }

    private static IOException createAtSiteB(final String message) {
        return new IOException(message);
    }

    private static IOException createWithCause(final String message, final Throwable cause) {
        return new IOException(message, cause);
    }

    @Test
    void sameStackTraceIsReportedOnlyOnce() {
        final StackTraceDeduplicator deduplicator = new StackTraceDeduplicator();
        final List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(deduplicator.isNew(createAtSiteA("Connection reset")));
        }
        assertThat(results).containsExactly(true, false, false);
    }

    @Test
    void messageIsIgnored() {
        final StackTraceDeduplicator deduplicator = new StackTraceDeduplicator();
        final List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            // peer-specific details, different on every occurrence
            results.add(deduplicator.isNew(createAtSiteA("Connection reset by peer 10.0.0." + i + ":50111")));
        }
        assertThat(results).containsExactly(true, false, false);
    }

    @Test
    void differentThrowSiteIsNew() {
        final StackTraceDeduplicator deduplicator = new StackTraceDeduplicator();
        final Exception fromA = createAtSiteA("test");
        final Exception fromB = createAtSiteB("test");

        assertTrue(deduplicator.isNew(fromA));
        assertTrue(deduplicator.isNew(fromB), "a trace from a different place should be reported");
        // both are remembered now
        assertFalse(deduplicator.isNew(fromA));
        assertFalse(deduplicator.isNew(fromB));
    }

    @Test
    void differentCallerIsNew() {
        final StackTraceDeduplicator deduplicator = new StackTraceDeduplicator();
        // same creation site, but called from two different lines of this method
        assertTrue(deduplicator.isNew(createAtSiteA("test")));
        assertTrue(deduplicator.isNew(createAtSiteA("test")), "different caller line should be a new trace");
    }

    @Test
    void differentCauseIsNew() {
        final StackTraceDeduplicator deduplicator = new StackTraceDeduplicator();
        final Throwable[] causes = {null, new SocketException("cause"), null, new SocketException("cause")};
        final List<Boolean> results = new ArrayList<>();
        for (final Throwable cause : causes) {
            results.add(deduplicator.isNew(createWithCause("wrapper", cause)));
        }
        // no cause: new; with a cause: new, as the cause chain differs; no cause again: same as the first one;
        // second SocketException is created on the same line as the first one, so its trace is the same
        assertThat(results).containsExactly(true, true, false, false);
    }

    @Test
    void sameExceptionObjectIsReportedOnlyOnce() {
        final StackTraceDeduplicator deduplicator = new StackTraceDeduplicator();
        final Exception e = new IOException("test", new SocketException("cause"));
        assertTrue(deduplicator.isNew(e));
        assertFalse(deduplicator.isNew(e));
    }

    @Test
    void signatureContainsTypesButNotMessages() {
        final String signature = StackTraceDeduplicator.signature(
                new IOException("unique-message-12345", new SocketException("unique-cause-67890")));

        assertThat(signature).contains(IOException.class.getName());
        assertThat(signature).contains(SocketException.class.getName());
        assertThat(signature).contains(StackTraceDeduplicatorTest.class.getName());
        assertThat(signature).doesNotContain("unique-message-12345");
        assertThat(signature).doesNotContain("unique-cause-67890");
    }

    @Test
    void trackingStartsFromScratchWhenLimitIsReached() {
        final StackTraceDeduplicator deduplicator = new StackTraceDeduplicator(2);
        final Exception first = createAtSiteA("test");
        final Exception second = createAtSiteB("test");
        final Exception third = createWithCause("test", null);

        assertTrue(deduplicator.isNew(first));
        assertTrue(deduplicator.isNew(second));
        // limit reached: everything is forgotten, only the newest trace is remembered
        assertTrue(deduplicator.isNew(third));
        assertFalse(deduplicator.isNew(third));
        assertTrue(deduplicator.isNew(first), "a forgotten trace is reported again");
    }

    @Test
    void cyclicCausesDoNotHang() {
        final Exception a = new Exception("a");
        final Exception b = new Exception("b");
        a.initCause(b);
        b.initCause(a);

        final StackTraceDeduplicator deduplicator = new StackTraceDeduplicator();
        assertDoesNotThrow(() -> deduplicator.isNew(a));
        assertFalse(deduplicator.isNew(a));
    }

    @Test
    void invalidLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StackTraceDeduplicator(0));
        assertThrows(IllegalArgumentException.class, () -> new StackTraceDeduplicator(-1));
    }

    @Test
    void concurrentReportsOfSameTraceYieldExactlyOneNew() throws Exception {
        final int threads = 8;
        final StackTraceDeduplicator deduplicator = new StackTraceDeduplicator();
        final Exception e = new IOException("shared");
        final AtomicInteger newCount = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);

        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            final List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    if (deduplicator.isNew(e)) {
                        newCount.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (final Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(newCount.get()).isEqualTo(1);
    }
}

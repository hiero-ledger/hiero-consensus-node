// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;

public class ConcurrentThrottleTestHelper {
    private final int threads;
    private final int lifetimeSecs;
    private final int opsToRequest;

    private int lastNumAllowed = 0;

    public ConcurrentThrottleTestHelper(final int threads, final int lifetimeSecs, final int opsToRequest) {
        this.threads = threads;
        this.lifetimeSecs = lifetimeSecs;
        this.opsToRequest = opsToRequest;
    }

    public void assertTolerableTps(
            final double expectedTps, final double maxPerDeviation, final int logicalToActualTxnRatio) {
        final var actualTps = (1.0 * lastNumAllowed) / logicalToActualTxnRatio / lifetimeSecs;
        final var percentDeviation = Math.abs(1.0 - actualTps / expectedTps) * 100.0;
        Assertions.assertEquals(0.0, percentDeviation, maxPerDeviation);
    }

    public void assertTolerableTps(final double expectedTps, final double maxPerDeviation) {
        final var actualTps = (1.0 * lastNumAllowed) / lifetimeSecs;
        final var percentDeviation = Math.abs(1.0 - actualTps / expectedTps) * 100.0;
        Assertions.assertEquals(0.0, percentDeviation, maxPerDeviation);
    }

    // Suppressing the warning that we use TimeUnit.sleep
    @SuppressWarnings("java:S2925")
    public int runWith(final DeterministicThrottle subject) throws InterruptedException {
        final AtomicInteger allowed = new AtomicInteger(0);
        final AtomicBoolean stopped = new AtomicBoolean(false);

        final var ready = new CountDownLatch(threads);
        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(threads);
        final ExecutorService exec = Executors.newCachedThreadPool();

        final Instant startTime = Instant.now();
        final long startNanos = System.nanoTime();
        final long[] addNanos = new long[] {0};

        for (int i = 0; i < threads; i++) {
            exec.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    while (!stopped.get()) {
                        synchronized (subject) {

                            // We need to handle time going backwards here, which was
                            // causing tests using
                            // this to be flaky. It is possible for time to go backwards
                            // with ntp running on
                            // your system.
                            final long toAdd = System.nanoTime() - startNanos;
                            if (addNanos[0] >= toAdd) {
                                continue;
                            }

                            addNanos[0] = toAdd;

                            if (subject.allow(opsToRequest, startTime.plusNanos(addNanos[0]))) {
                                allowed.getAndAdd(opsToRequest);
                            }
                        }
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        // and:
        ready.await();
        start.countDown();
        TimeUnit.SECONDS.sleep(lifetimeSecs);
        stopped.set(true);
        done.await();

        exec.shutdown();

        return (lastNumAllowed = allowed.get());
    }
}

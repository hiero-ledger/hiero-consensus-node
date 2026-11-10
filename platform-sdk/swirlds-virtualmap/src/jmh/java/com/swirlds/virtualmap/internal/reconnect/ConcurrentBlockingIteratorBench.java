// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect; // NOSONAR: Needed to benchmark internal classes

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/// Compares the hand-off of dirty leaves from the reconnect learner's applier thread to its hashing thread:
/// [ConcurrentBlockingIterator], a lock-free MPSC ring buffer with spin-then-park waiting, against
/// [LinkedBlockingQueueIterator], the `LinkedBlockingQueue`-based version it replaced in #28079. Both run from the same
/// jar, so the baseline needs no separate build: `mpsc` and `linkedBlocking` run the same code, [#handOff], each with
/// its own implementation of [SuppliedIterator].
///
/// One pass is one reconnect's worth of hand-offs: a new iterator, [#LEAVES] leaves supplied in path order by a
/// producer thread (the applier), consumed by the benchmark thread (the hasher), then `close()`. The score is the
/// mean time per leaf (`@OperationsPerInvocation`), and `-prof gc` reports `gc.alloc.rate.norm` in bytes per leaf.
///
/// Scenarios (`scenario`) set the work per leaf on each side, in [Blackhole#consumeCPU(long)] tokens, and the buffer
/// size:
/// - `KEEPS_UP`: the hasher is faster than the applier, so the buffer stays almost empty, as in 1B-account
///   reconnects. A `LinkedBlockingQueue` signals the waiting hasher on almost every leaf there.
/// - `BURSTS`: as `KEEPS_UP`, but the applier pauses for 50µs every 1000 leaves, as when a chunk waits for the
///   teacher. The hasher runs dry and parks, so waking it up counts: a `LinkedBlockingQueue` wakes it with a signal
///   right away, the MPSC iterator only notices new leaves when its park ends, up to about 1ms later.
/// - `BEHIND`: the hasher is slower than the applier, so the buffer fills up after about 130K leaves, and the applier
///   waits for space from then on. The buffer of 2^16 leaves holds about 30ms of the hasher's work, far more than a
///   park of the waiting applier, so the hasher never runs dry because the applier wakes up late. With a buffer of
///   only 2^10 leaves it does, at least on Windows, where a park lasts a timer tick of about 1ms or more.
/// - `RAW`: no work on either side, the cost of the hand-off alone. Not realistic, an upper bound of the difference.
///
/// The applier's work of 200 tokens is meant to match its measured cost of about 0.6µs per leaf
/// (`applyMillis=307` for 500K leaves in `ReconnectHalfMillionNodesBench`). Token costs depend on the machine; check
/// that `KEEPS_UP` and `BEHIND` behave as described with `-prof gc` and the iterator sizes in a profiled run.
///
/// Allocation: `gc.alloc.rate.norm` from `-prof gc` is the right measure here, unlike the per-thread counters the
/// benchmark conventions recommend. The old iterator allocates its queue nodes on the producer thread, which a
/// per-thread counter on the benchmark thread doesn't see. The leaves are built once per trial, and a pass has no other
/// setup, so `-prof gc` counts only the hand-off: the iterator and its buffer, the nodes, and one task per pass.
///
/// This is a comparison benchmark for #28079. It measures cost per leaf and throughput, not how long a leaf waits
/// in the buffer, and it isn't tracked nightly, so it doesn't report latency percentiles through `LatencyProfiler`.
///
/// Run, with the jar from `./gradlew :swirlds-virtualmap:jmhJarWithMergedServiceFiles`:
/// ```
/// java -jar platform-sdk/swirlds-virtualmap/build/libs/swirlds-virtualmap-*-jmh-merged.jar \
///     ConcurrentBlockingIteratorBench -prof gc
/// ```
/// To run one implementation, select its method, e.g. `'ConcurrentBlockingIteratorBench\.mpsc$'`.
/// On Linux, add `-prof perfnorm:events=cycles,instructions,context-switches` for cycles and context switches per
/// leaf. The events must be listed: the default ones don't include context switches.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, jvmArgsAppend = "-Xmx4g")
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 10, time = 3)
public class ConcurrentBlockingIteratorBench {

    /// Leaves per pass, about one reconnect's worth in the reconnect benchmark. A constant, because
    /// `@OperationsPerInvocation` needs one.
    static final int LEAVES = 1_000_000;

    /// The buffer size of the learner's hashing iterator, `VirtualMapLearner.MAX_RECONNECT_HASHING_BUFFER_SIZE`.
    private static final int PRODUCTION_BUFFER_SIZE = 1 << 20;

    public enum Scenario {
        KEEPS_UP(200, 100, 0, 0, PRODUCTION_BUFFER_SIZE),
        BURSTS(200, 100, 1000, 50_000, PRODUCTION_BUFFER_SIZE),
        BEHIND(100, 200, 0, 0, 1 << 16),
        RAW(0, 0, 0, 0, PRODUCTION_BUFFER_SIZE);

        /// Work per leaf on the producer side (the applier), in tokens.
        final long supplierTokens;
        /// Work per leaf on the consumer side (the hasher), in tokens.
        final long consumerTokens;
        /// The producer pauses after every this many leaves, 0 for no pauses.
        final int pauseEvery;
        /// The producer's pause, in nanoseconds. Parks rather than spins, so the consumer runs dry.
        final long pauseNanos;
        final int bufferSize;

        Scenario(
                final long supplierTokens,
                final long consumerTokens,
                final int pauseEvery,
                final long pauseNanos,
                final int bufferSize) {
            this.supplierTokens = supplierTokens;
            this.consumerTokens = consumerTokens;
            this.pauseEvery = pauseEvery;
            this.pauseNanos = pauseNanos;
            this.bufferSize = bufferSize;
        }
    }

    @Param
    public Scenario scenario;

    /// An iterator whose elements are supplied by another thread: what both implementations have in common.
    interface SuppliedIterator<T> extends Iterator<T> {
        void supply(T element) throws InterruptedException;

        void close();
    }

    // The implementations under test, as they are: the inherited methods implement SuppliedIterator, so the
    // benchmark calls them directly

    private static final class Mpsc extends ConcurrentBlockingIterator<VirtualLeafBytes<?>>
            implements SuppliedIterator<VirtualLeafBytes<?>> {
        Mpsc(final int bufferSize) {
            super(bufferSize);
        }
    }

    private static final class LinkedBlocking extends LinkedBlockingQueueIterator<VirtualLeafBytes<?>>
            implements SuppliedIterator<VirtualLeafBytes<?>> {
        LinkedBlocking(final int bufferSize) {
            super(bufferSize);
        }
    }

    /// The leaves of a pass, in path order. The same objects in every pass.
    private VirtualLeafBytes<?>[] leaves;

    /// Runs the producer of every pass.
    private ExecutorService producer;

    /// Leaves consumed in the last pass, and whether they came in path order, checked after every pass.
    private int consumed;

    private boolean inOrder;

    @Setup(Level.Trial)
    public void setUp() {
        // The content doesn't matter to the hand-off, only that every leaf is a separate object
        final Bytes key = Bytes.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        final Bytes value = Bytes.wrap(new byte[32]);
        leaves = new VirtualLeafBytes<?>[LEAVES];
        for (int i = 0; i < LEAVES; i++) {
            leaves[i] = new VirtualLeafBytes<>(i, key, value);
        }
        producer = Executors.newSingleThreadExecutor(r -> {
            final Thread thread = new Thread(r, "applier");
            thread.setDaemon(true);
            return thread;
        });
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        producer.shutdownNow();
    }

    @Benchmark
    @OperationsPerInvocation(LEAVES)
    public void mpsc(final Blackhole bh) throws Exception {
        handOff(new Mpsc(scenario.bufferSize), bh);
    }

    @Benchmark
    @OperationsPerInvocation(LEAVES)
    public void linkedBlocking(final Blackhole bh) throws Exception {
        handOff(new LinkedBlocking(scenario.bufferSize), bh);
    }

    /// One pass: supplies all leaves on the producer thread and consumes them on this one. Every fork runs only one
    /// benchmark method, so the calls on `iterator` here only ever see one class.
    private void handOff(final SuppliedIterator<VirtualLeafBytes<?>> iterator, final Blackhole bh) throws Exception {
        final Future<?> supplying = producer.submit(() -> {
            supplyAll(iterator);
            return null;
        });

        final long consumerTokens = scenario.consumerTokens;
        int count = 0;
        boolean ordered = true;
        while (iterator.hasNext()) {
            final VirtualLeafBytes<?> leaf = iterator.next();
            ordered &= (leaf.path() == count);
            count++;
            Blackhole.consumeCPU(consumerTokens);
            bh.consume(leaf);
        }
        // Rethrows a failure of the producer
        supplying.get();
        consumed = count;
        inOrder = ordered;
    }

    private void supplyAll(final SuppliedIterator<VirtualLeafBytes<?>> iterator) throws InterruptedException {
        final long supplierTokens = scenario.supplierTokens;
        final int pauseEvery = scenario.pauseEvery;
        try {
            for (int i = 0; i < LEAVES; i++) {
                Blackhole.consumeCPU(supplierTokens);
                if ((pauseEvery > 0) && (i > 0) && (i % pauseEvery == 0)) {
                    LockSupport.parkNanos(scenario.pauseNanos);
                }
                iterator.supply(leaves[i]);
            }
        } finally {
            // Also on failure, so the consumer finishes, and the pass fails in the check below
            iterator.close();
        }
    }

    @TearDown(Level.Invocation)
    public void verifyPass() {
        if ((consumed != LEAVES) || !inOrder) {
            throw new IllegalStateException("Expected " + LEAVES + " leaves in path order, got " + consumed
                    + (inOrder ? " in order" : " out of order"));
        }
    }
}

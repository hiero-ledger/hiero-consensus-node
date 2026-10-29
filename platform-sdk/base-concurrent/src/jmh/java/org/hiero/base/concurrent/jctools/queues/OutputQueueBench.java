// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.jctools.queues;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/// Raw-queue comparison for the `AsyncOutputStream` output queue: N producers and a single consumer.
///
/// Queue implementations (`impl`): bounded [LinkedBlockingQueue] (the baseline, as on `main`),
/// [ArrayBlockingQueue], the vendored JCTools MPSC VarHandle queue ([MpscVarHandleArrayQueue]), and
/// [ConcurrentLinkedQueue] bounded by a separate counter (`CONCURRENT_LINKED`). The last one is the JDK alternative
/// that was evaluated and rejected; it isn't run by default, select it with `-PjmhParams` (see the examples below).
///
/// [ConcurrentLinkedQueue] has no capacity, but `AsyncOutputStream` relies on a full queue to stop producers when the
/// socket is slower than they are; without that, the queue grows until the heap runs out. So `CONCURRENT_LINKED`
/// pairs the queue with an [AtomicInteger] of queued messages: a producer reads it, and if there is room reserves a
/// slot by incrementing it and gives the slot back if that exceeded the capacity; the consumer decrements it after
/// each poll. This is the shape a production use would need, so the counter's cost is part of what is measured. The
/// counter is also what `AsyncOutputStream` would report as the queue size, because [ConcurrentLinkedQueue#size()]
/// walks the whole queue.
///
/// Wait semantics follow production: timed `offer`/`poll` for the blocking queues; for the MPSC and
/// `CONCURRENT_LINKED` queues non-blocking `offer`/`poll` with the same spin-then-park backoff as `AsyncOutputStream`.
///
/// Per-message work is simulated with [tokens][Blackhole#consumeCPU(long)] on both sides, so either side can
/// be made the bottleneck:
///
/// - `consumerTokens`: work the consumer does per message. `0` is a fast consumer; `2000` (a few microseconds) is a
///   slow consumer that keeps the queue full, so producers wait for space and the result is the consumer's rate.
/// - `producerTokens`: work each producer does per message before offering it. `0` makes producers as fast as
///   possible, which saturates the queue at any producer count. `1000` (one to two microseconds) is meant to keep
///   even 16 producers combined below a fast consumer, so the queue stays mostly empty and the result shows
///   contention and how quickly the consumer reacts to new messages. Token cost depends on the machine: check that
///   the `produce` rate with `producerTokens=1000` is well below the rate with `producerTokens=0` at the same
///   producer count; if it is not, the run is still consumer-bound.
///
/// Each producer count has its own group (`producers1` ... `producers16`) because `@GroupThreads` must be a
/// constant. Look at the `produce` line of a group for the number of messages offered per unit of time; the group's
/// own score is the sum of its `produce` and `consume` lines.
///
/// How to run (from the repository root). A full run takes well over half an hour; narrow it for targeted
/// comparisons and use more forks when the numbers are meant to decide something:
/// ```
/// ./gradlew :base-concurrent:jmhOutputQueue
/// ./gradlew :base-concurrent:jmhOutputQueue -PjmhProfilers=gc            # allocation rate
/// ./gradlew :base-concurrent:jmhOutputQueue -PjmhProfilers=gc,perfnorm   # Linux only: cycles, cache misses
/// ./gradlew :base-concurrent:jmhSmoke                                   # every benchmark once, briefly
/// ./gradlew :base-concurrent:jmhOutputQueue -PjmhForks=3 -PjmhGroups=producers16 "-PjmhParams=producerTokens=1000"
/// ./gradlew :base-concurrent:jmhOutputQueue "-PjmhParams=impl=MPSC_VARHANDLE,LINKED_BLOCKING;consumerTokens=0"
/// ./gradlew :base-concurrent:jmhOutputQueue "-PjmhParams=impl=MPSC_VARHANDLE,CONCURRENT_LINKED" -PjmhProfilers=gc
/// ```
@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
public class OutputQueueBench {

    private static final int CAPACITY = 16384;
    private static final byte[] PAYLOAD = new byte[200];

    /// Max wait of one producer call when the queue stays full (the production timeout is much longer).
    private static final long OFFER_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    /// Max wait of one consumer call when the queue stays empty; keeps benchmark threads able to finish.
    private static final long POLL_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    // Same backoff as AsyncOutputStream
    private static final int SPIN_LIMIT = 128;
    private static final int PRODUCER_MAX_PARK_SHIFT = 7;
    private static final int WRITER_MAX_PARK_SHIFT = 10;

    public enum Impl {
        LINKED_BLOCKING,
        ARRAY_BLOCKING,
        MPSC_VARHANDLE,
        CONCURRENT_LINKED
    }

    /// `CONCURRENT_LINKED` isn't run by default, to keep the full and smoke runs short; select it with `-PjmhParams`.
    /// With 16 producers it measured 3.6× slower than `MPSC_VARHANDLE` when producers do work, and 7.5× slower when
    /// they saturate the queue (Linux, 3 forks, see PR #27461).
    @Param({"LINKED_BLOCKING", "ARRAY_BLOCKING", "MPSC_VARHANDLE"})
    public Impl impl;

    @Param({"0", "2000"})
    public long consumerTokens;

    @Param({"0", "1000"})
    public long producerTokens;

    private BlockingQueue<byte[]> blockingQueue;
    private MessagePassingQueue<byte[]> mpscQueue;
    private ConcurrentLinkedQueue<byte[]> linkedQueue;
    /// Number of messages in [#linkedQueue], including slots reserved by producers that haven't offered yet.
    private AtomicInteger linkedQueueSize;

    @Setup(Level.Iteration)
    public void setup() {
        blockingQueue = null;
        mpscQueue = null;
        linkedQueue = null;
        linkedQueueSize = null;
        switch (impl) {
            case LINKED_BLOCKING -> blockingQueue = new LinkedBlockingQueue<>(CAPACITY);
            case ARRAY_BLOCKING -> blockingQueue = new ArrayBlockingQueue<>(CAPACITY);
            case MPSC_VARHANDLE -> mpscQueue = new MpscVarHandleArrayQueue<>(CAPACITY);
            case CONCURRENT_LINKED -> {
                linkedQueue = new ConcurrentLinkedQueue<>();
                linkedQueueSize = new AtomicInteger();
            }
        }
    }

    private void produce() throws InterruptedException {
        Blackhole.consumeCPU(producerTokens);
        if (blockingQueue != null) {
            blockingQueue.offer(PAYLOAD, OFFER_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
            return;
        }
        if (linkedQueue != null) {
            produceLinked();
            return;
        }
        if (mpscQueue.offer(PAYLOAD)) {
            return;
        }
        final long deadline = System.nanoTime() + OFFER_TIMEOUT_NANOS;
        int idle = 0;
        while (!mpscQueue.offer(PAYLOAD)) {
            if (System.nanoTime() - deadline >= 0) {
                return;
            }
            idle = backOff(idle, PRODUCER_MAX_PARK_SHIFT);
        }
    }

    // Same wait as the MPSC path, with the counter as the capacity check
    private void produceLinked() {
        if (offerLinked()) {
            return;
        }
        final long deadline = System.nanoTime() + OFFER_TIMEOUT_NANOS;
        int idle = 0;
        while (!offerLinked()) {
            if (System.nanoTime() - deadline >= 0) {
                return;
            }
            idle = backOff(idle, PRODUCER_MAX_PARK_SHIFT);
        }
    }

    // Reserves a slot first, so the queue never holds more than CAPACITY messages. The plain read before the
    // increment keeps producers retrying on a full queue from writing the counter, whose cache line the consumer
    // needs for its decrement
    private boolean offerLinked() {
        if (linkedQueueSize.get() >= CAPACITY) {
            return false;
        }
        if (linkedQueueSize.getAndIncrement() >= CAPACITY) {
            linkedQueueSize.getAndDecrement();
            return false;
        }
        return linkedQueue.offer(PAYLOAD);
    }

    private void consume(final Blackhole bh) throws InterruptedException {
        byte[] message;
        if (blockingQueue != null) {
            message = blockingQueue.poll(POLL_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
        } else if (linkedQueue != null) {
            message = linkedQueue.poll();
            if (message == null) {
                final long deadline = System.nanoTime() + POLL_TIMEOUT_NANOS;
                int idle = 0;
                while ((message = linkedQueue.poll()) == null) {
                    if (System.nanoTime() - deadline >= 0) {
                        break;
                    }
                    idle = backOff(idle, WRITER_MAX_PARK_SHIFT);
                }
            }
            if (message != null) {
                linkedQueueSize.getAndDecrement();
            }
        } else {
            // relaxedPoll(), like the AsyncOutputStream writer loop
            message = mpscQueue.relaxedPoll();
            if (message == null) {
                final long deadline = System.nanoTime() + POLL_TIMEOUT_NANOS;
                int idle = 0;
                while ((message = mpscQueue.relaxedPoll()) == null) {
                    if (System.nanoTime() - deadline >= 0) {
                        break;
                    }
                    idle = backOff(idle, WRITER_MAX_PARK_SHIFT);
                }
            }
        }
        if (message != null) {
            bh.consume(message);
            Blackhole.consumeCPU(consumerTokens);
        }
    }

    private static int backOff(final int idle, final int maxParkShift) {
        if (idle < SPIN_LIMIT) {
            Thread.onSpinWait();
        } else {
            LockSupport.parkNanos(1_000L << Math.min(idle - SPIN_LIMIT, maxParkShift));
        }
        return Math.min(idle + 1, SPIN_LIMIT + maxParkShift);
    }

    // One group per producer count, each with a single consumer.

    @Benchmark
    @Group("producers1")
    @GroupThreads(1)
    public void produce1() throws InterruptedException {
        produce();
    }

    @Benchmark
    @Group("producers1")
    @GroupThreads(1)
    public void consume1(final Blackhole bh) throws InterruptedException {
        consume(bh);
    }

    @Benchmark
    @Group("producers2")
    @GroupThreads(2)
    public void produce2() throws InterruptedException {
        produce();
    }

    @Benchmark
    @Group("producers2")
    @GroupThreads(1)
    public void consume2(final Blackhole bh) throws InterruptedException {
        consume(bh);
    }

    @Benchmark
    @Group("producers4")
    @GroupThreads(4)
    public void produce4() throws InterruptedException {
        produce();
    }

    @Benchmark
    @Group("producers4")
    @GroupThreads(1)
    public void consume4(final Blackhole bh) throws InterruptedException {
        consume(bh);
    }

    @Benchmark
    @Group("producers8")
    @GroupThreads(8)
    public void produce8() throws InterruptedException {
        produce();
    }

    @Benchmark
    @Group("producers8")
    @GroupThreads(1)
    public void consume8(final Blackhole bh) throws InterruptedException {
        consume(bh);
    }

    @Benchmark
    @Group("producers16")
    @GroupThreads(16)
    public void produce16() throws InterruptedException {
        produce();
    }

    @Benchmark
    @Group("producers16")
    @GroupThreads(1)
    public void consume16(final Blackhole bh) throws InterruptedException {
        consume(bh);
    }
}

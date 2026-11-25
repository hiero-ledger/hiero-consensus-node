// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import com.sun.management.ThreadMXBean;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.management.ManagementFactory;

/**
 * The common part of {@link LappingLatencyHistogramRecorder} and {@link LatencyHistogramRecorder}: it collects how long
 * the measured calls of one benchmark thread take during one JMH iteration, and how much memory they allocate.
 * {@link LatencyProfiler} turns these measurements into the benchmark's latency results.
 *
 * <h2>What it is for</h2>
 *
 * <p>On its own, JMH times the benchmark method as a whole and reports the average time per call. An average hides the
 * rare slow calls, which often matter most for latency. A recorder times every single call and records it in a
 * {@link LatencyHistogram}, so that {@link LatencyProfiler} can report percentiles: for example p99, the latency that
 * 99% of the calls stay at or below. It also measures the memory allocated by the measured calls, excluding the
 * benchmark's setup, and discards measurements disturbed by a garbage collection.
 *
 * <h2>The measured part of an invocation</h2>
 *
 * <p>{@link #prepareToMeasure()} and {@link #finalizeMeasurement()} enclose the measured part of one invocation of the
 * benchmark method. Within it, a subclass times calls in timed stretches: the laps of
 * {@link LappingLatencyHistogramRecorder}, or the single measurements of {@link LatencyHistogramRecorder}. Only the
 * work within a timed stretch counts in a latency and in the allocation. Each timed stretch costs two reads of the
 * allocated bytes, outside the recorded latencies.
 *
 * <p>Reading the clock takes time and has a limited resolution. On Apple Silicon, {@link System#nanoTime()} advances in
 * steps of about 42 ns, so latencies below a few microseconds are coarse on a Mac.
 *
 * <h2>Discarded invocations</h2>
 *
 * <p>A garbage collection pauses the application, and a latency containing that pause measures the garbage collector,
 * not the benchmarked code. An invocation during whose measured part a garbage collection ran is therefore discarded:
 * the recorder drops its latencies and allocation and only counts it. JMH still includes it in its primary score. A
 * collection outside the timed stretches discards the invocation too, because it can leave work for the measured
 * calls. Whether a collection falls into the measured part is non-deterministic, so a discarded invocation is not an
 * error. JMH keeps invoking the benchmark until the iteration ends, so the next invocation takes its place. The
 * allocation per call stands in for the cost of garbage collection. To make collections during the measured part rare,
 * call {@link System#gc()} at the end of the setup that runs before each invocation, and give the JVM enough heap.
 *
 * <h2>Using it</h2>
 *
 * <p>Obtain the recorder from {@link LatencyProfiler} in a setup method that runs at least once per iteration
 * ({@code Level.Iteration} or {@code Level.Invocation}). Every iteration gets new recorders, and a recorder kept from
 * an earlier iteration is no longer reported. Call {@code prepareToMeasure()} and {@code finalizeMeasurement()} once
 * per invocation, so that the profiler's counts of discarded invocations are right. Run the benchmark with
 * {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 *
 * <p>{@code finalizeMeasurement()} while a timed stretch is running throws, because it would count the allocation
 * wrongly.
 *
 * <h2>Using it on several threads</h2>
 *
 * <p>A recorder belongs to one thread and is not thread-safe, like the {@link LatencyHistogram} it records into.
 * {@link LatencyProfiler} returns a separate recorder to each calling thread. With several benchmark threads
 * ({@code @Threads}), keep the recorder in a {@code @State(Scope.Thread)} object, so that each thread uses its own.
 * Never store it in a {@code Scope.Benchmark} or {@code Scope.Group} object, where the threads would share it and lose
 * values without any error.
 *
 * <p>After the iteration, {@link LatencyProfiler} combines the recorders of all threads. JMH calls the profiler only
 * after all benchmark threads have finished, which makes their recorded values visible to it. The results then cover
 * the calls of all threads together.
 *
 * <p>Garbage collections are counted for the whole JVM, not per thread. A collection during the measured part of one
 * thread's invocation therefore also discards the invocations of the other threads whose measured parts were running at
 * the time.
 */
abstract class AbstractLatencyHistogramRecorder {

    /** The MXBean that measures the current thread's allocated bytes. */
    private static final ThreadMXBean THREAD_MX_BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** The latencies of the kept invocations. */
    private final LatencyHistogram histogram = HistogramLayout.newHistogram();

    /** The latencies of the current invocation. */
    private final LatencyHistogram invocationHistogram = HistogramLayout.newHistogram();

    /** The total bytes allocated by the timed stretches of the kept invocations. */
    private long allocatedBytes;

    /** The bytes allocated by the stopped timed stretches of the current invocation. */
    private long invocationAllocatedBytes;

    /** The bytes allocated by the current thread at the start of the running timed stretch. */
    private long allocatedBytesAtTimingStart;

    /** Whether a timed stretch is running: {@link #startTiming()} was called, and {@link #stopTiming()} not since. */
    private boolean timing;

    /** The number of garbage collections in the JVM at the start of the current invocation's measured part. */
    private long gcCountAtStart;

    /** The number of invocations discarded because a garbage collection ran during their measured part. */
    private long discardedInvocations;

    AbstractLatencyHistogramRecorder() {}

    /**
     * Starts the measured part of an invocation: watches for garbage collections from now on. Call it once per
     * invocation, at the start of the benchmark method.
     */
    public final void prepareToMeasure() {
        invocationHistogram.reset();
        invocationAllocatedBytes = 0;
        timing = false;
        gcCountAtStart = GarbageCollections.count();
    }

    /**
     * Ends the measured part started by {@link #prepareToMeasure()}. Call it at the end of the benchmark method. Keeps
     * the invocation's latencies and allocation if no garbage collection ran during the measured part, and discards
     * them otherwise.
     *
     * @throws IllegalStateException if a timed stretch is running
     */
    public final void finalizeMeasurement() {
        if (timing) {
            throw new IllegalStateException("Started; stop it before finalizeMeasurement()");
        }
        if (GarbageCollections.count() == gcCountAtStart) {
            histogram.add(invocationHistogram);
            allocatedBytes += invocationAllocatedBytes;
        } else {
            discardedInvocations++;
        }
    }

    /**
     * Starts a timed stretch: counts the allocation of the current thread from now on. The subclass reads the clock
     * right after it, so that the read here is not part of the first latency.
     *
     * @throws IllegalStateException if a timed stretch is running already
     */
    final void startTiming() {
        if (timing) {
            throw new IllegalStateException("Started already");
        }
        timing = true;
        allocatedBytesAtTimingStart = THREAD_MX_BEAN.getCurrentThreadAllocatedBytes();
    }

    /**
     * Stops the running timed stretch: adds what the current thread allocated since {@link #startTiming()} to the
     * current invocation.
     *
     * @throws IllegalStateException if no timed stretch is running
     */
    final void stopTiming() {
        if (!timing) {
            throw new IllegalStateException("Not started");
        }
        timing = false;
        invocationAllocatedBytes += THREAD_MX_BEAN.getCurrentThreadAllocatedBytes() - allocatedBytesAtTimingStart;
    }

    /**
     * Records the latency of one call of the current invocation.
     *
     * @param latencyNanos the latency in nanoseconds
     */
    final void record(final long latencyNanos) {
        invocationHistogram.record(latencyNanos);
    }

    /**
     * Returns the histogram of the kept invocations. The caller may read it, but must not modify it.
     *
     * @return the histogram of the kept invocations
     */
    @NonNull
    final LatencyHistogram histogram() {
        return histogram;
    }

    /**
     * Returns the total bytes allocated by the timed stretches of the kept invocations.
     *
     * @return the total bytes allocated by the timed stretches of the kept invocations
     */
    final long allocatedBytes() {
        return allocatedBytes;
    }

    /**
     * Returns the number of invocations discarded because a garbage collection ran during their measured part.
     *
     * @return the number of discarded invocations
     */
    final long discardedInvocations() {
        return discardedInvocations;
    }
}

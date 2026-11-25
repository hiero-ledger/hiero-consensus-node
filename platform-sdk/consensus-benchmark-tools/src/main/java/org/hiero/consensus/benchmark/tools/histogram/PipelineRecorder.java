// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import com.sun.management.ThreadMXBean;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.management.ManagementFactory;

/**
 * Collects the latencies and the allocation of the invocations of a pipeline benchmark during one JMH iteration.
 * {@link LatencyProfiler} turns them into the benchmark's latency results, as it does with the measurements of a
 * {@link LatencyHistogramRecorder}.
 *
 * <h2>What it is for</h2>
 *
 * <p>A pipeline hands its inputs on between threads: a value enters on one thread and leaves on another, thus it is not
 * possible to measure the latency on a single thread. A timer such as
 * {@link org.hiero.consensus.benchmark.tools.pipeline.PipelineTimer PipelineTimer} computes each latency from the time
 * its input was due and the time the value arrived, and records it here. The pipeline allocates on its own threads, so
 * the recorder counts the bytes that all threads of the JVM allocate during the measured part of an invocation.
 *
 * <h2>The measured part of an invocation</h2>
 *
 * <pre>
 * prepareToMeasure()             reads the allocated bytes of all threads and the number of garbage collections
 * replay the inputs and drain the pipeline
 * record(latency)                once for every value that arrived
 * finalizeMeasurement()          reads both again
 * discardSaturatedInvocation()   only if the pipeline did not keep up
 * </pre>
 *
 * <p>An invocation is kept once its measured part has ended, unless it is discarded. Neither discard is an error: the
 * next invocation takes the discarded one's place. A discarded invocation's latencies and allocation are dropped; JMH
 * still includes it in its primary score.
 *
 * <ul>
 *   <li>{@link #finalizeMeasurement()} discards an invocation during whose measured part a garbage collection ran, as
 *       {@link LatencyHistogramRecorder} does: the collection pauses every thread of the pipeline, and the pause would
 *       disturb the latencies of one invocation at random. The allocation per value stands in for the cost of garbage
 *       collection.</li>
 *   <li>{@link #discardSaturatedInvocation()} discards an invocation in which the pipeline did not keep up, whose
 *       latencies grew with the length of the measured part. The two are counted independently: an invocation with a
 *       collection in which the pipeline also did not keep up counts in both.</li>
 * </ul>
 *
 * <h2>Using it</h2>
 *
 * <p>Obtain the recorder with {@link LatencyProfiler#pipelineRecorder()} in a setup method that runs at least once per
 * iteration ({@code Level.Iteration} or {@code Level.Invocation}). Every iteration gets a new recorder. Call
 * {@code prepareToMeasure()} and {@code finalizeMeasurement()} once per invocation. The allocated bytes and the
 * collections are counted for the whole JVM, so the measured parts must not overlap: an iteration has one pipeline
 * recorder, for a benchmark that runs on one thread, and its methods throw when they are called out of order.
 */
public final class PipelineRecorder {

    /** The MXBean that measures the bytes allocated by all threads. */
    private static final ThreadMXBean THREAD_MX_BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** Where the latest invocation stands. */
    private enum State {
        /** No invocation has started its measured part, or the latest invocation was kept or discarded. */
        IDLE,
        /** The measured part of an invocation has started and not ended. */
        RUNNING,
        /** The measured part of the latest invocation has ended, and the invocation is kept unless it is discarded. */
        ENDED,
        /** The latest invocation was discarded because a garbage collection ran during its measured part. */
        DISCARDED_FOR_GC
    }

    /** The latencies of the kept invocations. */
    private final LatencyHistogram histogram = HistogramLayout.newHistogram();

    /** The latencies of the latest invocation. */
    private final LatencyHistogram invocationHistogram = HistogramLayout.newHistogram();

    /** Where the latest invocation stands. */
    private State state = State.IDLE;

    /** The total bytes allocated by the kept invocations. */
    private long allocatedBytes;

    /** The bytes allocated by all threads at the start of the latest invocation's measured part. */
    private long allocatedBytesAtStart;

    /** The bytes allocated by all threads during the latest invocation's measured part, once it has ended. */
    private long invocationAllocatedBytes;

    /** The number of garbage collections in the JVM at the start of the latest invocation's measured part. */
    private long gcCountAtStart;

    /** The number of invocations discarded because a garbage collection ran during their measured part. */
    private long gcDiscardedInvocations;

    /** The number of invocations discarded because the pipeline did not keep up. */
    private long saturationDiscardedInvocations;

    PipelineRecorder() {}

    /**
     * Starts the measured part of an invocation: counts the bytes all threads allocate and watches for garbage
     * collections from now on. Call it right before the first input. The previous invocation is kept from now on,
     * unless it was discarded.
     *
     * @throws IllegalStateException if a measured part is running
     */
    public void prepareToMeasure() {
        if (state == State.RUNNING) {
            throw new IllegalStateException(
                    "A measured part is running; end it with finalizeMeasurement() before preparing the next one");
        }
        keepEndedInvocation();
        invocationHistogram.reset();
        state = State.RUNNING;
        gcCountAtStart = GarbageCollections.count();
        // last, so that the work above is not counted
        allocatedBytesAtStart = THREAD_MX_BEAN.getTotalThreadAllocatedBytes();
    }

    /**
     * Records the latency of one value of the running measured part.
     *
     * @param latencyNanos the latency in nanoseconds
     * @throws IllegalStateException if no measured part is running
     * @throws IllegalArgumentException if the latency is negative
     */
    public void record(final long latencyNanos) {
        if (state != State.RUNNING) {
            throw new IllegalStateException("No measured part is running; begin one with prepareToMeasure()");
        }
        invocationHistogram.record(latencyNanos);
    }

    /**
     * Ends the running measured part. Call it right after the drain and the latencies, before work that allocates. The
     * invocation is discarded if a garbage collection ran during the measured part.
     *
     * @throws IllegalStateException if no measured part is running
     */
    public void finalizeMeasurement() {
        if (state != State.RUNNING) {
            throw new IllegalStateException("No measured part is running; begin one with prepareToMeasure()");
        }
        invocationAllocatedBytes = THREAD_MX_BEAN.getTotalThreadAllocatedBytes() - allocatedBytesAtStart;
        if (GarbageCollections.count() == gcCountAtStart) {
            state = State.ENDED;
        } else {
            gcDiscardedInvocations++;
            state = State.DISCARDED_FOR_GC;
        }
    }

    /**
     * Discards the invocation whose measured part ended last, because the pipeline did not keep up. An invocation that
     * {@link #finalizeMeasurement()} discarded for a garbage collection counts here too.
     *
     * @throws IllegalStateException if no measured part has ended since the latest one started, or if this method
     * discarded the invocation already
     */
    public void discardSaturatedInvocation() {
        if (state != State.ENDED && state != State.DISCARDED_FOR_GC) {
            throw new IllegalStateException(
                    "No invocation to discard: its measured part is running, or it was kept or discarded");
        }
        saturationDiscardedInvocations++;
        state = State.IDLE;
    }

    /**
     * Ends the iteration: the invocation whose measured part ended last is kept, unless it was discarded. An invocation
     * whose measured part is still running, because the benchmark failed during it, is not kept.
     */
    void endIteration() {
        keepEndedInvocation();
    }

    /** Adds the invocation whose measured part ended last to the kept invocations, unless it was discarded. */
    private void keepEndedInvocation() {
        if (state == State.ENDED) {
            histogram.add(invocationHistogram);
            allocatedBytes += invocationAllocatedBytes;
            state = State.IDLE;
        }
    }

    /**
     * Returns the histogram of the kept invocations. The caller may read it, but must not modify it.
     *
     * @return the histogram of the kept invocations
     */
    @NonNull
    LatencyHistogram histogram() {
        return histogram;
    }

    /**
     * Returns the total bytes allocated by the kept invocations.
     *
     * @return the total bytes allocated by the kept invocations
     */
    long allocatedBytes() {
        return allocatedBytes;
    }

    /**
     * Returns the number of invocations discarded because a garbage collection ran during their measured part.
     *
     * @return the number of invocations discarded for a collection
     */
    long gcDiscardedInvocations() {
        return gcDiscardedInvocations;
    }

    /**
     * Returns the number of invocations discarded because the pipeline did not keep up.
     *
     * @return the number of invocations discarded for saturation
     */
    long saturationDiscardedInvocations() {
        return saturationDiscardedInvocations;
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import com.sun.management.ThreadMXBean;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.management.ManagementFactory;

/**
 * Collects the latencies and the allocation of the passes of a pipeline benchmark during one JMH iteration.
 * {@link LatencyProfiler} turns them into the benchmark's latency results, as it does with the measurements of a
 * {@link LatencyRecorder}.
 *
 * <h2>What it is for</h2>
 *
 * <p>A pipeline hands its inputs on between threads: a value enters on one thread and leaves on another, thus it is not
 * possible to measure the latency on a single thread. A timer such as
 * {@link org.hiero.consensus.benchmark.tools.pipeline.PipelineTimer PipelineTimer} computes each latency from the time
 * its input was due and the time the value arrived, and records it here. The pipeline allocates on its own threads, so
 * the recorder counts the bytes that all threads of the JVM allocate during a pass.
 *
 * <h2>A pass</h2>
 *
 * <pre>
 * startPass()              reads the allocated bytes of all threads and the number of garbage collections
 * replay the inputs and drain the pipeline
 * record(latency)          once for every value that arrived
 * endPass()                reads both again
 * discardSaturatedPass()   only if the pipeline did not keep up
 * </pre>
 *
 * <p>A pass counts once it has ended, unless it is discarded. Neither discard is an error: the next pass takes the
 * discarded one's place.
 *
 * <ul>
 *   <li>{@link #endPass()} discards a pass during which a garbage collection ran, as {@link LatencyRecorder} does: the
 *       collection pauses every thread of the pipeline, and the pause would disturb the latencies of one pass at random.
 *       The allocation per value stands in for the cost of garbage collection.</li>
 *   <li>{@link #discardSaturatedPass()} discards a pass in which the pipeline did not keep up, whose latencies grew with
 *       the length of the pass. A pass with a collection is counted only as such, because the pause of a collection can
 *       make a pipeline that keeps up look saturated.</li>
 * </ul>
 *
 * <h2>Using it</h2>
 *
 * <p>Obtain the recorder with {@link LatencyProfiler#pipelineRecorder()} in a setup method that runs at least once per
 * iteration ({@code Level.Iteration} or {@code Level.Invocation}). Every iteration gets a new recorder. The allocated
 * bytes and the collections are counted for the whole JVM, so the passes must not overlap: an iteration has one pipeline
 * recorder, for a benchmark that runs on one thread, and its methods throw when they are called out of order.
 */
public final class PipelineRecorder {

    /** The MXBean that measures the bytes allocated by all threads. */
    private static final ThreadMXBean THREAD_MX_BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** Where the latest pass stands. */
    private enum State {
        /** No pass has started, or the latest pass counts or was discarded. */
        IDLE,
        /** A pass has started and not ended. */
        RUNNING,
        /** The latest pass has ended and counts, unless it is discarded. */
        ENDED,
        /** The latest pass has ended and was discarded because a garbage collection ran during it. */
        DISCARDED_FOR_GC
    }

    /** The latencies of the passes that count. */
    private final LatencyHistogram histogram = HistogramLayout.newHistogram();

    /** The latencies of the latest pass. */
    private final LatencyHistogram passHistogram = HistogramLayout.newHistogram();

    /** Where the latest pass stands. */
    private State state = State.IDLE;

    /** The total bytes allocated by the passes that count. */
    private long allocatedBytes;

    /** The bytes allocated by all threads at the start of the latest pass. */
    private long allocatedBytesAtStart;

    /** The bytes allocated by all threads during the latest pass, once it has ended. */
    private long passAllocatedBytes;

    /** The number of garbage collections in the JVM at the start of the latest pass. */
    private long gcCountAtStart;

    /** The number of passes discarded because a garbage collection ran during them. */
    private long gcDiscardedPasses;

    /** The number of passes discarded because the pipeline did not keep up. */
    private long saturationDiscardedPasses;

    PipelineRecorder() {}

    /**
     * Starts a pass: counts the bytes all threads allocate and watches for garbage collections from now on. Call it
     * right before the first input. The previous pass counts from now on, unless it was discarded.
     *
     * @throws IllegalStateException if a pass is running
     */
    public void startPass() {
        if (state == State.RUNNING) {
            throw new IllegalStateException("A pass is running; end it with endPass() before starting the next one");
        }
        countEndedPass();
        passHistogram.reset();
        state = State.RUNNING;
        gcCountAtStart = GarbageCollections.count();
        // last, so that the work above is not counted
        allocatedBytesAtStart = THREAD_MX_BEAN.getTotalThreadAllocatedBytes();
    }

    /**
     * Records the latency of one value of the running pass.
     *
     * @param latencyNanos the latency in nanoseconds
     * @throws IllegalStateException if no pass is running
     * @throws IllegalArgumentException if the latency is negative
     */
    public void record(final long latencyNanos) {
        if (state != State.RUNNING) {
            throw new IllegalStateException("No pass is running; start one with startPass()");
        }
        passHistogram.record(latencyNanos);
    }

    /**
     * Ends the running pass. Call it right after the drain and the latencies, before work that allocates. The pass is
     * discarded if a garbage collection ran during it.
     *
     * @throws IllegalStateException if no pass is running
     */
    public void endPass() {
        if (state != State.RUNNING) {
            throw new IllegalStateException("No pass is running; start one with startPass()");
        }
        passAllocatedBytes = THREAD_MX_BEAN.getTotalThreadAllocatedBytes() - allocatedBytesAtStart;
        if (GarbageCollections.count() == gcCountAtStart) {
            state = State.ENDED;
        } else {
            gcDiscardedPasses++;
            state = State.DISCARDED_FOR_GC;
        }
    }

    /**
     * Discards the pass that ended last, because the pipeline did not keep up. A pass that {@link #endPass()} discarded
     * for a garbage collection is not counted again.
     *
     * @throws IllegalStateException if no pass has ended since the latest one started, or if it was discarded already
     */
    public void discardSaturatedPass() {
        switch (state) {
            case ENDED -> saturationDiscardedPasses++;
            case DISCARDED_FOR_GC -> {
                // counted as discarded for the collection
            }
            default ->
                throw new IllegalStateException("No pass to discard: it is running, or it counts or was discarded");
        }
        state = State.IDLE;
    }

    /**
     * Ends the iteration: the pass that ended last counts, unless it was discarded. A pass that is still running, because
     * the benchmark failed during it, does not count.
     */
    void endIteration() {
        countEndedPass();
    }

    /** Adds the pass that ended last to the passes that count, unless it was discarded. */
    private void countEndedPass() {
        if (state == State.ENDED) {
            histogram.add(passHistogram);
            allocatedBytes += passAllocatedBytes;
            state = State.IDLE;
        }
    }

    /**
     * Returns the histogram of the passes that count. The caller may read it, but must not modify it.
     *
     * @return the histogram of the passes that count
     */
    @NonNull
    LatencyHistogram histogram() {
        return histogram;
    }

    /**
     * Returns the total bytes allocated by the passes that count.
     *
     * @return the total bytes allocated by the passes that count
     */
    long allocatedBytes() {
        return allocatedBytes;
    }

    /**
     * Returns the number of passes discarded because a garbage collection ran during them.
     *
     * @return the number of passes discarded for a collection
     */
    long gcDiscardedPasses() {
        return gcDiscardedPasses;
    }

    /**
     * Returns the number of passes discarded because the pipeline did not keep up.
     *
     * @return the number of passes discarded for saturation
     */
    long saturationDiscardedPasses() {
        return saturationDiscardedPasses;
    }
}

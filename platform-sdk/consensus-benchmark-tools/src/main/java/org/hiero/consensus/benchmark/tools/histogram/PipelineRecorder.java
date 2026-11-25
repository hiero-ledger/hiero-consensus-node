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
 * preparePass()            stores the previous pass, unless it was discarded, and clears the latencies of the last one
 * startPass()              reads the allocated bytes of all threads and the number of garbage collections
 * replay the inputs and drain the pipeline
 * record(latency)          once for every value that arrived
 * endPass()                reads both again
 * discardSaturatedPass()   only if the pipeline did not keep up
 * </pre>
 *
 * <p>A pass is stored once it has ended, unless it is discarded. Neither discard is an error: the next pass takes the
 * discarded one's place.
 *
 * <ul>
 *   <li>{@link #endPass()} discards a pass during which a garbage collection ran, as {@link LatencyRecorder} does: the
 *       collection pauses every thread of the pipeline, and the pause would disturb the latencies of one pass at random.
 *       The allocation per value stands in for the cost of garbage collection.</li>
 *   <li>{@link #discardSaturatedPass()} discards a pass in which the pipeline did not keep up, whose latencies grew with
 *       the length of the pass. The two are counted independently: a pass with a collection in which the pipeline also
 *       did not keep up counts in both.</li>
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
        /** No pass is prepared: none has run yet, or the latest pass was stored or discarded. */
        UNPREPARED,
        /** The next pass is prepared and has not started. */
        PREPARED,
        /** A pass has started and not ended. */
        RUNNING,
        /** The latest pass has ended; the next {@link #preparePass()} or the end of the iteration stores it,
         * unless {@link #discardSaturatedPass()} discards it. */
        ENDED,
        /** The latest pass has ended and was discarded because a garbage collection ran during it. */
        DISCARDED_FOR_GC
    }

    /** The latencies of the stored passes. */
    private final LatencyHistogram histogram = HistogramLayout.newHistogram();

    /** The latencies of the latest pass. */
    private final LatencyHistogram passHistogram = HistogramLayout.newHistogram();

    /** Where the latest pass stands. */
    private State state = State.UNPREPARED;

    /** The total bytes allocated by the stored passes. */
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
     * Prepares the next pass: the previous pass is stored, unless it was discarded, and the latencies of the next pass
     * are reset. Call it before the pass, in a setup method, so that this work does not delay the start of
     * the pass.
     *
     * @throws IllegalStateException if a pass is running
     */
    public void preparePass() {
        if (state == State.RUNNING) {
            throw new IllegalStateException("A pass is running; end it with endPass() before preparing the next one");
        }
        storeEndedPassUnlessDiscarded();
        passHistogram.reset();
        state = State.PREPARED;
    }

    /**
     * Starts the prepared pass: counts the bytes all threads allocate and watches for garbage collections from now on.
     * Call it right before the first measured input.
     *
     * @throws IllegalStateException if no pass is prepared
     */
    public void startPass() {
        if (state != State.PREPARED) {
            throw new IllegalStateException("No pass is prepared; call preparePass() before startPass()");
        }
        state = State.RUNNING;
        gcCountAtStart = GarbageCollections.count();
        // allocated bytes are read last, so that the work above does not interfere
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
     * for a garbage collection counts here too.
     *
     * @throws IllegalStateException if no pass has ended since the latest one started, or if this method discarded it
     * already
     */
    public void discardSaturatedPass() {
        if (state != State.ENDED && state != State.DISCARDED_FOR_GC) {
            throw new IllegalStateException("No pass to discard: it is running, or it was stored or discarded");
        }
        saturationDiscardedPasses++;
        state = State.UNPREPARED;
    }

    /**
     * Ends the iteration: the pass that ended last is stored, unless it was discarded. A pass that is still running,
     * because the benchmark failed during it, is not stored.
     */
    void endIteration() {
        storeEndedPassUnlessDiscarded();
    }

    /** Stores the pass that ended last, unless it was discarded: adds its latencies and allocation to the stored ones. */
    private void storeEndedPassUnlessDiscarded() {
        if (state == State.ENDED) {
            histogram.add(passHistogram);
            allocatedBytes += passAllocatedBytes;
            state = State.UNPREPARED;
        }
    }

    /**
     * Returns the histogram of the stored passes. The caller may read it, but must not modify it.
     *
     * @return the histogram of the stored passes
     */
    @NonNull
    LatencyHistogram histogram() {
        return histogram;
    }

    /**
     * Returns the total bytes allocated by the stored passes.
     *
     * @return the total bytes allocated by the stored passes
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

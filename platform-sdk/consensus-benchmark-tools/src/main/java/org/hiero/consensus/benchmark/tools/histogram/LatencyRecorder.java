// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import com.sun.management.ThreadMXBean;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Measures how long each call of a benchmark takes, and how much memory the calls allocate, for one benchmark thread
 * during one JMH iteration. {@link LatencyProfiler} turns these measurements into the benchmark's latency results.
 *
 * <h2>What it is for</h2>
 *
 * <p>On its own, JMH times the benchmark method as a whole and reports the average time per call. An average hides the
 * rare slow calls, which often matter most for latency. This recorder times every single call and records it in a
 * {@link LatencyHistogram}, so that {@link LatencyProfiler} can report percentiles: for example p99, the latency that
 * 99% of the calls stay at or below. It also measures the memory allocated by the measured calls, excluding the
 * benchmark's setup, and discards measurements disturbed by a garbage collection.
 *
 * <h2>How the timing works</h2>
 *
 * <p>The benchmark calls {@link #startMeasurement()} before its first measured call, {@link #lap()} right after each
 * measured call, and {@link #stopMeasurement()} after the last one. Every {@code lap()} reads the clock once and
 * records the time since the previous reading:
 *
 * <pre>
 * startMeasurement()     reads the clock: t0
 * operation(input1)
 * lap()                  reads the clock: t1, records t1 - t0
 * bh.consume(output1)    counted in the next latency
 * operation(input2)
 * lap()                  reads the clock: t2, records t2 - t1
 * </pre>
 *
 * <p>With a start and an end reading around each call, the clock would be read twice per call, and the time between
 * one end and the next start would not be measured at all. With one reading per call, no time is lost: the latencies
 * add up to the duration of the pass, so their mean agrees with JMH's own average. The price is that each latency also
 * contains the work between two calls, including the recording itself, so keep that work small.
 *
 * <p>Reading the clock takes time and has a limited resolution. On Apple Silicon, {@link System#nanoTime()} advances in
 * steps of about 42 ns, so latencies below a few microseconds are coarse on a Mac.
 *
 * <h2>Passes and garbage collection</h2>
 *
 * <p>The calls between {@link #startMeasurement()} and {@link #stopMeasurement()} form a pass. A garbage collection
 * pauses the application, and a latency containing that pause measures the garbage collector, not the benchmarked code.
 * A pass during which a garbage collection ran is therefore discarded: its latencies and allocation are dropped, and
 * only the discarded pass is counted. Whether a collection falls into a pass is non-deterministic,
 * so a discarded pass is not an error. JMH keeps invoking the benchmark until the iteration ends, so the next pass
 * takes its place. The allocation per call stands in for the cost of garbage collection. To make collections during a
 * pass rare, call {@link System#gc()} at the end of the setup that runs before each pass, and give the JVM enough heap.
 *
 * <h2>Using it</h2>
 *
 * <p>Obtain the recorder with {@link LatencyProfiler#recorder()} in a setup method that runs at least once per
 * iteration ({@code Level.Iteration} or {@code Level.Invocation}). Every iteration gets new recorders, and a recorder
 * kept from an earlier iteration is no longer reported. Run the benchmark with
 * {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 *
 * <pre>{@code
 * @State(Scope.Thread)
 * public class ExampleBenchmark {
 *     private List<Input> inputs;
 *     private LatencyRecorder recorder;
 *
 *     @Setup(Level.Invocation)
 *     public void prepareInvocation() {
 *         inputs = createInputs(); // not measured
 *         recorder = LatencyProfiler.recorder();
 *         System.gc();
 *     }
 *
 *     @Benchmark
 *     public void run(final Blackhole bh) {
 *         recorder.startMeasurement();
 *         for (final Input input : inputs) {
 *             final Output output = operation(input);
 *             recorder.lap();
 *             bh.consume(output);
 *         }
 *         recorder.stopMeasurement();
 *     }
 * }
 * }</pre>
 *
 * <h2>Using it on several threads</h2>
 *
 * <p>A recorder belongs to one thread and is not thread-safe, like the {@link LatencyHistogram} it records into.
 * {@link LatencyProfiler#recorder()} returns a separate recorder to each calling thread. With several benchmark threads
 * ({@code @Threads}), keep the recorder in a {@code @State(Scope.Thread)} object, so that each thread uses its own.
 * Never store it in a {@code Scope.Benchmark} or {@code Scope.Group} object, where the threads would share it and lose
 * values without any error.
 *
 * <p>After the iteration, {@link LatencyProfiler} combines the recorders of all threads. JMH calls the profiler only
 * after all benchmark threads have finished, which makes their recorded values visible to it. The results then cover
 * the calls of all threads together.
 *
 * <p>Garbage collections are counted for the whole JVM, not per thread. A collection during the pass of one thread
 * therefore also discards the passes of the other threads that were running at the time.
 */
public final class LatencyRecorder {

    /** The MXBean that measures the current thread's allocated bytes. */
    private static final ThreadMXBean THREAD_MX_BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** The MXBeans that count the JVM's garbage collections. */
    private static final List<GarbageCollectorMXBean> GC_MX_BEANS = ManagementFactory.getGarbageCollectorMXBeans();

    /** Buckets at most 2^-10 (about 0.1%) of their values wide, and exact below 2,048 ns. */
    private static final int PRECISION_BITS = 10;

    /** Tracks latencies up to 2^36 - 1 ns, about 68.7 s. */
    private static final int RANGE_BITS = 36;

    /** The latencies of the kept passes. */
    private final LatencyHistogram histogram = newHistogram();

    /** The latencies of the running pass. */
    private final LatencyHistogram passHistogram = newHistogram();

    /** The timestamp of the previous lap, or of the start of the pass. */
    private long lapStart;

    /** The total bytes allocated by the kept passes. */
    private long allocatedBytes;

    /** The bytes allocated by the running pass at its start. */
    private long allocatedBytesAtStart;

    /** The number of garbage collections in the JVM at the start of the running pass. */
    private long gcCountAtStart;

    /** The number of passes discarded because a garbage collection ran during them. */
    private long discardedPasses;

    LatencyRecorder() {}

    /**
     * Creates an empty histogram with the layout of the recorders' histograms, so that it can merge them.
     *
     * @return an empty histogram
     */
    @NonNull
    static LatencyHistogram newHistogram() {
        return new LatencyHistogram(PRECISION_BITS, RANGE_BITS);
    }

    /**
     * Starts a pass: measures the allocation of the current thread and watches for garbage collections from now on,
     * and starts the first lap. Call it right before the measured calls.
     */
    public void startMeasurement() {
        passHistogram.reset();
        gcCountAtStart = totalGcCount();
        allocatedBytesAtStart = THREAD_MX_BEAN.getCurrentThreadAllocatedBytes();
        // last, so that the reads above are not part of the first latency
        lapStart = System.nanoTime();
    }

    /**
     * Records the latency of one call: the time since the previous lap, or since {@link #startMeasurement()}. Call it
     * right after each measured call.
     */
    public void lap() {
        final long now = System.nanoTime();
        passHistogram.record(now - lapStart);
        lapStart = now;
    }

    /**
     * Ends the pass started by {@link #startMeasurement()}. Call it right after the measured calls. Keeps the pass's
     * latencies and allocation if no garbage collection ran during it, and discards them otherwise.
     */
    public void stopMeasurement() {
        final long passAllocatedBytes = THREAD_MX_BEAN.getCurrentThreadAllocatedBytes() - allocatedBytesAtStart;
        if (totalGcCount() == gcCountAtStart) {
            histogram.add(passHistogram);
            allocatedBytes += passAllocatedBytes;
        } else {
            discardedPasses++;
        }
    }

    private static long totalGcCount() {
        long count = 0;
        for (final GarbageCollectorMXBean bean : GC_MX_BEANS) {
            count += Math.max(0, bean.getCollectionCount());
        }
        return count;
    }

    /**
     * Returns the histogram of the kept passes. The caller may read it, but must not modify it.
     *
     * @return the histogram of the kept passes
     */
    @NonNull
    LatencyHistogram histogram() {
        return histogram;
    }

    /**
     * Returns the total bytes allocated by the kept passes.
     *
     * @return the total bytes allocated by the kept passes
     */
    long allocatedBytes() {
        return allocatedBytes;
    }

    /**
     * Returns the number of passes discarded because a garbage collection ran during them.
     *
     * @return the number of discarded passes
     */
    long discardedPasses() {
        return discardedPasses;
    }
}

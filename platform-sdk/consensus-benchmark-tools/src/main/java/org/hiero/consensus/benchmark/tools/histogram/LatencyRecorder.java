// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import com.sun.management.ThreadMXBean;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.management.ManagementFactory;

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
 * <h2>The measured part and laps</h2>
 *
 * <p>The recorder works on three nested levels:
 * <ul>
 *   <li>{@link #prepareToMeasure()} and {@link #finalizeMeasurement()} enclose the measured part of one invocation of
 *   the benchmark method. The invocation is discarded if a garbage collection runs during its measured part.</li>
 *   <li>{@link #startLaps()} and {@link #stopLaps()} enclose consecutive measured calls. Only the work between them is
 *   timed and counted in the allocation. The measured part has one stretch of laps or several.</li>
 *   <li>{@link #lap()}, right after each measured call, records its latency.</li>
 * </ul>
 *
 * <h2>How the timing works</h2>
 *
 * <p>Every {@code lap()} reads the clock once and records the time since the previous reading, which is the previous
 * {@code lap()} or {@code startLaps()}:
 *
 * <pre>
 * startLaps()            reads the allocated bytes, then the clock: t0
 * operation(input1)
 * lap()                  reads the clock: t1, records t1 - t0
 * bh.consume(output1)    counted in the next latency
 * operation(input2)
 * lap()                  reads the clock: t2, records t2 - t1
 * stopLaps()             reads the allocated bytes
 * </pre>
 *
 * <p>With a start and an end reading around each call, the clock would be read twice per call, and the time between
 * one end and the next start would not be measured at all. With one reading per call, no time is lost: the latencies
 * add up to the duration of the laps, so with one stretch of laps per invocation, their mean agrees with JMH's own
 * average. The price is that each latency also contains the work between two calls, including the recording itself, so
 * keep that work small.
 *
 * <h2>Leaving out work between calls</h2>
 *
 * <p>Work outside the laps counts neither in a latency nor in the allocation, for example preparing the input of the
 * next call, or calls of the code under test that another benchmark method measures:
 *
 * <pre>
 * startLaps()            reads the allocated bytes, then the clock: t0
 * operation(input1)
 * lap()                  reads the clock: t1, records t1 - t0
 * stopLaps()             reads the allocated bytes
 * otherWork()            neither timed nor counted in the allocation
 * startLaps()            reads the allocated bytes, then the clock: t2
 * operation(input2)
 * lap()                  reads the clock: t3, records t3 - t2
 * stopLaps()             reads the allocated bytes
 * </pre>
 *
 * <p>Each stretch of laps costs a clock read and two reads of the allocated bytes, outside the recorded latencies. A
 * garbage collection outside the laps still discards the invocation, because it can leave work for the measured calls.
 *
 * <p>{@code startLaps()} while laps are started, {@code stopLaps()} while none are, and {@code finalizeMeasurement()}
 * while laps are started throw, because each would count the allocation wrongly.
 *
 * <p>Reading the clock takes time and has a limited resolution. On Apple Silicon, {@link System#nanoTime()} advances in
 * steps of about 42 ns, so latencies below a few microseconds are coarse on a Mac.
 *
 * <h2>Discarded invocations</h2>
 *
 * <p>A garbage collection pauses the application, and a latency containing that pause measures the garbage collector,
 * not the benchmarked code. An invocation during whose measured part a garbage collection ran is therefore discarded:
 * the recorder drops its latencies and allocation and only counts it. JMH still includes it in its primary score.
 * Whether a collection falls into the measured part is non-deterministic, so a discarded invocation is not an error.
 * JMH keeps invoking the benchmark until the iteration ends, so the next invocation takes its place. The allocation per
 * call stands in for the cost of garbage collection. To make collections during the measured part rare, call
 * {@link System#gc()} at the end of the setup that runs before each invocation, and give the JVM enough heap.
 *
 * <h2>Using it</h2>
 *
 * <p>Obtain the recorder with {@link LatencyProfiler#recorder()} in a setup method that runs at least once per
 * iteration ({@code Level.Iteration} or {@code Level.Invocation}). Every iteration gets new recorders, and a recorder
 * kept from an earlier iteration is no longer reported. Call {@code prepareToMeasure()} and
 * {@code finalizeMeasurement()} once per invocation, so that the profiler's counts of discarded invocations are right.
 * Run the benchmark with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
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
 *         recorder.prepareToMeasure();
 *         recorder.startLaps();
 *         for (final Input input : inputs) {
 *             final Output output = operation(input);
 *             recorder.lap();
 *             bh.consume(output);
 *         }
 *         recorder.stopLaps();
 *         recorder.finalizeMeasurement();
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
 * <p>Garbage collections are counted for the whole JVM, not per thread. A collection during the measured part of one
 * thread's invocation therefore also discards the invocations of the other threads whose measured parts were running at
 * the time.
 */
public final class LatencyRecorder {

    /** The MXBean that measures the current thread's allocated bytes. */
    private static final ThreadMXBean THREAD_MX_BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** The latencies of the kept invocations. */
    private final LatencyHistogram histogram = HistogramLayout.newHistogram();

    /** The latencies of the current invocation. */
    private final LatencyHistogram invocationHistogram = HistogramLayout.newHistogram();

    /** The timestamp of the previous lap, or of {@link #startLaps()}. */
    private long lapStart;

    /** The total bytes allocated by the laps of the kept invocations. */
    private long allocatedBytes;

    /** The bytes allocated by the stopped laps of the current invocation. */
    private long invocationAllocatedBytes;

    /** The bytes allocated by the current thread at the latest {@link #startLaps()}. */
    private long allocatedBytesAtLapsStart;

    /** Whether laps are started: {@link #startLaps()} was called, and {@link #stopLaps()} not since. */
    private boolean lapsStarted;

    /** The number of garbage collections in the JVM at the start of the current invocation's measured part. */
    private long gcCountAtStart;

    /** The number of invocations discarded because a garbage collection ran during their measured part. */
    private long discardedInvocations;

    LatencyRecorder() {}

    /**
     * Starts the measured part of an invocation: watches for garbage collections from now on. Call it once per
     * invocation, at the start of the benchmark method.
     */
    public void prepareToMeasure() {
        invocationHistogram.reset();
        invocationAllocatedBytes = 0;
        lapsStarted = false;
        gcCountAtStart = GarbageCollections.count();
    }

    /**
     * Starts laps: measures the allocation of the current thread from now on, and starts the first lap. Call it right
     * before the first of consecutive measured calls.
     *
     * @throws IllegalStateException if laps are started already
     */
    public void startLaps() {
        if (lapsStarted) {
            throw new IllegalStateException("Laps are started already");
        }
        lapsStarted = true;
        allocatedBytesAtLapsStart = THREAD_MX_BEAN.getCurrentThreadAllocatedBytes();
        // last, so that the read above is not part of the first latency
        lapStart = System.nanoTime();
    }

    /**
     * Records the latency of one call: the time since the previous lap, or since {@link #startLaps()}. Call it right
     * after each measured call.
     */
    public void lap() {
        final long now = System.nanoTime();
        invocationHistogram.record(now - lapStart);
        lapStart = now;
    }

    /**
     * Stops the laps started by {@link #startLaps()}, so that the work until the next {@code startLaps()} counts
     * neither in a latency nor in the allocation. Call it right after the last {@link #lap()} of consecutive measured
     * calls.
     *
     * @throws IllegalStateException if no laps are started
     */
    public void stopLaps() {
        if (!lapsStarted) {
            throw new IllegalStateException("No laps are started");
        }
        lapsStarted = false;
        invocationAllocatedBytes += THREAD_MX_BEAN.getCurrentThreadAllocatedBytes() - allocatedBytesAtLapsStart;
    }

    /**
     * Ends the measured part started by {@link #prepareToMeasure()}. Call it at the end of the benchmark method. Keeps
     * the invocation's latencies and allocation if no garbage collection ran during the measured part, and discards
     * them otherwise.
     *
     * @throws IllegalStateException if laps are started
     */
    public void finalizeMeasurement() {
        if (lapsStarted) {
            throw new IllegalStateException("Laps are started; stop them before finalizing the measurement");
        }
        if (GarbageCollections.count() == gcCountAtStart) {
            histogram.add(invocationHistogram);
            allocatedBytes += invocationAllocatedBytes;
        } else {
            discardedInvocations++;
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
     * Returns the total bytes allocated by the laps of the kept invocations.
     *
     * @return the total bytes allocated by the laps of the kept invocations
     */
    long allocatedBytes() {
        return allocatedBytes;
    }

    /**
     * Returns the number of invocations discarded because a garbage collection ran during their measured part.
     *
     * @return the number of discarded invocations
     */
    long discardedInvocations() {
        return discardedInvocations;
    }
}

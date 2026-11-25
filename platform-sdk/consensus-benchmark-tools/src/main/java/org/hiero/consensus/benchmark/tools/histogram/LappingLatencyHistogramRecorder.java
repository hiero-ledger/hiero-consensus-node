// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

/**
 * Times the calls of a loop with one clock read per call, for one benchmark thread during one JMH iteration.
 * {@link LatencyProfiler#lappingRecorder()} returns it. For calls timed one at a time, with untimed work between each
 * two, use {@link LatencyHistogramRecorder} instead.
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
 * <p>Work outside the laps counts neither in a latency nor in the allocation, for example calls of the code under test
 * that another benchmark method measures:
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
 * <p>{@code startLaps()} while laps are started, {@code stopLaps()} while none are, and {@code finalizeMeasurement()}
 * while laps are started throw, because each would count the allocation wrongly.
 *
 * <h2>Using it</h2>
 *
 * <p>Obtain the recorder with {@link LatencyProfiler#lappingRecorder()} in a setup method that runs at least once per
 * iteration, and call {@code prepareToMeasure()} and {@code finalizeMeasurement()} once per invocation. The
 * {@code AbstractLatencyHistogramRecorder} in this package describes the discarded invocations and the use on several
 * threads.
 *
 * <pre>{@code
 * @State(Scope.Thread)
 * public class ExampleBenchmark {
 *     private List<Input> inputs;
 *     private LappingLatencyHistogramRecorder recorder;
 *
 *     @Setup(Level.Invocation)
 *     public void prepareInvocation() {
 *         inputs = createInputs(); // not measured
 *         recorder = LatencyProfiler.lappingRecorder();
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
 */
public final class LappingLatencyHistogramRecorder extends AbstractLatencyHistogramRecorder {

    /** The timestamp of the previous lap, or of {@link #startLaps()}. */
    private long lapStart;

    LappingLatencyHistogramRecorder() {}

    /**
     * Starts laps: measures the allocation of the current thread from now on, and starts the first lap. Call it right
     * before the first of consecutive measured calls.
     *
     * @throws IllegalStateException if laps are started already
     */
    public void startLaps() {
        startTiming();
        // last, so that the work above is not part of the first latency
        lapStart = System.nanoTime();
    }

    /**
     * Records the latency of one call: the time since the previous lap, or since {@link #startLaps()}. Call it right
     * after each measured call.
     */
    public void lap() {
        final long now = System.nanoTime();
        record(now - lapStart);
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
        stopTiming();
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

/**
 * Times single calls, one at a time, for one benchmark thread during one JMH iteration.
 * {@link LatencyProfiler#recorder()} returns it. For the calls of a loop, use {@link LappingLatencyHistogramRecorder}
 * instead, which reads the clock only once per call.
 *
 * <p>The recorder works on two nested levels:
 * <ul>
 *   <li>{@link #prepareToMeasure()} and {@link #finalizeMeasurement()} enclose the measured part of one invocation of
 *   the benchmark method. The invocation is discarded if a garbage collection runs during its measured part.</li>
 *   <li>{@link #startMeasurement()} and {@link #stopMeasurement()} enclose one measured call and record its latency.
 *   Only the work between them is timed and counted in the allocation.</li>
 * </ul>
 *
 * <h2>How the timing works</h2>
 *
 * <pre>
 * startMeasurement()     reads the allocated bytes, then the clock: t0
 * operation(input1)
 * stopMeasurement()      reads the clock: t1, then the allocated bytes, records t1 - t0
 * otherWork()            neither timed nor counted in the allocation
 * startMeasurement()     reads the allocated bytes, then the clock: t2
 * operation(input2)
 * stopMeasurement()      reads the clock: t3, then the allocated bytes, records t3 - t2
 * </pre>
 *
 * <p>Each latency contains about one clock read. Each measurement also costs two reads of the allocated bytes, outside
 * the recorded latencies.
 *
 * <p>{@code startMeasurement()} while a measurement is running, {@code stopMeasurement()} while none is, and
 * {@code finalizeMeasurement()} while a measurement is running throw, because each would count the allocation wrongly.
 *
 * <h2>Using it</h2>
 *
 * <p>Obtain the recorder with {@link LatencyProfiler#recorder()} in a setup method that runs at least once per
 * iteration, and call {@code prepareToMeasure()} and {@code finalizeMeasurement()} once per invocation. The
 * {@code AbstractLatencyHistogramRecorder} in this package describes the discarded invocations and the use on several
 * threads.
 *
 * <pre>{@code
 * @State(Scope.Thread)
 * public class ExampleBenchmark {
 *     private List<Input> inputs;
 *     private LatencyHistogramRecorder recorder;
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
 *         for (final Input input : inputs) {
 *             final Prepared prepared = prepare(input); // not measured
 *             recorder.startMeasurement();
 *             final Output output = operation(prepared);
 *             recorder.stopMeasurement();
 *             bh.consume(output);
 *         }
 *         recorder.finalizeMeasurement();
 *     }
 * }
 * }</pre>
 */
public final class LatencyHistogramRecorder extends AbstractLatencyHistogramRecorder {

    /** The timestamp of the latest {@link #startMeasurement()}. */
    private long measurementStart;

    LatencyHistogramRecorder() {}

    /**
     * Starts the measurement of one call: measures the allocation of the current thread from now on, and reads the
     * clock. Call it right before the measured call.
     *
     * @throws IllegalStateException if a measurement is running already
     */
    public void startMeasurement() {
        startTiming();
        // last, so that the work above is not part of the latency
        measurementStart = System.nanoTime();
    }

    /**
     * Stops the measurement started by {@link #startMeasurement()} and records the latency of the call. Call it right
     * after the measured call.
     *
     * @throws IllegalStateException if no measurement is running
     */
    public void stopMeasurement() {
        // first, so that the work below is not part of the latency
        final long measurementEnd = System.nanoTime();
        stopTiming();
        record(measurementEnd - measurementStart);
    }
}

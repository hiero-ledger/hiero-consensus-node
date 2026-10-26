// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the cost of recording into a {@link LatencyHistogram}. It checks the tool and is not a tracked benchmark.
 *
 * <p>{@link #timerPair()} times an empty region with two timestamps, and {@link #timerPairAndRecord()} also records
 * the result. The difference is the recorder's cost per timed call. {@link #readSpread()} and {@link #recordSpread()}
 * compare reading and recording values spread over many buckets.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class LatencyHistogramBenchmark {
    private static final long SEED = 0;
    private static final int PRECISION_BITS = 10;
    private static final int RANGE_BITS = 36;
    /** A power of two, so that the index wraps with a mask. */
    private static final int SPREAD_VALUES = 1 << 16;

    private final LatencyHistogram histogram = new LatencyHistogram(PRECISION_BITS, RANGE_BITS);
    private long[] spread;
    private int next;

    @Setup(Level.Trial)
    public void generateSpread() {
        final Random random = new Random(SEED);
        final double logRange = Math.log(1L << RANGE_BITS);
        spread = new long[SPREAD_VALUES];
        for (int i = 0; i < SPREAD_VALUES; i++) {
            spread[i] = (long) Math.exp(random.nextDouble() * logRange);
        }
    }

    @Setup(Level.Iteration)
    public void resetHistogram() {
        histogram.reset();
    }

    @Benchmark
    public long timerPair() {
        final long start = System.nanoTime();
        return System.nanoTime() - start;
    }

    @Benchmark
    public void timerPairAndRecord() {
        final long start = System.nanoTime();
        histogram.record(System.nanoTime() - start);
    }

    @Benchmark
    public long readSpread() {
        final long value = spread[next];
        next = (next + 1) & (SPREAD_VALUES - 1);
        return value;
    }

    @Benchmark
    public void recordSpread() {
        histogram.record(spread[next]);
        next = (next + 1) & (SPREAD_VALUES - 1);
    }
}

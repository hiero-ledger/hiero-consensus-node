// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.datapoint.LongCounterDataPoint;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
public class LongAdderVsAtomicLongBenchmark {

    private LongCounterDataPoint atomicLongDataPoint;
    private LongCounterDataPoint longAdderDataPoint;

    @Setup(Level.Trial)
    public void setupGlobal() {
        atomicLongDataPoint = LongCounter.builder("atomic_long_counter")
                .withLowThreadContention()
                .build()
                .getOrCreateNotLabeled();
        longAdderDataPoint = LongCounter.builder("long_adder_counter").build().getOrCreateNotLabeled();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Fork(value = 1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 3, time = 3)
    @Threads(16)
    public void atomicLongCounter16Threads() {
        atomicLongDataPoint.increment();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Fork(value = 1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 3, time = 3)
    @Threads(2)
    public void atomicLongCounter2Threads() {
        atomicLongDataPoint.increment();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Fork(value = 1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 3, time = 3)
    @Threads(16)
    public void longAdderCounter16Threads() {
        longAdderDataPoint.increment();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Fork(value = 1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 3, time = 3)
    @Threads(2)
    public void longAdderCounter2Threads() {
        longAdderDataPoint.increment();
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/// Snapshot campaign microbenchmark of the shared-pool writer's skip check. Each chunk write first
/// reads the snapshot-wide failure reference, which all writer threads share and nobody sets unless
/// a write fails. Run it with as many threads as the pool has to measure the shared read.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class SnapshotFailureCheckBenchmark {

    /// The check the writers perform before each chunk.
    private final AtomicReference<Throwable> snapshotFailure = new AtomicReference<>();

    /// A plain field holding the same value, as the lowest possible cost of a read.
    private Throwable plainFailure;

    /// Reads the shared failure reference.
    ///
    /// @return whether a failure was recorded
    @Benchmark
    public boolean atomicReferenceCheck() {
        return snapshotFailure.get() != null;
    }

    /// Reads a plain field, for comparison.
    ///
    /// @return whether a failure was recorded
    @Benchmark
    public boolean plainFieldCheck() {
        return plainFailure != null;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.cache; // NOSONAR: Needed to benchmark internal classes

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 10, time = 15)
public class ConcurrentArrayBench {

    private static final Random RANDOM = new Random(12341);
    private static final int MIN_THREADS = 2;
    private static final int DEFAULT_ARRAY_SIZE = 1000;

    @Param({"1000000"})
    public int size;

    private ConcurrentArray<Long> concurrentArray;
    private ExecutorService executor;

    // Used in the benchmark to create concurrent arrays from streams
    private long[] source;

    @Setup(Level.Trial)
    public void setupInfrastructure() {
        int numCores = Runtime.getRuntime().availableProcessors();
        executor = Executors.newFixedThreadPool(Math.max(MIN_THREADS, numCores - 1));
    }

    @Setup(Level.Trial)
    public void setupSourceArray() {
        source = new long[size];
        for (int i = 0; i < size; i++) {
            source[i] = RANDOM.nextLong();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        executor.shutdownNow();
    }

    @Setup(Level.Iteration)
    public void setupPerIteration() {
        concurrentArray = new ConcurrentArray<>();
        // Add 1000 elements initially to the array.
        for (int i = 0; i < DEFAULT_ARRAY_SIZE; i++) {
            concurrentArray.add(RANDOM.nextLong());
        }
    }

    @Benchmark
    public void benchmarkCreateFromStream() {
        concurrentArray = new ConcurrentArray<>(Arrays.stream(source).boxed());
    }

    @Benchmark
    public void benchmarkGet() {
        concurrentArray.get(RANDOM.nextInt(DEFAULT_ARRAY_SIZE));
    }

    @Benchmark
    public void benchmarkSeal() {
        concurrentArray.seal();
    }

    @Benchmark
    public void benchmarkStream() {
        concurrentArray.seal();
        concurrentArray.stream();
    }

    @Benchmark
    public void benchmarkParallelTraverse() throws ExecutionException, InterruptedException {
        concurrentArray.seal();
        concurrentArray.parallelTraverse(executor, l -> {}).get();
    }

    @Benchmark
    public void benchmarkConcurrentOps() throws ExecutionException, InterruptedException {
        concurrentArray.seal();
        List<Future<?>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> concurrentArray.stream()));
        futures.add(executor.submit(() -> concurrentArray.stream()));
        concurrentArray.parallelTraverse(executor, i -> {}).get();
        for (Future<?> f : futures) {
            f.get();
        }
    }

    public ConcurrentArrayBench() {}
}

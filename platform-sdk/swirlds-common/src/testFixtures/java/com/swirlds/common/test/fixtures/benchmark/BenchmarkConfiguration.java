// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.benchmark;

import com.swirlds.common.merkle.MerkleNode;
import java.time.Duration;
import java.util.List;

/**
 * This class is used to configure and build a Benchmark object.
 *
 * @param <M>
 * 		the type of the merkle node state
 * @param <B>
 * 		the type of the benchmark state
 */
public class BenchmarkConfiguration<M extends MerkleNode, B extends BenchmarkMetadata> {

    private M initialState;
    private B initialBenchmarkState;
    private List<BenchmarkOperation<M, B>> mutableStateOperations;
    private List<BenchmarkOperation<M, B>> immutableStateOperations;
    private int immutableThreadCount;
    private Duration roundPeriod;
    private int statesInMemory;
    private Duration spinUpTime;
    private Duration testDuration;

    public BenchmarkConfiguration() {
        immutableThreadCount = 5;
        roundPeriod = Duration.ofMillis(100);
        statesInMemory = 10;
        spinUpTime = Duration.ofSeconds(5);
        testDuration = Duration.ofMinutes(1);
    }

    /**
     * Build a new benchmark.
     */
    public Benchmark<M, B> build() {
        final Benchmark<M, B> benchmark = new Benchmark<M, B>(
                initialState,
                initialBenchmarkState,
                mutableStateOperations,
                immutableStateOperations,
                immutableThreadCount,
                roundPeriod,
                statesInMemory,
                spinUpTime,
                testDuration);

        // These are modified by the benchmark, we must set them again if the configuration object is reused
        initialState = null;
        initialBenchmarkState = null;

        return benchmark;
    }

    /**
     * Get the initial merkle state.
     */
    public MerkleNode getInitialState() {
        return initialState;
    }

    /**
     * Set the initial merkle state.
     *
     * @return this object
     */
    public BenchmarkConfiguration<M, B> setInitialState(final M initialState) {
        this.initialState = initialState;
        return this;
    }

    /**
     * Get the initial non-merkle state for the benchmark.
     */
    public B getInitialBenchmarkState() {
        return initialBenchmarkState;
    }

    /**
     * Set the initial non-merkle state for the benchmark.
     *
     * @return this object
     */
    public BenchmarkConfiguration<M, B> setBenchmarkMetadata(final B initialBenchmarkState) {
        this.initialBenchmarkState = initialBenchmarkState;
        return this;
    }

    /**
     * Get the operations to perform on the mutable state.
     */
    public List<BenchmarkOperation<M, B>> getMutableStateOperations() {
        return mutableStateOperations;
    }

    /**
     * Set the operations to perform on the mutable state.
     *
     * @return this object
     */
    public BenchmarkConfiguration<M, B> setMutableStateOperations(
            final List<BenchmarkOperation<M, B>> mutableStateOperations) {
        this.mutableStateOperations = mutableStateOperations;
        return this;
    }

    /**
     * Get the operations to perform on the immutable state copies.
     */
    public List<BenchmarkOperation<M, B>> getImmutableStateOperations() {
        return immutableStateOperations;
    }

    /**
     * Set the operations to perform on the immutable state copies.
     *
     * @return this object
     */
    public BenchmarkConfiguration<M, B> setImmutableStateOperations(
            final List<BenchmarkOperation<M, B>> immutableStateOperations) {
        this.immutableStateOperations = immutableStateOperations;
        return this;
    }

    /**
     * Get the number of threads that will be running operations on immutable state copies.
     */
    public int getImmutableThreadCount() {
        return immutableThreadCount;
    }

    /**
     * Set the number of threads that will be running operations on immutable state copies.
     *
     * @return this object
     */
    public BenchmarkConfiguration<M, B> setImmutableThreadCount(final int immutableThreadCount) {
        this.immutableThreadCount = immutableThreadCount;
        return this;
    }

    /**
     * Get the time between copies of the state.
     */
    public Duration getRoundPeriod() {
        return roundPeriod;
    }

    /**
     * Set the time between copies of the state.
     *
     * @return this object
     */
    public BenchmarkConfiguration<M, B> setRoundPeriod(final Duration roundPeriod) {
        this.roundPeriod = roundPeriod;
        return this;
    }

    /**
     * Get the number of states that are kept in memory (excluding the mutable state).
     */
    public int getStatesInMemory() {
        return statesInMemory;
    }

    /**
     * Set the number of states that are kept in memory (excluding the mutable state).
     *
     * @return this object
     */
    public BenchmarkConfiguration<M, B> setStatesInMemory(final int statesInMemory) {
        this.statesInMemory = statesInMemory;
        return this;
    }

    /**
     * Get the time to wait before starting the collection of statistics.
     */
    public Duration getSpinUpTime() {
        return spinUpTime;
    }

    /**
     * Set the time to wait before starting the collection of statistics.
     *
     * @return this object
     */
    public BenchmarkConfiguration<M, B> setSpinUpTime(final Duration spinUpTime) {
        this.spinUpTime = spinUpTime;
        return this;
    }

    /**
     * Get the length of the test.
     */
    public Duration getTestDuration() {
        return testDuration;
    }

    /**
     * Set the length of the test.
     *
     * @return this object
     */
    public BenchmarkConfiguration<M, B> setTestDuration(final Duration testDuration) {
        this.testDuration = testDuration;
        return this;
    }
}

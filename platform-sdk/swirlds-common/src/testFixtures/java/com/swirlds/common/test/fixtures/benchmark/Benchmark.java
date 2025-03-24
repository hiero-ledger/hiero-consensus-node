// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.benchmark;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.utility.AutoCloseableWrapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.model.crypto.Hash;

/**
 * Runs a benchmark with a pool of operations.
 *
 * @param <S>
 * 		the type of the merkle node state
 * @param <M>
 * 		the type of the benchmark metadata
 */
public class Benchmark<S extends MerkleNode, M extends BenchmarkMetadata> {

    private static final Random random = new Random();
    private static final String copyStatisticsName = "copyState";
    private static final String hashStatisticsName = "hashState";
    private static final String deleteStatisticsName = "deleteState";
    private final List<BenchmarkOperation<S, M>> mutableStateOperations;
    private final List<BenchmarkOperation<S, M>> immutableStateOperations;
    private final int immutableThreadCount;
    private final Duration roundPeriod;
    private final int statesInMemory;
    private final Duration spinUpTime;
    private final Duration testDuration;
    private final MutableStateManager<S> mutableStateManager;
    private final ImmutableStateManager<S> immutableStateManager;
    private final BlockingQueue<S> statesToHash;
    private final BlockingQueue<S> statesToDelete;
    private final M metadata;
    private final Map<String, BenchmarkStatistic> statistics;
    private final BenchmarkStatistic copyStatistics;
    private final BenchmarkStatistic hashStatistics;
    private final BenchmarkStatistic deleteStatistics;
    private volatile boolean alive;
    private volatile boolean captureStatistics;

    /**
     * This constructor is intentionally package private
     *
     * @param initialState
     * 		the initial merkle state
     * @param metadata
     * 		the metadata object
     * @param mutableStateOperations
     * 		a list of operations to perform on the mutable state
     * @param immutableStateOperations
     * 		a list of operations to perform on the immutable state
     * @param immutableThreadCount
     * 		the number of threads running immutable operations
     * @param roundPeriod
     * 		the average time between copies of the state
     * @param statesInMemory
     * 		the number of state copies to keep in memory (excluding mutable copy)
     * @param spinUpTime
     * 		the amount of time to wait before starting to collect statistics
     * @param testDuration
     * 		the amount of time to run the test (after spinUpTime)
     */
    Benchmark(
            final S initialState,
            final M metadata,
            final List<BenchmarkOperation<S, M>> mutableStateOperations,
            final List<BenchmarkOperation<S, M>> immutableStateOperations,
            final int immutableThreadCount,
            final Duration roundPeriod,
            final int statesInMemory,
            final Duration spinUpTime,
            final Duration testDuration) {

        mutableStateManager = new MutableStateManager<>(initialState);
        this.metadata = metadata;

        this.mutableStateOperations = mutableStateOperations;

        this.immutableStateOperations = immutableStateOperations;

        this.immutableThreadCount = immutableThreadCount;
        this.roundPeriod = roundPeriod;
        this.statesInMemory = statesInMemory;
        this.spinUpTime = spinUpTime;
        this.testDuration = testDuration;

        immutableStateManager = new ImmutableStateManager<>();
        statesToHash = new LinkedBlockingQueue<>();
        statesToDelete = new LinkedBlockingQueue<>();

        alive = true;
        captureStatistics = false;
        statistics = new HashMap<>();

        copyStatistics = new BenchmarkStatistic(copyStatisticsName, testDuration);
        statistics.put("copyState", copyStatistics);
        hashStatistics = new BenchmarkStatistic(hashStatisticsName, testDuration);
        statistics.put("hashState", hashStatistics);
        deleteStatistics = new BenchmarkStatistic(deleteStatisticsName, testDuration);
        statistics.put("deleteState", deleteStatistics);

        for (final BenchmarkOperation<S, M> operation : mutableStateOperations) {
            registerStatisticForOperation(operation);
        }
        for (final BenchmarkOperation<S, M> operation : immutableStateOperations) {
            registerStatisticForOperation(operation);
        }
    }

    /**
     * Sleep for a while. Print a status update once per second.
     */
    private void waitWithStatusUpdates(final Duration timeToWait) {
        final Instant startTime = Instant.now();
        while (true) {

            final Instant now = Instant.now();
            final Duration timeRemaining = timeToWait.minus(Duration.between(startTime, now));
            if (timeRemaining.isNegative()) {
                break;
            }
            System.out.println(timeRemaining.toSeconds() + "s remaining");
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Print the configuration data for the test.
     */
    public void printSettings() {
        System.out.println("Mutable operations (1 thread)");
        for (final BenchmarkOperation<S, M> operation : mutableStateOperations) {
            System.out.println("    - " + operation);
        }

        System.out.println("Immutable operations (" + immutableThreadCount + " thread"
                + (immutableThreadCount == 1 ? "" : "s") + ")");
        for (final BenchmarkOperation<S, M> operation : immutableStateOperations) {
            System.out.println("    - " + operation);
        }

        System.out.println("Round period: " + roundPeriod.toMillis() + " milliseconds");
        System.out.println("States in memory: " + statesInMemory + " immutable states + 1 mutable state");
        System.out.println("Spin up time: " + spinUpTime.toSeconds() + " seconds");
        System.out.println("Test duration: " + testDuration.toSeconds() + " seconds");
    }

    /**
     * Run the benchmark.
     */
    public Collection<BenchmarkStatistic> run() throws InterruptedException {

        // Build threads
        // FUTURE WORK use thread configs once they merge
        final Thread copyThread = new Thread(this::copyThreadRunnable);
        final Thread hashingThread = new Thread(this::hashingThreadRunnable);
        hashingThread.setName("HASHING THREAD");
        final Thread deletionThread = new Thread(this::deletionThreadRunnable);
        final Thread mutableThread =
                new Thread(buildOperationRunnable(copyOperationList(mutableStateOperations), mutableStateManager));
        final List<Thread> immutableThreads = new LinkedList<>();
        for (int i = 0; i < immutableThreadCount; i++) {
            final Thread immutableThread = new Thread(
                    buildOperationRunnable(copyOperationList(immutableStateOperations), immutableStateManager));
            immutableThread.setName("immutable " + i);
            immutableThreads.add(immutableThread);
        }

        // Start the threads
        copyThread.start();
        hashingThread.start();
        deletionThread.start();
        mutableThread.start();
        immutableThreads.forEach(Thread::start);

        // Let the system spin up before starting to collect statistics
        System.out.println(
                "Waiting for " + spinUpTime.toSeconds() + " seconds to allow the system to reach a steady state");
        waitWithStatusUpdates(spinUpTime);
        captureStatistics = true;
        System.out.println("Spin up complete, gathering statistics");

        // Wait until the run is completed
        waitWithStatusUpdates(testDuration);

        // Don't capture statistics while the system is being torn down
        captureStatistics = false;

        // Stop the threads
        alive = false;
        System.out.println("joining copy thread");
        copyThread.join();
        System.out.println("joining hashing thread");
        hashingThread.join();
        System.out.println("joining deletion thread");
        deletionThread.join();
        System.out.println("joining mutable state thread");
        mutableThread.join();
        System.out.println("joining immutable state thread");
        for (final Thread thread : immutableThreads) {
            thread.join();
        }
        System.out.println("finished joining all threads");

        return statistics.values();
    }

    /**
     * Copy a list of operations.
     */
    private List<BenchmarkOperation<S, M>> copyOperationList(final List<BenchmarkOperation<S, M>> operations) {
        final List<BenchmarkOperation<S, M>> operationsCopy = new ArrayList<>(operations.size());
        for (final BenchmarkOperation<S, M> operation : operations) {
            operationsCopy.add(operation.copy());
        }
        return operationsCopy;
    }

    /**
     * Get the total weight of a list of operations.
     */
    private double getSumOfOperationWeights(final List<BenchmarkOperation<S, M>> operations) {
        double sum = 0;
        for (final BenchmarkOperation<S, M> operation : operations) {
            sum += operation.getWeight();
        }
        return sum;
    }

    /**
     * Register statistics for a given operation.
     */
    private void registerStatisticForOperation(final BenchmarkOperation<S, M> operation) {
        if (statistics.containsKey(operation.getName())) {
            throw new IllegalArgumentException(
                    "Operation names must be unique, but \"" + operation.getName() + "\" has already been used");
        }
        statistics.put(operation.getName(), new BenchmarkStatistic(operation.getName(), testDuration));
    }

    /**
     * Get the statistics for a given operation.
     */
    private BenchmarkStatistic getStatisticsForOperation(final BenchmarkOperation<S, M> operation) {
        if (!statistics.containsKey(operation.getName())) {
            throw new IllegalArgumentException("No statistics registered for operation " + operation.getName());
        }
        return statistics.get(operation.getName());
    }

    /**
     * Choose a random operation from a given list of operations based on weighted probability.
     *
     * @param operations
     * 		a list of possible operations
     * @param totalWeight
     * 		the sum of the weights of all operations in the list
     */
    private BenchmarkOperation<S, M> chooseRandomOperation(
            final List<BenchmarkOperation<S, M>> operations, double totalWeight) {

        final double choice = random.nextDouble() * totalWeight;

        double sum = 0;
        for (final BenchmarkOperation<S, M> operation : operations) {
            sum += operation.getWeight();
            if (sum >= choice) {
                return operation;
            }
        }

        throw new IllegalStateException("no operation was chosen");
    }

    /**
     * Run an operation and measure the time it takes.
     */
    private void runWithStatistics(final RunnableWithException operation, BenchmarkStatistic statistic) {
        try {
            final boolean captureStatistics = this.captureStatistics;
            if (captureStatistics) {
                statistic.start();
            }

            operation.run();

            if (captureStatistics) {
                statistic.stop();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * This thread makes copies of the state every once in a while.
     */
    @SuppressWarnings("unchecked")
    private void copyThreadRunnable() {
        while (alive) {
            try {
                Thread.sleep(roundPeriod.toMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
                return;
            }

            S originalState;
            try (final AutoCloseableWrapper<S> originalStateWrapper = mutableStateManager.getState()) {
                originalState = originalStateWrapper.get();
                runWithStatistics(() -> mutableStateManager.setState((S) originalState.copy()), copyStatistics);
            }

            immutableStateManager.getStates().addLast(originalState);
            statesToHash.add(originalState);
            if (immutableStateManager.getStates().size() > statesInMemory) {
                statesToDelete.add(immutableStateManager.getStates().removeFirst());
            }
        }
    }

    /**
     * This thread hashes immutable states after they have been copied.
     */
    private void hashingThreadRunnable() {
        try {
            while (alive) {
                final MerkleNode stateToHash;

                stateToHash = statesToHash.poll(100, TimeUnit.MILLISECONDS);

                if (stateToHash == null) {
                    continue;
                }

                runWithStatistics(
                        () -> {
                            final Future<Hash> future =
                                    TestMerkleCryptoFactory.getInstance().digestTreeAsync(stateToHash);
                            future.get();
                        },
                        hashStatistics);
            }
        } catch (InterruptedException e) { // FUTURE WORK unnecessary if this is a stoppable thread
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * This thread deletes old states.
     */
    private void deletionThreadRunnable() {
        try {
            while (alive) {
                final MerkleNode stateToDelete = statesToDelete.poll(100, TimeUnit.MILLISECONDS);
                if (stateToDelete == null) {
                    continue;
                }

                runWithStatistics(stateToDelete::release, deleteStatistics);
            }
        } catch (InterruptedException e) { // FUTURE WORK unnecessary if this is a stoppable thread
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * This thread performs benchmark specific operations.
     */
    private Runnable buildOperationRunnable(
            final List<BenchmarkOperation<S, M>> operations, final StateManager<S> stateManager) {

        final double operationsWeight = getSumOfOperationWeights(operations);

        return () -> {
            while (alive) {
                final BenchmarkOperation<S, M> operation = chooseRandomOperation(operations, operationsWeight);
                final BenchmarkStatistic statistic = getStatisticsForOperation(operation);

                operation.prepare(metadata, random);
                if (operation.shouldAbort()) {
                    continue;
                }

                try (final AutoCloseableWrapper<S> merkleStateWrapper = stateManager.getState()) {
                    if (merkleStateWrapper.get() == null) {
                        // This may happen during spin up
                        continue;
                    }
                    runWithStatistics(() -> operation.execute(merkleStateWrapper.get()), statistic);
                }
            }
        };
    }

    /**
     * Utility interface, describes a runnable that can throw an exception.
     */
    @FunctionalInterface
    private interface RunnableWithException {
        void run() throws Exception;
    }
}

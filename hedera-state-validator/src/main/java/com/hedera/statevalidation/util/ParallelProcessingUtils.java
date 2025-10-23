// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util;

import static com.hedera.statevalidation.util.ConfigUtils.PARALLELISM;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.stream.LongStream;

/**
 * Utility methods for parallel processing using a shared {@link ForkJoinPool}.
 */
public final class ParallelProcessingUtils {

    private ParallelProcessingUtils() {}

    public static final ForkJoinPool VALIDATOR_FORK_JOIN_POOL;

    static {
        VALIDATOR_FORK_JOIN_POOL = new ForkJoinPool(PARALLELISM);
    }

    public static ForkJoinTask<?> processRange(long start, long end, LongConsumer consumer) {
        return VALIDATOR_FORK_JOIN_POOL.submit(
                () -> LongStream.range(start, end).parallel().forEach(consumer));
    }

    public static <T> ForkJoinTask<?> processObjects(List<T> objectsToProcess, Consumer<T> consumer) {
        return VALIDATOR_FORK_JOIN_POOL.submit(
                () -> objectsToProcess.stream().parallel().forEach(consumer));
    }

    public static ForkJoinTask<?> doNothing() {
        return VALIDATOR_FORK_JOIN_POOL.submit(() -> {});
    }

    public static <T> ForkJoinTask<T> submitSingleTask(Callable<T> task) {
        return VALIDATOR_FORK_JOIN_POOL.submit(task);
    }
}

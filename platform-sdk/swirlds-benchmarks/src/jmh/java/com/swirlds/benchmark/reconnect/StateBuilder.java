// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.benchmark.BenchmarkValue;
import com.swirlds.benchmark.BenchmarkValueCodec;
import com.swirlds.virtualmap.VirtualMap;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.LongStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A utility class to help build and populate virtual map states for benchmarks.
 */
public record StateBuilder(
        // Build a key for a given index.
        Function<Long, Bytes> keyBuilder,
        // Build a value for a given index.
        Function<Long, BenchmarkValue> valueBuilder) {

    private static final Logger logger = LogManager.getLogger(StateBuilder.class);

    /**
     * Builds a VirtualMap populator that is able to add/update, as well as remove nodes (when the value is null).
     * Note that it doesn't support explicitly adding null values under a key.
     *
     * @param mapRef a reference to a VirtualMap instance (allows swapping the map during population, e.g. via copyMap)
     * @return a populator for the map
     */
    public static BiConsumer<Bytes, BenchmarkValue> buildVMPopulator(final AtomicReference<VirtualMap> mapRef) {
        return (k, v) -> {
            if (v == null) {
                mapRef.get().remove(k, BenchmarkValueCodec.INSTANCE);
            } else {
                mapRef.get().put(k, v, BenchmarkValueCodec.INSTANCE);
            }
        };
    }

    /** Return {@code true} with the given probability. */
    private static boolean isRandomOutcome(final Random random, final double probability) {
        return random.nextDouble(1.) < probability;
    }

    /**
     * Populate state(s) by iterating over the key range [fromIndex, toIndex) and passing each
     * generated key/value pair to all provided populators, with periodic storage optimization.
     *
     * @param fromIndex the first index for key generation (inclusive)
     * @param toIndex the end index for key generation (exclusive)
     * @param storageOptimizer called with each index to allow periodic map copies, flushes, etc.
     * @param populators one or more consumers that receive (key, value) pairs
     */
    @SafeVarargs
    public final void populateState(
            final long fromIndex,
            final long toIndex,
            final Consumer<Long> storageOptimizer,
            final BiConsumer<Bytes, BenchmarkValue>... populators) {
        LongStream.range(fromIndex, toIndex).forEach(i -> {
            storageOptimizer.accept(i);
            final Bytes key = keyBuilder.apply(i);
            final BenchmarkValue value = valueBuilder.apply(i);
            for (final BiConsumer<Bytes, BenchmarkValue> populator : populators) {
                populator.accept(key, value);
            }
        });
    }

    /**
     * Build a random state and pass it to the provided teacher and learner populators.
     * <p>
     * The process starts by creating two identical states with the specified size for both the teacher and the learner.
     * It then uses the provided probabilities to modify the teacher state in order to emulate a scenario
     * where the learner has disconnected from the network and hasn't updated its map with the latest changes:
     * <ul>
     *     <li>teacherAddProbability - a new node is added to the teacher state
     *     <li>teacherRemoveProbability - an existing node is removed from the teacher state
     *     <li>teacherModifyProbability - an existing node is updated with a new value in the teacher state
     * </ul>
     * <p>
     * Note that the state populators must correctly support additions, updates, and removals (when the value is null).
     *
     * @param random a Random instance
     * @param size the number of nodes in the learner state.
     *          The teacher may have a slightly different number of nodes depending on the probabilities below.
     * @param teacherAddProbability the probability of a key to be added to the teacher state
     * @param teacherRemoveProbability the probability of a key to be removed from the teacher state
     * @param teacherModifyProbability the probability of a node under a given key in the teacher state
     *          to have a value that is different from the value under the same key in the learner state.
     * @param teacherPopulator a BiConsumer that persists the teacher state (Map::put or similar)
     * @param learnerPopulator a BiConsumer that persists the learner state (Map::put or similar)
     * @param storageOptimizer a Consumer<Long> that could optimize the underlying state storage
     *          (e.g. compacting it, or splitting it into multiple units such as files, etc.)
     *          based on the current node index passed as a parameter
     */
    public void buildState(
            final Random random,
            final long size,
            final double teacherAddProbability,
            final double teacherRemoveProbability,
            final double teacherModifyProbability,
            final BiConsumer<Bytes, BenchmarkValue> teacherPopulator,
            final BiConsumer<Bytes, BenchmarkValue> learnerPopulator,
            final Consumer<Long> storageOptimizer) {
        logger.info("Building a state of size {}", size);

        // Phase 1: populate both teacher and learner identically with keys 1..size-1
        populateState(1, size, storageOptimizer, teacherPopulator, learnerPopulator);

        // Phase 2: apply teacher-specific mutations
        final AtomicLong curSize = new AtomicLong(size - 1);

        LongStream.range(1, size).forEach(i -> {
            storageOptimizer.accept(i);

            // Make all random outcomes independent of each other:
            final boolean teacherAdd = isRandomOutcome(random, teacherAddProbability);
            final boolean teacherModify = isRandomOutcome(random, teacherModifyProbability);
            final boolean teacherRemove = isRandomOutcome(random, teacherRemoveProbability);

            if (teacherAdd) {
                final Bytes key = keyBuilder.apply(i + size);
                // Added values indexes (size + 1)..(2 * size)
                final BenchmarkValue value = valueBuilder.apply(i + size);
                teacherPopulator.accept(key, value);
                curSize.incrementAndGet();
            }

            final long iModify = random.nextLong(curSize.get()) + 1;
            final long iRemove = random.nextLong(curSize.get()) + 1;

            if (teacherModify) {
                final Bytes key = keyBuilder.apply(iModify);
                // Modified values indexes (2 * size + 1)..(3 * size)
                final BenchmarkValue value = valueBuilder.apply(iModify + 2L * size);
                teacherPopulator.accept(key, value);
            }

            if (teacherRemove) {
                final Bytes key = keyBuilder.apply(iRemove);
                teacherPopulator.accept(key, null);
                curSize.decrementAndGet();
            }
        });
    }
}

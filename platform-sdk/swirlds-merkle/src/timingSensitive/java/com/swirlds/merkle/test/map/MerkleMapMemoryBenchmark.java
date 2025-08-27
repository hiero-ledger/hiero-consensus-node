// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.map;

import com.swirlds.common.merkle.utility.KeyedMerkleLong;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.merkle.map.MerkleMap;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * This tool provides a set of benchmarks that can be used to profile the memory usage of a MerkleMap.
 * The results of these benchmarks are intended to be interpreted using jprofiler.
 */
@DisplayName("MerkleMap Memory Benchmark")
class MerkleMapMemoryBenchmark {

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.MerkleMap");
        registry.registerConstructables("com.swirlds.merkletree");
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("org.hiero");
    }

    /**
     * Build a MerkleMap of the requested size. Map is returned fully hashed.
     */
    private MerkleMap<SerializableLong, KeyedMerkleLong<SerializableLong>> buildMap(final Random random, final int size)
            throws ExecutionException, InterruptedException {

        final MerkleMap<SerializableLong, KeyedMerkleLong<SerializableLong>> map = new MerkleMap<>(size);

        System.out.println("building map");
        for (int i = 0; i < size; i++) {
            map.put(new SerializableLong(random.nextInt()), new KeyedMerkleLong<>(random.nextInt()));
        }

        System.out.println("hashing map");
        TestMerkleCryptoFactory.getInstance().digestTreeAsync(map).get();

        return map;
    }

    /**
     * These tests need to be manually killed.
     */
    void pauseForever() throws InterruptedException {
        System.out.println("Pausing for a very long time.");
        Thread.sleep(Long.MAX_VALUE);
    }

    /**
     * Build a map of a given size then pause forever.
     */
    @Test
    @EnabledIfEnvironmentVariable(disabledReason = "Benchmark", named = "benchmark", matches = "true")
    void runBenchmark() throws ExecutionException, InterruptedException {
        buildMap(new Random(), 3_000_000);
        pauseForever();
    }
}

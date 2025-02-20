// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.benchmark;

import com.swirlds.common.test.fixtures.benchmark.BenchmarkMetadata;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Used to run benchmarks against the MerkleMap.
 */
public class MerkleMapBenchmarkMetadata implements BenchmarkMetadata {

    private final RandomKeySet<Long> keys;
    private long nextKey;

    private final long networkFeeKey;
    private final List<Long> nodeFeeKeys;

    private final Set<Long> undeletableKeys;

    private static final int NUMBER_OF_NODES = 13;

    public MerkleMapBenchmarkMetadata() {
        keys = new RandomKeySet<>();

        networkFeeKey = nextKey++;
        nodeFeeKeys = new ArrayList<>(NUMBER_OF_NODES);
        for (int i = 0; i < NUMBER_OF_NODES; i++) {
            nodeFeeKeys.add(nextKey++);
        }

        undeletableKeys = new HashSet<>();
    }

    /**
     * Get a new key. It is expected that this key will be inserted into the map.
     */
    public synchronized BenchmarkKey getNewKey() {
        final long key = nextKey++;
        keys.add(key);
        return new BenchmarkKey(key);
    }

    /**
     * Get a random key.
     */
    public synchronized BenchmarkKey getRandomKey(final Random random) {
        return new BenchmarkKey(keys.getRandomKey(random));
    }

    /**
     * Delete a given key.
     */
    public synchronized void deleteKey(final BenchmarkKey key) {
        keys.remove(key.getValue());
    }

    /**
     * Get the key to which the network fee is paid.
     */
    public BenchmarkKey getNetworkFeeKey() {
        return new BenchmarkKey(networkFeeKey);
    }

    /**
     * Get a random node for a node fee.
     */
    public BenchmarkKey getRandomNodeFeeKey(final Random random) {
        return new BenchmarkKey(nodeFeeKeys.get(random.nextInt(nodeFeeKeys.size())));
    }

    /**
     * Get a list of keys for all nodes that collect fees.
     */
    public List<BenchmarkKey> getNodeFeeKeys() {
        final List<BenchmarkKey> keys = new LinkedList<>();
        for (final long key : nodeFeeKeys) {
            keys.add(new BenchmarkKey(key));
        }
        return keys;
    }

    /**
     * Mark a key as undeletable.
     */
    public void markKeyAsUndeletable(final BenchmarkKey key) {
        undeletableKeys.add(key.getValue());
    }

    /**
     * Remove the undeletable status from a key.
     */
    public void markKeyAsDeletable(final BenchmarkKey key) {
        undeletableKeys.remove(key.getValue());
    }

    /**
     * Check if a key is undeletable.
     */
    public boolean isKeyUndeletable(final BenchmarkKey key) {
        return undeletableKeys.contains(key.getValue());
    }
}

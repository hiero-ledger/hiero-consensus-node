// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.benchmark;

import com.swirlds.merkle.map.MerkleMap;
import java.util.Random;
import org.hiero.consensus.utility.test.fixtures.RandomUtils;

public final class MerkleMapBenchmarkUtils {

    private MerkleMapBenchmarkUtils() {}

    /**
     * Pre-populate a map with a bunch of accounts.
     *
     * @param random
     * 		a source of randomness
     * @param dataSize
     * 		the size of the auxiliary data for the account
     * @param benchmarkMetadata
     * 		metadata for the benchmark
     * @param size
     * 		the number of accounts to create
     * @param accountFactory
     * 		an object which can create accounts of the proper type
     */
    public static <A extends BenchmarkAccount> MerkleMap<BenchmarkKey, A> generateInitialState(
            final Random random,
            final int dataSize,
            final MerkleMapBenchmarkMetadata benchmarkMetadata,
            final int size,
            AccountFactory<A> accountFactory) {

        final MerkleMap<BenchmarkKey, A> map = new MerkleMap<>();

        // Create account for network fees
        map.put(
                benchmarkMetadata.getNetworkFeeKey(),
                accountFactory.buildAccount(random.nextLong(), RandomUtils.randomByteArray(random, dataSize)));

        // Create accounts for node fees
        for (final BenchmarkKey key : benchmarkMetadata.getNodeFeeKeys()) {
            map.put(key, accountFactory.buildAccount(random.nextLong(), RandomUtils.randomByteArray(random, dataSize)));
        }

        // Create a bunch of user accounts
        for (int i = 0; i < size; i++) {
            final BenchmarkKey key = benchmarkMetadata.getNewKey();
            map.put(key, accountFactory.buildAccount(random.nextLong(), RandomUtils.randomByteArray(random, dataSize)));
        }

        return map;
    }
}

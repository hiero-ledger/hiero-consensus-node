// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.benchmark.operations;

import com.swirlds.common.test.fixtures.benchmark.AbstractBenchmarkOperation;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.benchmark.BenchmarkAccount;
import com.swirlds.merkle.test.fixtures.map.benchmark.BenchmarkKey;
import com.swirlds.merkle.test.fixtures.map.benchmark.MerkleMapBenchmarkMetadata;
import java.util.Random;

/**
 * Simulates a read-only operation that looks at a balance.
 *
 * @param <A>
 * 		the type of the account
 * @param <M>
 * 		the type of the metadata
 */
public class ReadBalanceOperation<A extends BenchmarkAccount, M extends MerkleMapBenchmarkMetadata>
        extends AbstractBenchmarkOperation<MerkleMap<BenchmarkKey, A>, M> {

    private BenchmarkKey key;

    public ReadBalanceOperation(final double weight) {
        super(weight);
    }

    private ReadBalanceOperation(final ReadBalanceOperation<A, M> that) {
        super(that.getWeight());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return "readBalance";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void prepare(final M metadata, final Random random) {
        key = metadata.getRandomKey(random);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(final MerkleMap<BenchmarkKey, A> state) {
        state.get(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ReadBalanceOperation<A, M> copy() {
        return new ReadBalanceOperation<>(this);
    }
}

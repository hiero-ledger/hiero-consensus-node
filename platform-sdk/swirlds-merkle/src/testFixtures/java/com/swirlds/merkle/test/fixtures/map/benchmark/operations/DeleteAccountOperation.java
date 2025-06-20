// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.benchmark.operations;

import com.swirlds.common.test.fixtures.benchmark.AbstractBenchmarkOperation;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.benchmark.BenchmarkAccount;
import com.swirlds.merkle.test.fixtures.map.benchmark.BenchmarkKey;
import com.swirlds.merkle.test.fixtures.map.benchmark.MerkleMapBenchmarkMetadata;
import java.util.Random;

/**
 * Simulate the deletion of an account.
 *
 * @param <A>
 * 		the type of the account
 * @param <M>
 * 		the type of the metadata
 */
public class DeleteAccountOperation<A extends BenchmarkAccount, M extends MerkleMapBenchmarkMetadata>
        extends AbstractBenchmarkOperation<MerkleMap<BenchmarkKey, A>, M> {

    private BenchmarkKey deleteKey;

    public DeleteAccountOperation(final double weight) {
        super(weight);
    }

    private DeleteAccountOperation(final DeleteAccountOperation<A, M> that) {
        super(that.getWeight());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return "deleteAccount";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void prepare(final M metadata, final Random random) {
        deleteKey = metadata.getRandomKey(random);
        if (metadata.isKeyUndeletable(deleteKey)) {
            abort();
            return;
        }
        metadata.deleteKey(deleteKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(final MerkleMap<BenchmarkKey, A> state) {
        state.remove(deleteKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DeleteAccountOperation<A, M> copy() {
        return new DeleteAccountOperation<>(this);
    }
}

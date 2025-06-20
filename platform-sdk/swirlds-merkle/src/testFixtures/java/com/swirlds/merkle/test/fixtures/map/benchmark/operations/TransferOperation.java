// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.benchmark.operations;

import com.swirlds.common.test.fixtures.benchmark.AbstractBenchmarkOperation;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.benchmark.BenchmarkAccount;
import com.swirlds.merkle.test.fixtures.map.benchmark.BenchmarkKey;
import com.swirlds.merkle.test.fixtures.map.benchmark.MerkleMapBenchmarkMetadata;
import java.util.Random;

/**
 * Simulate the transfer of a balance between two accounts.
 *
 * @param <A>
 * 		the type of the account
 * @param <M>
 * 		the type of the metadata
 */
public class TransferOperation<A extends BenchmarkAccount, M extends MerkleMapBenchmarkMetadata>
        extends AbstractBenchmarkOperation<MerkleMap<BenchmarkKey, A>, M> {

    private BenchmarkKey fromKey;
    private long newFromBalance;

    private BenchmarkKey toKey;
    private long newToBalance;

    private BenchmarkKey nodeFeeKey;
    private long newNodeBalance;

    private BenchmarkKey networkFeeKey;
    private long newNetworkBalance;

    public TransferOperation(final double weight) {
        super(weight);
    }

    private TransferOperation(final TransferOperation<A, M> that) {
        super(that.getWeight());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return "transfer";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void prepare(final M metadata, final Random random) {
        fromKey = metadata.getRandomKey(random);
        toKey = metadata.getRandomKey(random);

        if (fromKey == null || toKey == null || fromKey == toKey) {
            abort();
            return;
        }

        newFromBalance = random.nextLong();
        newToBalance = random.nextLong();

        nodeFeeKey = metadata.getRandomNodeFeeKey(random);
        newNodeBalance = random.nextLong();

        networkFeeKey = metadata.getNetworkFeeKey();
        newNetworkBalance = random.nextLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(final MerkleMap<BenchmarkKey, A> state) {

        final BenchmarkAccount fromAccount = state.getForModify(fromKey);
        final BenchmarkAccount toAccount = state.getForModify(toKey);
        final BenchmarkAccount nodeAccount = state.getForModify(nodeFeeKey);
        final BenchmarkAccount networkAccount = state.getForModify(networkFeeKey);

        fromAccount.setBalance(newFromBalance);
        toAccount.setBalance(newToBalance);
        nodeAccount.setBalance(newNodeBalance);
        networkAccount.setBalance(newNetworkBalance);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TransferOperation<A, M> copy() {
        return new TransferOperation<>(this);
    }
}

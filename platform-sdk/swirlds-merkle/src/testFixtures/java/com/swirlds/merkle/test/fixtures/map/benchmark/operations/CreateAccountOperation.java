// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.benchmark.operations;

import com.swirlds.common.test.fixtures.benchmark.AbstractBenchmarkOperation;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.benchmark.AccountFactory;
import com.swirlds.merkle.test.fixtures.map.benchmark.BenchmarkAccount;
import com.swirlds.merkle.test.fixtures.map.benchmark.BenchmarkKey;
import com.swirlds.merkle.test.fixtures.map.benchmark.MerkleMapBenchmarkMetadata;
import java.util.Random;
import org.hiero.consensus.utility.test.fixtures.RandomUtils;

/**
 * Simulate the creation of an account.
 *
 * @param <A>
 * 		the type of the account
 * @param <M>
 * 		the type of the metadata
 */
public class CreateAccountOperation<A extends BenchmarkAccount, M extends MerkleMapBenchmarkMetadata>
        extends AbstractBenchmarkOperation<MerkleMap<BenchmarkKey, A>, M> {

    private BenchmarkKey fromKey;
    private long newFromBalance;

    private BenchmarkKey newAccountKey;
    private A newAccount;

    private BenchmarkKey nodeFeeKey;
    private long newNodeBalance;

    private BenchmarkKey networkFeeKey;
    private long newNetworkBalance;

    private final int dataSize;

    private AccountFactory<A> accountFactory;

    public CreateAccountOperation(final double weight, final int dataSize, final AccountFactory<A> accountFactory) {
        super(weight);
        this.dataSize = dataSize;
        this.accountFactory = accountFactory;
    }

    private CreateAccountOperation(final CreateAccountOperation<A, M> that) {
        super(that.getWeight());
        this.dataSize = that.dataSize;
        this.accountFactory = that.accountFactory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return "createAccount";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void prepare(final M metadata, final Random random) {
        fromKey = metadata.getRandomKey(random);
        newFromBalance = random.nextLong();

        newAccountKey = metadata.getNewKey();
        newAccount = accountFactory.buildAccount(random.nextLong(), RandomUtils.randomByteArray(random, dataSize));

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

        // Simulate fees for the account creation
        final A fromAccount = state.getForModify(fromKey);
        final A nodeAccount = state.getForModify(nodeFeeKey);
        final A networkAccount = state.getForModify(networkFeeKey);

        fromAccount.setBalance(newFromBalance);
        nodeAccount.setBalance(newNodeBalance);
        networkAccount.setBalance(newNetworkBalance);

        // Actually create the account
        state.put(newAccountKey, newAccount);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CreateAccountOperation<A, M> copy() {
        return new CreateAccountOperation<>(this);
    }
}

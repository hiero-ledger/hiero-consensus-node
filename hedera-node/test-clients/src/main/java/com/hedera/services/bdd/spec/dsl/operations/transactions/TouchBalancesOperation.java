// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.transactions;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.SpecEntity;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Returns an operation that touches the balances of the given accounts, for example to
 * trigger their staking rewards.
 */
public class TouchBalancesOperation extends AbstractSpecTransaction<TouchBalancesOperation, HapiCryptoTransfer>
        implements SpecOperation {
    private final List<SpecAccount> accounts;

    private int expectedStakingRewardCount = -1;

    public TouchBalancesOperation(@NonNull final SpecAccount... accounts) {
        super(List.of(requireNonNull(accounts)));
        this.accounts = List.of(accounts);
    }

    public static TouchBalancesOperation touchBalanceOf(@NonNull final SpecAccount... accounts) {
        return new TouchBalancesOperation(accounts);
    }

    public TouchBalancesOperation andAssertStakingRewardCount(final int expectedStakingRewardCount) {
        this.expectedStakingRewardCount = expectedStakingRewardCount;
        return this;
    }

    @Override
    protected TouchBalancesOperation self() {
        return this;
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull HapiSpec spec) {
        final var op = cryptoTransfer(movingHbar(accounts.size())
                .distributing(
                        DEFAULT_PAYER, accounts.stream().map(SpecEntity::name).toArray(String[]::new)));
        return expectedStakingRewardCount == -1
                ? op
                : blockingOrder(
                        op.via(this.toString()),
                        getTxnRecord(this.toString()).hasPaidStakingRewardsCount(expectedStakingRewardCount));
    }
}

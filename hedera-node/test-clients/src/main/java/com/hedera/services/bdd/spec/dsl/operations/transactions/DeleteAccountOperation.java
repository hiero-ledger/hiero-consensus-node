// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.transactions;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.operations.AbstractSpecOperation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Deletes an account and transfers its balance to a beneficiary account.
 */
public class DeleteAccountOperation extends AbstractSpecOperation implements SpecOperation {
    private final SpecAccount target;
    private final SpecAccount beneficiary;

    public DeleteAccountOperation(@NonNull final SpecAccount target, @NonNull final SpecAccount beneficiary) {
        super(List.of(target, beneficiary));
        this.target = target;
        this.beneficiary = beneficiary;
    }

    @Override
    protected @NonNull SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        return cryptoDelete(target.name()).transfer(beneficiary.name());
    }

    @Override
    public String toString() {
        return "DeleteAccountOperation{" + "target=" + target + ", beneficiary=" + beneficiary + '}';
    }
}

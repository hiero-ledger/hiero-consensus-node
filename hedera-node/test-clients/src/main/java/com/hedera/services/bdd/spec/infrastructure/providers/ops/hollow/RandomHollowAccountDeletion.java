// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow;

import static com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow.RandomHollowAccount.ACCOUNT_SUFFIX;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.CRYPTO_TRANSFER_RECEIVER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomHollowAccountDeletion implements OpProvider {
    private final RegistrySourcedNameProvider<AccountID> accounts;
    private final ResponseCodeEnum[] permissiblePrechecks = standardPrechecksAnd(ACCOUNT_DELETED, INVALID_ACCOUNT_ID);
    private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(ACCOUNT_DELETED, INVALID_ACCOUNT_ID);

    public RandomHollowAccountDeletion(RegistrySourcedNameProvider<AccountID> accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        return accounts.getQualifying().filter(a -> a.endsWith(ACCOUNT_SUFFIX)).map(this::accountDeleteOp);
    }

    private HapiSpecOperation accountDeleteOp(String account) {
        return cryptoDelete(account)
                .purging()
                .transfer(CRYPTO_TRANSFER_RECEIVER)
                .hasPrecheckFrom(permissiblePrechecks)
                .hasKnownStatusFrom(permissibleOutcomes)
                .noLogging();
    }
}

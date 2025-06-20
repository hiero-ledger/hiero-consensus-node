// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.LookupUtils;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoDelete;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomAccountDeletion implements OpProvider {
    private final RegistrySourcedNameProvider<AccountID> accounts;
    private final ResponseCodeEnum[] permissiblePrechecks = standardPrechecksAnd(ACCOUNT_DELETED, INVALID_ACCOUNT_ID);
    private final ResponseCodeEnum[] permissibleOutcomes =
            standardOutcomesAnd(ACCOUNT_DELETED, INVALID_ACCOUNT_ID, TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES);
    private final ResponseCodeEnum[] customOutcomes;

    public RandomAccountDeletion(RegistrySourcedNameProvider<AccountID> accounts, ResponseCodeEnum[] customOutcomes) {
        this.accounts = accounts;
        this.customOutcomes = customOutcomes;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        final var involved = LookupUtils.twoDistinct(accounts);
        if (involved.isEmpty()) {
            return Optional.empty();
        }
        HapiCryptoDelete op = cryptoDelete(involved.get().getKey())
                .purging()
                .transfer(involved.get().getValue())
                .hasPrecheckFrom(plus(permissiblePrechecks, customOutcomes))
                .hasKnownStatusFrom(plus(permissibleOutcomes, customOutcomes));
        return Optional.of(op);
    }
}

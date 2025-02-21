// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountRecords;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomAccountRecords implements OpProvider {
    private final RegistrySourcedNameProvider<AccountID> accounts;

    private final ResponseCodeEnum[] permissibleCostAnswerPrechecks = standardPrechecksAnd(ACCOUNT_DELETED);
    private final ResponseCodeEnum[] permissibleAnswerOnlyPrechecks =
            standardPrechecksAnd(ACCOUNT_DELETED, INSUFFICIENT_TX_FEE);

    public RandomAccountRecords(RegistrySourcedNameProvider<AccountID> accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        Optional<String> account = accounts.getQualifying();
        if (account.isEmpty()) {
            return Optional.empty();
        }

        HapiGetAccountRecords op = getAccountRecords(account.get())
                .hasCostAnswerPrecheckFrom(permissibleCostAnswerPrechecks)
                .hasAnswerOnlyPrecheckFrom(permissibleAnswerOnlyPrechecks);

        return Optional.of(op);
    }
}

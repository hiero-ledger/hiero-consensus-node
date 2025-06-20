// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomAccountInfo implements OpProvider {
    private final RegistrySourcedNameProvider<AccountID> accounts;

    private final ResponseCodeEnum[] permissibleCostAnswerPrechecks = standardQueryPrechecksAnd(ACCOUNT_DELETED);
    private final ResponseCodeEnum[] permissibleAnswerOnlyPrechecks = standardQueryPrechecksAnd(ACCOUNT_DELETED);

    public RandomAccountInfo(RegistrySourcedNameProvider<AccountID> accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        Optional<String> account = accounts.getQualifying();
        if (account.isEmpty()) {
            return Optional.empty();
        }

        var op = getAccountInfo(account.get())
                .hasCostAnswerPrecheckFrom(permissibleCostAnswerPrechecks)
                .hasAnswerOnlyPrecheckFrom(permissibleAnswerOnlyPrechecks);

        return Optional.of(op);
    }
}

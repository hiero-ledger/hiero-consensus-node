// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.contract;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_DELETED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.queries.contract.HapiContractCallLocal;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomCallLocal implements OpProvider {
    private final EntityNameProvider localCalls;

    private final ResponseCodeEnum[] permissibleCostAnswerPrechecks = standardPrechecksAnd(CONTRACT_DELETED);
    private final ResponseCodeEnum[] permissibleAnswerOnlyPrechecks = standardPrechecksAnd(CONTRACT_DELETED);
    private final ResponseCodeEnum[] customOutcomes;

    public RandomCallLocal(EntityNameProvider localCalls, ResponseCodeEnum[] customOutcomes) {
        this.localCalls = localCalls;
        this.customOutcomes = customOutcomes;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        Optional<String> localCall = localCalls.getQualifying();
        if (localCall.isEmpty()) {
            return Optional.empty();
        }

        HapiContractCallLocal op = QueryVerbs.contractCallLocalFrom(localCall.get())
                .hasCostAnswerPrecheckFrom(permissibleCostAnswerPrechecks)
                .hasAnswerOnlyPrecheckFrom(plus(permissibleAnswerOnlyPrechecks, customOutcomes));

        return Optional.of(op);
    }
}

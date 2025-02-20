// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.contract;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallFrom;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomCall implements OpProvider {
    private final EntityNameProvider calls;

    private final ResponseCodeEnum[] permissiblePrechecks = standardPrechecksAnd(CONTRACT_DELETED);
    private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(INVALID_CONTRACT_ID, CONTRACT_DELETED);
    private final ResponseCodeEnum[] customOutcomes;

    public RandomCall(EntityNameProvider calls, ResponseCodeEnum[] customOutcomes) {
        this.calls = calls;
        this.customOutcomes = customOutcomes;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        Optional<String> call = calls.getQualifying();
        if (call.isEmpty()) {
            return Optional.empty();
        }

        HapiContractCall op = contractCallFrom(call.get())
                .hasPrecheckFrom(permissiblePrechecks)
                .hasKnownStatusFrom(plus(permissibleOutcomes, customOutcomes));

        return Optional.of(op);
    }
}

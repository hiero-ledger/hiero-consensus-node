// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.suites.regression.factories.AccountCompletionFuzzingFactory.CONTRACT;

import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hederahashgraph.api.proto.java.AccountID;

public class RandomContractCallSignedBy extends RandomOperationSignedBy<HapiContractCall> {

    public RandomContractCallSignedBy(HapiSpecRegistry registry, RegistrySourcedNameProvider<AccountID> accounts) {
        super(registry, accounts);
    }

    @Override
    protected HapiTxnOp<HapiContractCall> hapiTxnOp(String keyName) {
        return contractCall(CONTRACT);
    }
}

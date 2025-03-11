// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow;

import static com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow.RandomHollowAccount.ACCOUNT_SUFFIX;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.suites.regression.factories.AccountCompletionFuzzingFactory.VANILLA_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenAssociate;
import com.hederahashgraph.api.proto.java.AccountID;

public class RandomTokenAssociateSignedBy extends RandomOperationSignedBy<HapiTokenAssociate> {
    public RandomTokenAssociateSignedBy(HapiSpecRegistry registry, RegistrySourcedNameProvider<AccountID> accounts) {
        super(registry, accounts);
    }

    @Override
    protected HapiTxnOp<HapiTokenAssociate> hapiTxnOp(String keyName) {
        return tokenAssociate(keyName + ACCOUNT_SUFFIX, VANILLA_TOKEN).hasCostAnswerPrecheckFrom(OK, ACCOUNT_DELETED);
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.meta;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECEIPT_NOT_FOUND;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.queries.meta.HapiGetReceipt;
import com.hedera.services.bdd.spec.transactions.TxnFactory;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.util.Optional;

public class RandomReceipt implements OpProvider {
    private final TxnFactory txns;

    public RandomReceipt(TxnFactory txns) {
        this.txns = txns;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        TransactionID txnId = txns.sampleRecentTxnId();
        if (txnId == TransactionID.getDefaultInstance()) {
            return Optional.empty();
        } else {
            HapiGetReceipt op = getReceipt(txnId).hasAnswerOnlyPrecheckFrom(OK, RECEIPT_NOT_FOUND);
            return Optional.of(op);
        }
    }
}

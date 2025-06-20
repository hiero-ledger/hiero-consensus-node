// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.TransactionBody;

public class TokenFreezeUsage extends TokenTxnUsage<TokenFreezeUsage> {
    private TokenFreezeUsage(TransactionBody tokenFreezeOp, TxnUsageEstimator usageEstimator) {
        super(tokenFreezeOp, usageEstimator);
    }

    public static TokenFreezeUsage newEstimate(TransactionBody tokenFreezeOp, TxnUsageEstimator usageEstimator) {
        return new TokenFreezeUsage(tokenFreezeOp, usageEstimator);
    }

    @Override
    TokenFreezeUsage self() {
        return this;
    }

    public FeeData get() {
        addEntityBpt();
        addEntityBpt();
        return usageEstimator.get();
    }
}

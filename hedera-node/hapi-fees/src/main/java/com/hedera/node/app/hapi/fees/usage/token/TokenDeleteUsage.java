// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.TransactionBody;

public class TokenDeleteUsage extends TokenTxnUsage<TokenDeleteUsage> {
    public TokenDeleteUsage(TransactionBody tokenDeletionOp, TxnUsageEstimator usageEstimator) {
        super(tokenDeletionOp, usageEstimator);
    }

    public static TokenDeleteUsage newEstimate(TransactionBody tokenDeletionOp, TxnUsageEstimator usageEstimator) {
        return new TokenDeleteUsage(tokenDeletionOp, usageEstimator);
    }

    @Override
    TokenDeleteUsage self() {
        return this;
    }

    public FeeData get() {
        addEntityBpt();
        return usageEstimator.get();
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;

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

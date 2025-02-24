// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;

public class TokenRevokeKycUsage extends TokenTxnUsage<TokenRevokeKycUsage> {
    private TokenRevokeKycUsage(TransactionBody tokenRevokeKycOp, TxnUsageEstimator usageEstimator) {
        super(tokenRevokeKycOp, usageEstimator);
    }

    public static TokenRevokeKycUsage newEstimate(TransactionBody tokenRevokeKycOp, TxnUsageEstimator usageEstimator) {
        return new TokenRevokeKycUsage(tokenRevokeKycOp, usageEstimator);
    }

    @Override
    TokenRevokeKycUsage self() {
        return this;
    }

    public FeeData get() {
        addEntityBpt();
        addEntityBpt();
        return usageEstimator.get();
    }
}

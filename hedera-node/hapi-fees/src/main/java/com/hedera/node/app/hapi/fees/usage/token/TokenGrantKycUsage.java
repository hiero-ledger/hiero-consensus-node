// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.TransactionBody;

public class TokenGrantKycUsage extends TokenTxnUsage<TokenGrantKycUsage> {
    private TokenGrantKycUsage(TransactionBody tokenGrantKycOp, TxnUsageEstimator usageEstimator) {
        super(tokenGrantKycOp, usageEstimator);
    }

    public static TokenGrantKycUsage newEstimate(TransactionBody tokenGrantKycOp, TxnUsageEstimator usageEstimator) {
        return new TokenGrantKycUsage(tokenGrantKycOp, usageEstimator);
    }

    @Override
    TokenGrantKycUsage self() {
        return this;
    }

    public FeeData get() {
        addEntityBpt();
        addEntityBpt();
        return usageEstimator.get();
    }
}

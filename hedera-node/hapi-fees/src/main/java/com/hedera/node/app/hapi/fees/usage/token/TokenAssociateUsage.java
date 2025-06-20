// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;

import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.TransactionBody;

public class TokenAssociateUsage extends TokenTxnUsage<TokenAssociateUsage> {
    private long currentExpiry;

    public TokenAssociateUsage(TransactionBody tokenOp, TxnUsageEstimator usageEstimator) {
        super(tokenOp, usageEstimator);
    }

    public static TokenAssociateUsage newEstimate(TransactionBody tokenOp, TxnUsageEstimator usageEstimator) {
        return new TokenAssociateUsage(tokenOp, usageEstimator);
    }

    @Override
    TokenAssociateUsage self() {
        return this;
    }

    public TokenAssociateUsage givenCurrentExpiry(long expiry) {
        this.currentExpiry = expiry;
        return this;
    }

    public FeeData get() {
        var op = this.op.getTokenAssociate();
        addEntityBpt();
        op.getTokensList().forEach(t -> addEntityBpt());
        novelRelsLasting(op.getTokensCount(), ESTIMATOR_UTILS.relativeLifetime(this.op, currentExpiry));
        return usageEstimator.get();
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.TransactionBody;

public class TokenDissociateUsage extends TokenTxnUsage<TokenDissociateUsage> {
    private TokenDissociateUsage(TransactionBody tokenOp, TxnUsageEstimator usageEstimator) {
        super(tokenOp, usageEstimator);
    }

    public static TokenDissociateUsage newEstimate(TransactionBody tokenOp, TxnUsageEstimator usageEstimator) {
        return new TokenDissociateUsage(tokenOp, usageEstimator);
    }

    @Override
    TokenDissociateUsage self() {
        return this;
    }

    public FeeData get() {
        var op = this.op.getTokenDissociate();
        addEntityBpt();
        op.getTokensList().forEach(t -> addEntityBpt());
        return usageEstimator.get();
    }
}

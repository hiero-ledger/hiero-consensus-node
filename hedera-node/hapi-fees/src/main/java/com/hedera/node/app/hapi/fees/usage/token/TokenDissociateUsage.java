// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.token.TokenDissociateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;

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
        var op = this.op.tokenDissociateOrElse(TokenDissociateTransactionBody.DEFAULT);
        addEntityBpt();
        op.tokens().forEach(t -> addEntityBpt());
        return usageEstimator.get();
    }
}

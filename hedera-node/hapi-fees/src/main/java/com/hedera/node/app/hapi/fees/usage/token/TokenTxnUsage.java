// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;

import com.hedera.node.app.hapi.fees.usage.TxnUsage;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes;
import com.hederahashgraph.api.proto.java.TransactionBody;

public abstract class TokenTxnUsage<T extends TokenTxnUsage<T>> extends TxnUsage {
    static TokenEntitySizes tokenEntitySizes = TOKEN_ENTITY_SIZES;

    abstract T self();

    protected TokenTxnUsage(final TransactionBody tokenOp, final TxnUsageEstimator usageEstimator) {
        super(tokenOp, usageEstimator);
    }

    void addTokenTransfersRecordRb(final int numTokens, final int fungibleNumTransfers, final int uniqueNumTransfers) {
        addRecordRb(
                tokenEntitySizes.bytesUsedToRecordTokenTransfers(numTokens, fungibleNumTransfers, uniqueNumTransfers));
    }

    public T novelRelsLasting(final int n, final long secs) {
        usageEstimator.addRbs(n * tokenEntitySizes.bytesUsedPerAccountRelationship() * secs);
        return self();
    }
}

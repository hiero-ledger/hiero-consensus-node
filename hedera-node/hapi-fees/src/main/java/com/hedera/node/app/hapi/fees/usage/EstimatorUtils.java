// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_TX_BODY_SIZE;

import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.TransactionBody;

public interface EstimatorUtils {
    /* A century of seconds */
    long MAX_ENTITY_LIFETIME = 100L * 365L * 24L * 60L * 60L;

    default long baseBodyBytes(TransactionBody txn) {
        return (long) BASIC_TX_BODY_SIZE + txn.getMemoBytes().size();
    }

    default long nonDegenerateDiv(long dividend, int divisor) {
        return (dividend == 0) ? 0 : Math.max(1, dividend / divisor);
    }

    default long relativeLifetime(TransactionBody txn, long expiry) {
        long effectiveNow = txn.getTransactionID().getTransactionValidStart().getSeconds();
        return relativeLifetime(effectiveNow, expiry);
    }

    default long relativeLifetime(long now, long expiry) {
        return expiry - now;
    }

    default long changeInBsUsage(long oldB, long oldLifetimeSecs, long newB, long newLifetimeSecs) {
        oldLifetimeSecs = Math.min(MAX_ENTITY_LIFETIME, oldLifetimeSecs);
        newLifetimeSecs = Math.min(MAX_ENTITY_LIFETIME, newLifetimeSecs);

        newLifetimeSecs = Math.max(oldLifetimeSecs, newLifetimeSecs);
        long oldBs = Math.multiplyExact(oldB, oldLifetimeSecs);
        long newBs = Math.multiplyExact(newB, newLifetimeSecs);
        return Math.max(0, newBs - oldBs);
    }

    long baseNetworkRbs();

    FeeData withDefaultTxnPartitioning(FeeComponents usage, SubType subType, long networkRbh, int numPayerKeys);

    FeeData withDefaultQueryPartitioning(FeeComponents usage);

    UsageEstimate baseEstimate(TransactionBody txn, SigUsage sigUsage);
}

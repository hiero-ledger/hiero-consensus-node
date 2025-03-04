// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

import com.hedera.hapi.node.transaction.TransactionBody;

@FunctionalInterface
public interface EstimatorFactory {
    TxnUsageEstimator get(SigUsage sigUsage, TransactionBody txn, EstimatorUtils utils);
}

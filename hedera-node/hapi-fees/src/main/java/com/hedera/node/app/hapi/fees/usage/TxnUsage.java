// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;

import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.function.Function;
import java.util.function.Predicate;

public abstract class TxnUsage {
    protected static final int AMOUNT_REPR_BYTES = 8;
    protected static final UsageProperties usageProperties = USAGE_PROPERTIES;

    protected final TransactionBody op;
    protected final TxnUsageEstimator usageEstimator;

    protected TxnUsage(final TransactionBody op, final TxnUsageEstimator usageEstimator) {
        this.op = op;
        this.usageEstimator = usageEstimator;
    }

    public static <T> long keySizeIfPresent(final T op, final Predicate<T> check, final Function<T, Key> getter) {
        return check.test(op) ? getAccountKeyStorageSize(getter.apply(op)) : 0L;
    }

    protected void addAmountBpt() {
        usageEstimator.addBpt(AMOUNT_REPR_BYTES);
    }

    protected void addEntityBpt() {
        usageEstimator.addBpt(BASIC_ENTITY_ID_SIZE);
    }

    protected void addNetworkRecordRb(final long rb) {
        usageEstimator.addNetworkRbs(rb * usageProperties.legacyReceiptStorageSecs());
    }

    protected void addRecordRb(final long rb) {
        usageEstimator.addRbs(rb * usageProperties.legacyReceiptStorageSecs());
    }
}

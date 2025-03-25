// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event;

import static com.swirlds.platform.system.transaction.TransactionWrapperUtils.createAppPayloadWrapper;

import java.util.random.RandomGenerator;
import org.hiero.consensus.model.transaction.TransactionWrapper;

public class TransactionUtils {

    public static TransactionWrapper[] randomApplicationTransactions(
            final RandomGenerator random,
            final double transactionSizeAverage,
            final double transactionSizeStandardDeviation,
            final double transactionCountAverage,
            final double transactionCountStandardDeviation) {

        final int transactionCount =
                (int) Math.max(0, transactionCountAverage + random.nextGaussian() * transactionCountStandardDeviation);

        final TransactionWrapper[] transactions = new TransactionWrapper[transactionCount];

        for (int index = 0; index < transactionCount; index++) {
            transactions[index] =
                    randomApplicationTransaction(random, transactionSizeAverage, transactionSizeStandardDeviation);
        }

        return transactions;
    }

    public static TransactionWrapper randomApplicationTransaction(
            final RandomGenerator random,
            final double transactionSizeAverage,
            final double transactionSizeStandardDeviation) {
        final int transactionSize =
                (int) Math.max(1, transactionSizeAverage + random.nextGaussian() * transactionSizeStandardDeviation);
        final byte[] transBytes = new byte[transactionSize];
        random.nextBytes(transBytes);
        return createAppPayloadWrapper(transBytes);
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.api;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code CryptoCreate}
 * transaction.
 */
public interface FeeStreamBuilder {
    /**
     * Gets the current value of the transaction fee in this builder.
     * @return The current transaction fee value
     */
    long transactionFee();

    /**
     * Sets the consensus transaction fee.
     *
     * @param transactionFee the transaction fee
     * @return the builder
     */
    @NonNull
    FeeStreamBuilder transactionFee(long transactionFee);
}

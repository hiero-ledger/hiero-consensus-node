// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged by an application when it attempts and fails to create a transaction.
 */
public class CreateTransactionFailedPayload extends AbstractLogPayload {
    private String transactionType;

    public CreateTransactionFailedPayload(final String transactionType) {
        super("Platform refused to create a new transaction");
        this.transactionType = transactionType;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(final String transactionType) {
        this.transactionType = transactionType;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

public interface CalculatorState {

    /**
     * Returns the number of signatures provided for the transaction.
     * This is typically the size of the signature map ({@code txInfo.signatureMap().sigPair().size()}).
     * @return the number of signatures
     */
    int numTxnSignatures();
}

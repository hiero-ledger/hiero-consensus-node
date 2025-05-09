// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

/**
 * A small class to store a running count of Hedera gas consumed by a smart contract.
 */
public class HederaGasCounter {
    private Long gasConsumed;

    public HederaGasCounter(final long initialGas) {
        gasConsumed = initialGas;
    }

    public void incrementGasConsumed(long gas) {
        gasConsumed += gas;
    }

    public Long getGasConsumed() {
        return gasConsumed;
    }
}

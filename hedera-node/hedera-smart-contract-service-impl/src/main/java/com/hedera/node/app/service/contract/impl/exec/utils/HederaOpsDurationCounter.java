// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

/**
 * A small class to store a running count of Hedera ops duration consumed by a smart contract.
 */
public class HederaOpsDurationCounter {
    private Long opsDurationCounter;

    public HederaOpsDurationCounter(final long initialOpsDuration) {
        opsDurationCounter = initialOpsDuration;
    }

    public void incrementOpsDuration(long opsDuration) {
        opsDurationCounter += opsDuration;
    }

    public Long getOpsDurationCounter() {
        return opsDurationCounter;
    }
}

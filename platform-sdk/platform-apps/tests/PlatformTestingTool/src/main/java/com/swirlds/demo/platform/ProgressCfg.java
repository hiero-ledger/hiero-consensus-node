// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

public class ProgressCfg {
    /** whether log progress for transaction handling */
    private int progressMarker = 0;

    private long expectedFCMCreateAmount;
    private long expectedFCMUpdateAmount;
    private long expectedFCMTransferAmount;
    private long expectedFCMDeleteAmount;
    private long expectedFCMAssortedAmount;

    public int getProgressMarker() {
        return progressMarker;
    }

    public void setProgressMarker(int progressMarker) {
        this.progressMarker = progressMarker;
    }

    public long getExpectedFCMCreateAmount() {
        return expectedFCMCreateAmount;
    }

    public void setExpectedFCMCreateAmount(long expectedFCMCreateAmount) {
        this.expectedFCMCreateAmount = expectedFCMCreateAmount;
    }

    public long getExpectedFCMUpdateAmount() {
        return expectedFCMUpdateAmount;
    }

    public void setExpectedFCMUpdateAmount(long expectedFCMUpdateAmount) {
        this.expectedFCMUpdateAmount = expectedFCMUpdateAmount;
    }

    public long getExpectedFCMTransferAmount() {
        return expectedFCMTransferAmount;
    }

    public void setExpectedFCMTransferAmount(long expectedFCMTransferAmount) {
        this.expectedFCMTransferAmount = expectedFCMTransferAmount;
    }

    public long getExpectedFCMDeleteAmount() {
        return expectedFCMDeleteAmount;
    }

    public void setExpectedFCMDeleteAmount(long expectedFCMDeleteAmount) {
        this.expectedFCMDeleteAmount = expectedFCMDeleteAmount;
    }

    public long getTotalExpectedFCMTransactions() {
        return expectedFCMCreateAmount + expectedFCMTransferAmount + expectedFCMUpdateAmount + expectedFCMDeleteAmount;
    }

    public long getTotalExpectedTransactions() {
        return getTotalExpectedFCMTransactions();
    }

    public void setExpectedFCMAssortedAmount(long expectedFCMAssortedAmount) {
        this.expectedFCMAssortedAmount = expectedFCMAssortedAmount;
    }

    public long getExpectedFCMAssortedAmount() {
        return expectedFCMAssortedAmount;
    }
}

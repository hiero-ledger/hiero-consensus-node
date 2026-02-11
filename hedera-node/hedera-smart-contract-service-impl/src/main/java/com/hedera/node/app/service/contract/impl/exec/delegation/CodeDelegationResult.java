// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.delegation;

/**
 * Class that contains the results when delegating EIP-7702 transaction.
 */
public class CodeDelegationResult {
    private long remainingLazyCreationGasAvailable;
    private long totalLazyCreationGasCharged;
    private int numAuthorizationsEligibleForRefund;

    public CodeDelegationResult(final long lazyCreationGasAvailable) {
        this.remainingLazyCreationGasAvailable = lazyCreationGasAvailable;
    }

    public void addHollowAccountCreationGasCharge(final long gas) {
        this.remainingLazyCreationGasAvailable -= gas;
        this.totalLazyCreationGasCharged += gas;
    }

    public long remainingLazyCreationGasAvailable() {
        return this.remainingLazyCreationGasAvailable;
    }

    public void incAuthorizationsEligibleForRefund() {
        this.numAuthorizationsEligibleForRefund += 1;
    }

    public long totalLazyCreationGasCharged() {
        return totalLazyCreationGasCharged;
    }

    public int numAuthorizationsEligibleForRefund() {
        return numAuthorizationsEligibleForRefund;
    }
}

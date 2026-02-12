// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.delegation;

import java.util.HashMap;
import java.util.Map;

/**
 * Class that contains the results when delegating EIP-7702 transaction.
 */
public class CodeDelegationResult {
    public enum EntryIgnoreReason {
        ChainIdMismatch,
        NonceMismatch,
        AccountAlreadyHasCode,
        InsufficientGasForLazyCreation,
        Other
    }

    private long remainingLazyCreationGasAvailable;
    private long totalLazyCreationGasCharged;
    private int numAuthorizationsEligibleForRefund;
    private int successfullyProcessedAuthorizations;
    private final Map<EntryIgnoreReason, Integer> numIgnoredEntriesByReason = new HashMap<>();

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

    public void reportIgnoredEntry(EntryIgnoreReason reason) {
        this.numIgnoredEntriesByReason.compute(reason, (k, v) -> v == null ? 1 : v + 1);
    }

    public void incSuccessfullyProcessedAuthorizations() {
        this.successfullyProcessedAuthorizations += 1;
    }

    public int successfullyProcessedAuthorizations() {
        return this.successfullyProcessedAuthorizations;
    }

    public int ignoredCodeDelegations() {
        return this.numIgnoredEntriesByReason.values().stream().reduce(0, Integer::sum);
    }
}

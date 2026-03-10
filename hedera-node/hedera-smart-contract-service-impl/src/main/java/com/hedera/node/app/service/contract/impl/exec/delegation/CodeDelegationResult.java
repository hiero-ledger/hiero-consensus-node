// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.delegation;

import java.util.Map;

public record CodeDelegationResult(
        long totalLazyCreationGasCharged,
        int numAuthorizationsEligibleForRefund,
        int successfullyProcessedAuthorizations,
        Map<EntryIgnoreReason, Integer> numIgnoredEntriesByReason) {
    public enum EntryIgnoreReason {
        CHAIN_ID_MISMATCH,
        NONCE_MISMATCH,
        ACCOUNT_ALREADY_HAS_CODE,
        INSUFFICIENT_GAS_FOR_LAZY_CREATION,
        OTHER
    }

    public static final CodeDelegationResult EMPTY = new CodeDelegationResult(0, 0, 0, Map.of());

    public int ignoredCodeDelegations() {
        return numIgnoredEntriesByReason.isEmpty()
                ? 0
                : this.numIgnoredEntriesByReason.values().stream().reduce(0, Integer::sum);
    }
}

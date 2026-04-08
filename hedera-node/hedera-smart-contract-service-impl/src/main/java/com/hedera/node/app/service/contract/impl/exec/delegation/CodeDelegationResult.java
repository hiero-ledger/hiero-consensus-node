// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.delegation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import org.hyperledger.besu.datatypes.Address;

public record CodeDelegationResult(
        long totalLazyCreationGasCharged,
        int numAuthorizationsEligibleForRefund,
        int successfullyProcessedAuthorizations,
        @NonNull Map<EntryIgnoreReason, Integer> numIgnoredEntriesByReason,
        @NonNull List<Address> accessedAddresses) {
    public enum EntryIgnoreReason {
        CHAIN_ID_MISMATCH,
        NONCE_MISMATCH,
        ACCOUNT_ALREADY_HAS_CODE,
        INSUFFICIENT_GAS_FOR_LAZY_CREATION,
        OTHER
    }

    public static final CodeDelegationResult EMPTY = new CodeDelegationResult(0, 0, 0, Map.of(), List.of());

    public int ignoredCodeDelegations() {
        return numIgnoredEntriesByReason.isEmpty()
                ? 0
                : this.numIgnoredEntriesByReason.values().stream().reduce(0, Integer::sum);
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.delegation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hyperledger.besu.datatypes.Address;

public record CodeDelegationResult(
        long totalLazyCreationGasCharged,
        int numAuthorizationsEligibleForRefund,
        int successfullyProcessedAuthorizations,
        Map<EntryIgnoreReason, Integer> numIgnoredEntriesByReason,
        List<Address> authorities) {
    public enum EntryIgnoreReason {
        ChainIdMismatch,
        NonceMismatch,
        AccountAlreadyHasCode,
        InsufficientGasForLazyCreation,
        Other
    }

    public static CodeDelegationResult empty() {
        return new CodeDelegationResult(0, 0, 0, new HashMap<>(), List.of());
    }

    public int ignoredCodeDelegations() {
        return this.numIgnoredEntriesByReason.values().stream().reduce(0, Integer::sum);
    }
}

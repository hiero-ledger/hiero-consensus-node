// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.contract.SlotKey;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Set;

/**
 * The summary of storage usage for an EVM transaction.
 * @param accesses the list of storage accesses made by the transaction
 * @param changedKeys if requested, the set of slot keys whose logical values changed (not just prev/next pointers)
 */
public record TxStorageUsage(List<StorageAccesses> accesses, @Nullable Set<SlotKey> changedKeys) {
    /**
     * Checks if the transaction has changed keys.
     * @return true if the transaction has changed keys, false otherwise
     */
    public boolean hasChangedKeys() {
        return changedKeys != null;
    }

    /**
     * Returns the set of changed keys, or throws an exception if there are no changed keys.
     * @throws NullPointerException if changedKeys is null
     */
    public Set<SlotKey> changedKeysOrThrow() {
        return requireNonNull(changedKeys);
    }
}

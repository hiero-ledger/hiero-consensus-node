// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.hapi.node.base.ContractID;
import java.util.List;

public record StorageAccesses(ContractID contractID, List<StorageAccess> accesses) {
    public StorageSizeChange summarizeSizeEffects() {
        var numRemovals = 0;
        var numInsertions = 0;
        for (final var change : accesses()) {
            if (change.isRemoval()) {
                numRemovals++;
            } else if (change.isInsertion()) {
                numInsertions++;
            }
        }
        return new StorageSizeChange(contractID, numRemovals, numInsertions);
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.hapi.node.base.ContractID;

public record StorageSizeChange(ContractID contractID, int numRemovals, int numInsertions) {
    public int numAdded() {
        return Math.max(0, numInsertions - numRemovals);
    }

    public int netChange() {
        return numInsertions - numRemovals;
    }
}

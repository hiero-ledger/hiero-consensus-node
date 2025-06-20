// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.state.StorageSizeChange;
import org.junit.jupiter.api.Test;

class StorageSizeChangeTest {
    @Test
    void neverAddsNegativeNumberOfSlots() {
        final var changes =
                new StorageSizeChange(ContractID.newBuilder().contractNum(1L).build(), 5, 2);
        assertEquals(0, changes.numAdded());
        assertEquals(-3, changes.netChange());
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.contract.entities;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BOOL_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ContractEntitySizesTest {
    private final ContractEntitySizes subject = ContractEntitySizes.CONTRACT_ENTITY_SIZES;

    @Test
    void knowsExpectedFixedBytes() {
        // expect:
        assertEquals(1 * BOOL_SIZE + 4 * LONG_SIZE + 2 * BASIC_ENTITY_ID_SIZE + 40, subject.fixedBytesInContractRepr());
    }
}

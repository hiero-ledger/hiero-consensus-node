// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.hedera.node.app.hapi.fees.usage.token.meta.ExtantFeeScheduleContext;
import com.hedera.node.app.hapi.fees.usage.token.meta.FeeScheduleUpdateMeta;
import org.junit.jupiter.api.Test;

class MetadataObjectContractsTest {
    @Test
    void baseTxnMetaObjContractSanityChecks() {
        // given:
        final var a = new BaseTransactionMeta(12, 3);
        final var b = new BaseTransactionMeta(23, 4);
        final var c = new BaseTransactionMeta(12, 3);

        // expect:
        assertEquals(a, c);
        assertNotEquals(b, c);
        assertNotEquals(b.hashCode(), c.hashCode());
        assertNotEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void extantFeeScheduleCtxObjContractSanityChecks() {
        // given:
        final var a = new ExtantFeeScheduleContext(12, 3);
        final var b = new ExtantFeeScheduleContext(23, 4);
        final var c = new ExtantFeeScheduleContext(12, 3);

        // expect:
        assertEquals(a, c);
        assertNotEquals(b, c);
        assertNotEquals(b.hashCode(), c.hashCode());
        assertNotEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void feeScheduleMetaObjContractSanityChecks() {
        // given:
        final var a = new FeeScheduleUpdateMeta(12, 3);
        final var b = new FeeScheduleUpdateMeta(23, 4);
        final var c = new FeeScheduleUpdateMeta(12, 3);

        // expect:
        assertEquals(a, c);
        assertNotEquals(b, c);
        assertNotEquals(b.hashCode(), c.hashCode());
        assertNotEquals(a.hashCode(), b.hashCode());
    }
}

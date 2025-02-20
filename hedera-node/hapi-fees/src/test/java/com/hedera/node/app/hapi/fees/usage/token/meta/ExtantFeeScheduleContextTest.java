// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExtantFeeScheduleContextTest {
    @Test
    void assertEqualsWork() {
        // when:
        final var subject = new ExtantFeeScheduleContext(1234L, 22);
        final var subject2 = new ExtantFeeScheduleContext(1234L, 22);

        // then:
        assertEquals(subject, subject2);
        assertEquals(subject.hashCode(), subject2.hashCode());
    }

    @Test
    void assertGetters() {
        // when:
        final var subject = new ExtantFeeScheduleContext(1234L, 22);

        // then:
        assertEquals(1234L, subject.expiry());
        assertEquals(22, subject.numBytesInFeeScheduleRepr());
    }
}

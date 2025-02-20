// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FeeScheduleUpdateMetaTest {
    @Test
    void assertEqualsWork() {
        // when:
        final var subject = new FeeScheduleUpdateMeta(1_234_567L, 22);
        final var subject2 = new FeeScheduleUpdateMeta(1_234_567L, 22);

        // then:
        assertEquals(subject, subject2);
        assertEquals(subject.hashCode(), subject2.hashCode());
    }

    @Test
    void assertGetters() {
        // when:
        final var subject = new FeeScheduleUpdateMeta(1_234_567L, 22);

        // then:
        assertEquals(1_234_567L, subject.effConsensusTime());
        assertEquals(22, subject.numBytesInNewFeeScheduleRepr());
    }

    @Test
    void toStringWorks() {
        // given:
        final var desired = "FeeScheduleUpdateMeta[effConsensusTime=1234567, numBytesInNewFeeScheduleRepr=22]";

        // when:
        final var subject = new FeeScheduleUpdateMeta(1_234_567L, 22);

        // then:
        assertEquals(desired, subject.toString());
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SigUsageTest {
    @Test
    void gettersWork() {
        final SigUsage one = new SigUsage(3, 256, 2);
        assertEquals(3, one.numSigs());
        assertEquals(256, one.sigsSize());
        assertEquals(2, one.numPayerKeys());
    }

    @Test
    void sigUsageObjectContractWorks() {
        final SigUsage one = new SigUsage(3, 256, 2);
        final SigUsage two = new SigUsage(3, 256, 2);
        final SigUsage three = new SigUsage(4, 256, 2);

        assertNotEquals(null, one);
        assertNotEquals(new Object(), one);
        assertNotEquals(one, three);
        assertEquals(one, two);

        assertEquals(one.hashCode(), two.hashCode());
        assertNotEquals(one.hashCode(), three.hashCode());

        assertEquals(one.toString(), two.toString());
        assertNotEquals(one.toString(), three.toString());
    }
}

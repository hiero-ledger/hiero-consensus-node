// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AtomicMaxTest {
    @Test
    void basic() {
        final long def = 0;
        AtomicMax a = new AtomicMax(def);
        assertEquals(def, a.get(), "The max value should be equal to the initialized value.");

        a.update(1);
        a.update(3);
        a.update(1);

        assertEquals(3, a.get(), "The max should be 3");
        assertEquals(3, a.getAndReset(), "The max should still be 3");
        assertEquals(def, a.get(), "The max after a reset should be set to default");
    }
}

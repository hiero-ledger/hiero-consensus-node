// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AtomicAverageTest {
    @Test
    void basic() {
        final double def = 0;
        final double weight = 0.5;
        AtomicAverage a = new AtomicAverage(weight, def);
        assertEquals(def, a.get(), "The average value should be equal to the initialized value.");

        a.update(1);
        a.update(2);

        assertEquals(1.5, a.get(), "The evenly weighted average of 1 and 2 should be 1.5.");
    }

    @Test
    void weight() {
        final double weight = 0.2;
        AtomicAverage a = new AtomicAverage(weight);

        a.update(1);
        a.update(2);

        assertEquals(
                1.2,
                round(a.get(), 5),
                "The average of 1 and 2 with a" + " weight of 0.2 for new values should be 1.2");
    }

    public static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }
}

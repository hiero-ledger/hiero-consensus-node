// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats;

import com.swirlds.platform.stats.atomic.AtomicSumAndCount;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AtomicSumAndCountTest {
    private static final double DELTA = 0.001;

    /**
     * Tests basic functionality, adding to a sum and calculating an average
     */
    @Test
    void testIntSum() {
        final AtomicSumAndCount sumAndCount = new AtomicSumAndCount();

        Assertions.assertEquals(0, sumAndCount.getCount(), "initial value should be 0");
        Assertions.assertEquals(0, sumAndCount.getSum(), "initial value should be 0");

        sumAndCount.add(10);

        Assertions.assertEquals(1, sumAndCount.getCount(), "added once, should be 1");
        Assertions.assertEquals(10, sumAndCount.getSum(), "added only 10, should be 10");

        sumAndCount.add(5);

        Assertions.assertEquals(2, sumAndCount.getCount(), "added twice, should be 2");
        Assertions.assertEquals(15, sumAndCount.getSum(), "added only 10+5, should be 15");

        Assertions.assertEquals(7.5, sumAndCount.averageAndReset(), DELTA, "average should be 15/2 == 7.5");

        Assertions.assertEquals(0, sumAndCount.getCount(), "after reset should be 0");
        Assertions.assertEquals(0, sumAndCount.getSum(), "after reset should be 0");
    }
}

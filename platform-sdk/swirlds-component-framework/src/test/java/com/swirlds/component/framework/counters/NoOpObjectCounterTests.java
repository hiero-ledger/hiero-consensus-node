// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.counters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NoOpObjectCounterTests {

    /**
     * The most important part of the no-op implementation is that it doesn't throw exceptions.
     */
    @Test
    void noThrowingTest() {
        final NoOpObjectCounter counter = NoOpObjectCounter.getInstance();

        counter.onRamp();
        counter.attemptOnRamp();
        counter.forceOnRamp();
        counter.offRamp();
        counter.waitUntilEmpty();
        assertEquals(-1, counter.getCount());
    }
}

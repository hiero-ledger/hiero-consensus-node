// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility.throttle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MultiThrottle Tests")
class MultiThrottleTest {

    private static final int ONE_SECOND_SLEEP = 1_000;

    @Test
    @DisplayName("Validate Throttling")
    void testValidateThrottling() throws InterruptedException {
        final Throttle throttleA = new Throttle(1, 1);
        final Throttle throttleB = new Throttle(1, 1);
        final Throttle throttleC = new Throttle(2, 1);
        final Throttle throttleD = new Throttle(2, 1);

        final MultiThrottle multiAB = new MultiThrottle(Arrays.asList(throttleA, throttleB));
        final MultiThrottle multiAC = new MultiThrottle(Arrays.asList(throttleA, throttleC));
        final MultiThrottle multiCD = new MultiThrottle(Arrays.asList(throttleC, throttleD));

        final MultiThrottle multiCD2 = new MultiThrottle();
        multiCD2.addThrottle(throttleC);
        multiCD2.addThrottle(throttleD);

        assertTrue(multiAB.allow(), "MultiThrottle::allow() - Improperly Throttled");
        Thread.sleep(ONE_SECOND_SLEEP);
        assertTrue(multiAB.allow(), "MultiThrottle::allow() - Improperly Throttled");
        assertFalse(multiAB.allow(), "MultiThrottle::allow() - Improperly Allowed Capacity to be Exceeded");
        assertFalse(multiAC.allow(), "MultiThrottle::allow() - Improperly Allowed Lower Throttle to be Exceeded");

        assertTrue(multiCD.allow(), "MultiThrottle::allow() - Improperly Throttled");
        Thread.sleep(ONE_SECOND_SLEEP);
        assertTrue(multiCD.allow(), "MultiThrottle::allow() - Improperly Throttled at half capacity");
        assertTrue(multiCD.allow(), "MultiThrottle::allow() - Improperly Throttled");
        assertFalse(multiCD.allow(), "MultiThrottle::allow() - Improperly Allowed Capacity to be Exceeded");
        Thread.sleep(ONE_SECOND_SLEEP);
        assertTrue(multiCD.allow(2), "MultiThrottle::allow() - Improperly Throttled");
        assertFalse(multiCD.allow(), "MultiThrottle::allow() - Improperly Allowed Capacity to be Exceeded");
        assertFalse(multiCD2.allow(), "MultiThrottle::allow() - Improperly Allowed Capacity to be Exceeded");
        Thread.sleep(ONE_SECOND_SLEEP);
        assertTrue(multiCD2.allow(2), "MultiThrottle::allow() - Improperly Throttled");
        assertFalse(multiCD.allow(), "MultiThrottle::allow() - Improperly Allowed Capacity to be Exceeded");
        assertFalse(multiCD2.allow(), "MultiThrottle::allow() - Improperly Allowed Capacity to be Exceeded");
        Thread.sleep(ONE_SECOND_SLEEP);
        assertTrue(multiAC.allow(), "MultiThrottle::allow() - Improperly Throttled");
        assertFalse(multiCD.allow(2), "MultiThrottle::allow() - Improperly Allowed Capacity to be Exceeded");
    }

    @Test
    @DisplayName("Validate Creation")
    void testValidateCreation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MultiThrottle(null),
                "MultiThrottle::MultiThrottle(List<Throttle>) should have thrown with Null argument");

        MultiThrottle multiThrottle = new MultiThrottle();
        assertThrows(
                IllegalArgumentException.class,
                () -> multiThrottle.addThrottle(null),
                "MultiThrottle::addThrottle() should have thrown with null argument");
    }
}

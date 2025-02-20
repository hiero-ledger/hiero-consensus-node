// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility.throttle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Basic Throttle Tests")
public class BasicThrottleTest {

    private static final int ONE_SECOND_SLEEP = 1_000;

    @Test
    @DisplayName("Verify Accessor Behavior")
    public void testVerifyAccessorBehavior() {
        final Throttle throttle = new Throttle(10);

        assertEquals(1, throttle.getBurstPeriod(), "Throttle::getBurstPeriod() - Invalid Default Value");
        assertEquals(10, throttle.getTps(), "Throttle::getTps() - Invalid Value from Constructor");
        assertEquals(10, throttle.getCapacity(), "Throttle::getCapacity() - Invalid Computed Value");

        throttle.setTps(-100);
        assertEquals(1, throttle.getBurstPeriod(), "Throttle::getBurstPeriod() - Invalid Default Value");
        assertEquals(0, throttle.getTps(), "Throttle::getTps() - Failed to Handle Negative Value");
        assertEquals(0, throttle.getCapacity(), "Throttle::getCapacity() - Failed to Handle Negative Value");

        throttle.setTps(100);
        assertEquals(1, throttle.getBurstPeriod(), "Throttle::getBurstPeriod() - Invalid Default Value");
        assertEquals(100, throttle.getTps(), "Throttle::getTps() - Invalid Value from Setter");
        assertEquals(100, throttle.getCapacity(), "Throttle::getCapacity() - Invalid Computed Value");

        throttle.setBurstPeriod(-10);
        assertEquals(0, throttle.getBurstPeriod(), "Throttle::getBurstPeriod() - Failed to Handle Negative Value");
        assertEquals(100, throttle.getTps(), "Throttle::getTps() - Invalid Value from Setter");
        assertEquals(0, throttle.getCapacity(), "Throttle::getCapacity() - Failed to Handle Negative Value");

        throttle.setBurstPeriod(10);
        assertEquals(10, throttle.getBurstPeriod(), "Throttle::getBurstPeriod() - Invalid Value from Setter");
        assertEquals(100, throttle.getTps(), "Throttle::getTps() - Invalid Value from Setter");
        assertEquals(1_000, throttle.getCapacity(), "Throttle::getCapacity() - Invalid Computed Value");
    }

    @Test
    @DisplayName("Validate Throttling")
    public void testValidateThrottling() throws InterruptedException {
        final Throttle throttle = new Throttle(100, 1);

        assertTrue(throttle.allow(), "Throttle::allow() - Improperly Throttled");
        Thread.sleep(ONE_SECOND_SLEEP);
        assertTrue(throttle.allow(50), "Throttle::allow() - Improperly Throttled at Half Capacity Burst");
        assertTrue(throttle.allow(50), "Throttle::allow() - Improperly Throttled at Remaining Capacity Burst");
        assertFalse(throttle.allow(), "Throttle::allow() - Improperly Allowed Capacity to be Exceeded");

        throttle.setBurstPeriod(10);
        Thread.sleep(ONE_SECOND_SLEEP);
        assertTrue(throttle.allow(0), "Throttle::allow() - Improperly Handled Zero Flow");
        assertTrue(throttle.allow(100), "Throttle::allow() - Improperly Throttled at 1/10th Capacity Burst");
        assertTrue(throttle.allow(100), "Throttle::allow() - Improperly Throttled at 2/10th Capacity Burst");
        assertTrue(throttle.allow(800), "Throttle::allow() - Improperly Throttled at Remaining Capacity Burst");
        assertFalse(throttle.allow(5), "Throttle::allow() - Improperly Allowed Capacity to be Exceeded on Burst Flow");
        assertFalse(throttle.allow(), "Throttle::allow() - Improperly Allowed Capacity to be Exceeded on Single Flow");

        assertFalse(throttle.allow(-10), "Throttle::allow() - Improperly Handled Negative Flow");
    }
}

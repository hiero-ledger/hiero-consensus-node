// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.hiero.base.ValueReference;
import org.hiero.base.exceptions.ReferenceCountException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReferenceCounter Tests")
class ReferenceCounterTests {

    @Test
    @DisplayName("Implicit Reservation Test")
    void implicitReservationTest() {

        ValueReference<Boolean> destroyed = new ValueReference<>(false);
        final Runnable onDestroy = () -> {
            assertFalse(destroyed.getValue(), "should not be destroyed more than once");
            destroyed.setValue(true);
        };

        final ReferenceCounter referenceCounter = new ReferenceCounter(onDestroy);

        assertFalse(referenceCounter.isDestroyed());
        assertFalse(destroyed.getValue(), "should not be destroyed yet");
        assertEquals(0, referenceCounter.getReservationCount(), "unexpected reservation count");
        referenceCounter.throwIfDestroyed("this is not expected to throw");

        referenceCounter.release();

        assertEquals(-1, referenceCounter.getReservationCount(), "unexpected reservation count");
        assertTrue(destroyed.getValue(), "onDestroy callback not invoked");
        assertTrue(referenceCounter.isDestroyed(), "should report being destroyed");

        assertThrows(
                ReferenceCountException.class,
                referenceCounter::reserve,
                "should not be able to reserve after destroying");
        assertThrows(
                ReferenceCountException.class,
                referenceCounter::release,
                "should not be able to release after destroying");
        assertThrows(RuntimeException.class, referenceCounter::throwIfDestroyed, "should throw after destruction");

        assertEquals(-1, referenceCounter.getReservationCount(), "unexpected reservation count");
        assertFalse(referenceCounter.tryReserve(), "should not be able to reserve after destruction");
    }

    /**
     * As in "intentional", not "rated R for mature audiences"
     */
    @Test
    @DisplayName("Explicit Reservation Test")
    void explicitReservationTest() {

        final Random random = getRandomPrintSeed();

        ValueReference<Boolean> destroyed = new ValueReference<>(false);
        final Runnable onDestroy = () -> {
            assertFalse(destroyed.getValue(), "should not be destroyed more than once");
            destroyed.setValue(true);
        };

        final ReferenceCounter referenceCounter = new ReferenceCounter(onDestroy);

        assertFalse(referenceCounter.isDestroyed());
        assertFalse(destroyed.getValue(), "should not be destroyed yet");
        assertEquals(0, referenceCounter.getReservationCount(), "unexpected reservation count");
        referenceCounter.throwIfDestroyed("this is not expected to throw");

        // Take a bunch of reservations
        int currentReservationCount = 0;
        for (int i = 0; i < 100; i++) {
            currentReservationCount++;
            if (random.nextBoolean()) {
                referenceCounter.reserve();
            } else {
                assertTrue(referenceCounter.tryReserve(), "should be able to get a reservation");
            }
            assertEquals(currentReservationCount, referenceCounter.getReservationCount(), "unexpected reference count");
            assertFalse(referenceCounter.isDestroyed());
            assertFalse(destroyed.getValue(), "should not be destroyed yet");
            assertEquals(
                    currentReservationCount, referenceCounter.getReservationCount(), "unexpected reservation count");
            referenceCounter.throwIfDestroyed("this is not expected to throw");
        }

        // Take and release reservations. But release more often than taking, so eventually the reservations
        // should run out.
        while (currentReservationCount > 0) {
            assertEquals(currentReservationCount, referenceCounter.getReservationCount(), "unexpected reference count");
            assertFalse(referenceCounter.isDestroyed());
            assertFalse(destroyed.getValue(), "should not be destroyed yet");
            assertEquals(
                    currentReservationCount, referenceCounter.getReservationCount(), "unexpected reservation count");
            referenceCounter.throwIfDestroyed("this is not expected to throw");

            if (random.nextDouble() < 2.0 / 3.0) {
                currentReservationCount--;
                referenceCounter.release();
            } else {
                currentReservationCount++;
                if (random.nextBoolean()) {
                    referenceCounter.reserve();
                } else {
                    assertTrue(referenceCounter.tryReserve(), "should be able to get a reservation");
                }
            }
        }

        // The reservations should be depleted now
        assertEquals(-1, referenceCounter.getReservationCount(), "unexpected reservation count");
        assertTrue(destroyed.getValue(), "onDestroy callback not invoked");
        assertTrue(referenceCounter.isDestroyed(), "should report being destroyed");

        assertThrows(
                ReferenceCountException.class,
                referenceCounter::reserve,
                "should not be able to reserve after destroying");
        assertThrows(
                ReferenceCountException.class,
                referenceCounter::release,
                "should not be able to release after destroying");
        assertThrows(RuntimeException.class, referenceCounter::throwIfDestroyed, "should throw after destruction");

        assertEquals(-1, referenceCounter.getReservationCount(), "unexpected reservation count");
        assertFalse(referenceCounter.tryReserve(), "should not be able to reserve after destruction");
    }
}

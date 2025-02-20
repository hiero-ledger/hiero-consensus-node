// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.components;

import static com.swirlds.platform.event.EventUtils.calculateNewEventCreationTime;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EventUtils Tests")
class EventUtilsTests {
    @Test
    @DisplayName("calculateNewEventCreationTime Test()")
    void calculateNewEventCreationTimeTest() {
        final Instant parentTime = Instant.now();

        // now is after minimum time, no transactions
        final Instant now1 = parentTime.plusNanos(10);
        final Instant calculatedTime1 = calculateNewEventCreationTime(now1, parentTime, 0);
        assertEquals(now1, calculatedTime1);

        // now is after minimum time with transactions
        final Instant now2 = parentTime.plusNanos(10);
        final Instant calculatedTime2 = calculateNewEventCreationTime(now2, parentTime, 5);
        assertEquals(now2, calculatedTime2);

        // now is before minimum time, no transactions
        final Instant now3 = parentTime.minusNanos(10);
        final Instant calculatedTime3 = calculateNewEventCreationTime(now3, parentTime, 0);
        assertEquals(parentTime.plusNanos(1), calculatedTime3);

        // now is before minimum time because of transactions
        final Instant now4 = parentTime.plusNanos(10);
        final Instant calculatedTime4 = calculateNewEventCreationTime(now4, parentTime, 20);
        assertEquals(parentTime.plusNanos(20), calculatedTime4);

        // exact time no transactions
        final Instant now5 = parentTime;
        final Instant calculatedTime5 = calculateNewEventCreationTime(now5, parentTime, 0);
        assertEquals(parentTime.plusNanos(1), calculatedTime5);
    }
}

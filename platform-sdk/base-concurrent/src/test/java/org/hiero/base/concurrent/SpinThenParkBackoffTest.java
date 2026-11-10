// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent;

import static org.hiero.base.concurrent.SpinThenParkBackoff.MAX_PARK_SHIFT;
import static org.hiero.base.concurrent.SpinThenParkBackoff.SPIN_LIMIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpinThenParkBackoffTest {

    @Test
    void spinsWithoutParkingBeforeSpinLimit() {
        final List<Long> parks = new ArrayList<>();
        int backoffStep = 0;
        for (int i = 0; i < SPIN_LIMIT; i++) {
            backoffStep = SpinThenParkBackoff.backOff(backoffStep, 7, parks::add);
            assertEquals(i + 1, backoffStep, "the step should grow by one per call");
        }
        assertTrue(parks.isEmpty(), "the first SPIN_LIMIT calls should spin, not park");
    }

    @Test
    void parksWithDoublingLengthAfterSpinLimitUpToCap() {
        final int maxParkShift = 7;
        final List<Long> parks = new ArrayList<>();
        int backoffStep = SPIN_LIMIT;
        for (int i = 0; i <= maxParkShift; i++) {
            backoffStep = SpinThenParkBackoff.backOff(backoffStep, maxParkShift, parks::add);
        }
        final List<Long> expected = new ArrayList<>();
        for (int shift = 0; shift <= maxParkShift; shift++) {
            expected.add(1_000L << shift);
        }
        assertEquals(expected, parks, "parks should be 1µs, 2µs, 4µs, ... up to 1µs << maxParkShift");
    }

    @Test
    void parkLengthAndStepStopGrowingAtCap() {
        final int maxParkShift = 3;
        final List<Long> parks = new ArrayList<>();
        int backoffStep = 0;
        for (int i = 0; i < SPIN_LIMIT + maxParkShift + 100; i++) {
            backoffStep = SpinThenParkBackoff.backOff(backoffStep, maxParkShift, parks::add);
        }
        assertEquals(SPIN_LIMIT + maxParkShift, backoffStep, "the step should stop growing at the last step");
        assertEquals(maxParkShift + 100, parks.size(), "every call after the spin phase should park");
        final List<Long> lastParks = parks.subList(maxParkShift, parks.size());
        assertTrue(
                lastParks.stream().allMatch(nanos -> nanos == (1_000L << maxParkShift)),
                "parks should stay at the cap during a long wait");
    }

    @Test
    void zeroShiftParksOneMicrosecond() {
        final List<Long> parks = new ArrayList<>();
        final int backoffStep = SpinThenParkBackoff.backOff(SPIN_LIMIT, 0, parks::add);
        assertEquals(List.of(1_000L), parks);
        assertEquals(SPIN_LIMIT, backoffStep, "the step is already the last one");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, MAX_PARK_SHIFT + 1})
    void rejectsOutOfRangeShift(final int maxParkShift) {
        assertThrows(IllegalArgumentException.class, () -> SpinThenParkBackoff.backOff(0, maxParkShift));
    }

    @Test
    void returnsImmediatelyAndKeepsFlagWhenInterrupted() {
        // At the cap with MAX_PARK_SHIFT, the park would last about 18 minutes if the interrupt were ignored.
        // The timeout runs the call in a separate thread, so the interrupt is set there
        final boolean stillInterrupted = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            Thread.currentThread().interrupt();
            SpinThenParkBackoff.backOff(SPIN_LIMIT + MAX_PARK_SHIFT, MAX_PARK_SHIFT);
            return Thread.currentThread().isInterrupted();
        });
        assertTrue(stillInterrupted, "the interrupted flag should be left set");
    }
}

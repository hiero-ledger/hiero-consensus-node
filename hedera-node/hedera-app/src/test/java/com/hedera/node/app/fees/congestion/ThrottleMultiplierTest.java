// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees.congestion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.node.app.hapi.utils.throttles.CongestibleThrottle;
import com.hedera.node.config.types.CongestionMultipliers;
import java.time.Instant;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class})
class ThrottleMultiplierTest {
    private static final long DEFAULT_MULTIPLIER = 1L;
    private static final long THROTTLE_CAPACITY = 100_000L;

    @Mock
    private CongestionMultipliers congestionMultipliers;

    @Mock
    private CongestibleThrottle throttle;

    private ThrottleMultiplier subject;

    @BeforeEach
    void setUp() {
        congestionMultipliers = mock(CongestionMultipliers.class);
        throttle = mock(CongestibleThrottle.class);

        Supplier<CongestionMultipliers> multiplierSupplier = () -> congestionMultipliers;
        Supplier<List<? extends CongestibleThrottle>> throttleSource = () -> List.of(throttle);

        subject = new ThrottleMultiplier(
                "UsageType",
                "AbbrevUsageType",
                "CongestionType",
                mock(LongSupplier.class),
                multiplierSupplier,
                throttleSource);

        assertEquals(DEFAULT_MULTIPLIER, subject.currentMultiplier());
    }

    @Test
    void testUpdateMultiplier() {
        final var firstMultiplier = 2L;
        final var secondMultiplier = 3L;
        final var thirdMultiplier = 4L;
        when(congestionMultipliers.multipliers())
                .thenReturn(new long[] {firstMultiplier, secondMultiplier, thirdMultiplier});

        final var firstUsagePercentTrigger = 5;
        final var secondUsagePercentTrigger = 10;
        final var thirdUsagePercentTrigger = 90;
        when(congestionMultipliers.usagePercentTriggers())
                .thenReturn(new int[] {firstUsagePercentTrigger, secondUsagePercentTrigger, thirdUsagePercentTrigger});
        when(throttle.capacity()).thenReturn(THROTTLE_CAPACITY);
        subject.resetExpectations();

        var usagePercentage = 7;
        when(throttle.used()).thenReturn(usagePercentage * (THROTTLE_CAPACITY / 100));
        subject.updateMultiplier(Instant.now());
        assertEquals(firstMultiplier, subject.currentMultiplier());

        usagePercentage = 50;
        when(throttle.used()).thenReturn(usagePercentage * (THROTTLE_CAPACITY / 100));
        subject.updateMultiplier(Instant.now());
        assertEquals(secondMultiplier, subject.currentMultiplier());

        usagePercentage = 95;
        when(throttle.used()).thenReturn(usagePercentage * (THROTTLE_CAPACITY / 100));
        subject.updateMultiplier(Instant.now());
        assertEquals(thirdMultiplier, subject.currentMultiplier());
    }

    @Test
    void testResetCongestionLevelStarts() {
        Instant[] startTimes = {Instant.now(), Instant.now().plusSeconds(10)};

        subject.resetCongestionLevelStarts(startTimes);

        assertEquals(startTimes[0], subject.congestionLevelStarts()[0]);
        assertEquals(startTimes[1], subject.congestionLevelStarts()[1]);
    }

    @Test
    void testResetCongestionLevelStartsIgnoresTierCountMismatch() {
        when(congestionMultipliers.multipliers()).thenReturn(new long[] {2L, 3L, 4L});
        when(congestionMultipliers.usagePercentTriggers()).thenReturn(new int[] {5, 10, 90});
        when(throttle.capacity()).thenReturn(THROTTLE_CAPACITY);
        subject.resetExpectations();

        // A restored array whose length no longer matches the active tier count (the number of
        // congestion multiplier tiers changed since it was saved) must be ignored, not adopted,
        // so the handle path does not index out of bounds on the next update.
        final Instant[] staleStarts = {Instant.now(), Instant.now()};
        subject.resetCongestionLevelStarts(staleStarts);

        assertEquals(3, subject.congestionLevelStarts().length);
        assertDoesNotThrow(() -> subject.updateMultiplier(Instant.now()));
    }
}

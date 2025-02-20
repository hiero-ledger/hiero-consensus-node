// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.metrics.IntegerPairAccumulator;
import com.swirlds.common.metrics.platform.PlatformIntegerPairAccumulator;
import com.swirlds.common.time.IntegerEpochTime;
import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class CountPerSecondTest {
    private final FakeTime clock = new FakeTime(Instant.EPOCH, Duration.ZERO);
    private final IntegerEpochTime epochClock = new IntegerEpochTime(clock);
    private CountPerSecond metric;

    @BeforeEach
    void reset() {
        final Metrics metrics = mock(Metrics.class);
        when(metrics.getOrCreate(any())).thenAnswer((Answer<IntegerPairAccumulator<Double>>) invocation -> {
            final IntegerPairAccumulator.Config<Double> config = invocation.getArgument(0);
            return new PlatformIntegerPairAccumulator<>(config);
        });
        metric = new CountPerSecond(
                metrics,
                new CountPerSecond.Config("a", "b").withDescription("c").withUnit("d"),
                epochClock);
        clock.reset();
    }

    @Test
    void basic() {
        metric.count();
        clock.tick(Duration.ofMillis(500));
        metric.count();

        assertEquals(4.0, metric.get(), "2 counts in half a second should be 4/second");
    }

    @Test
    void intOverflow() {
        // set the clock so that the milli epoch is just before the int overflow
        clock.set(Duration.ofMillis(Integer.MAX_VALUE - 1));
        metric.reset();
        assertEquals(Integer.MAX_VALUE - 1, epochClock.getMilliTime(), "the metric clock should be just below max int");
        metric.count(1);
        clock.tick(Duration.ofMillis(1));
        assertEquals(1000.0, metric.get(), "1 count in 1 millisecond should be 1000/second");
        assertEquals(0, epochClock.getMilliTime(), "the clock should have overflown and should now be 0");
    }

    @Test
    void noTimePassed() {
        assertEquals(0.0, metric.get(), "if the count is 0, then the return value should always be 0");
        metric.count();
        assertTrue(
                0.0 < metric.get(),
                "if now time has passed, then the per second count would be infinite. "
                        + "this should never happen in the real world, "
                        + "but we want to make sure that the value returned is at least positive");
    }
}

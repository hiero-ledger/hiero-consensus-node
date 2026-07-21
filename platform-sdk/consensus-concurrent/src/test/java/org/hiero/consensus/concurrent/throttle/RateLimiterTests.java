// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.throttle;

import static org.hiero.base.CompareTo.isLessThan;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RateLimiter Tests")
class RateLimiterTests {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 100})
    @DisplayName("Period Test")
    void periodTest(final int periodMs) {
        final Random random = getRandomPrintSeed();

        final FakeTime time = new FakeTime(Duration.ofNanos(1));
        final RateLimiter rateLimiter = new RateLimiter(time, Duration.ofMillis(periodMs));

        long count = 0;
        long denied = 0;

        final Duration limit = Duration.ofSeconds(1);
        while (isLessThan(time.elapsed(), limit)) {
            assertEquals(denied, rateLimiter.getDeniedRequests(), "invalid number of denied requests");

            // Check and see if the rate limiter will allow the action to be triggered.
            final boolean requestAccepted = rateLimiter.request();
            if (!requestAccepted) {
                denied++;
            } else {
                denied = 0;
            }

            assertEquals(denied, rateLimiter.getDeniedRequests(), "invalid number of denied requests");

            if (random.nextBoolean()) {
                if (rateLimiter.request()) {
                    rateLimiter.trigger();
                    count++;
                } else {
                    denied++;
                }
            } else if (rateLimiter.requestAndTrigger()) {
                count++;
                denied = 0;
            } else {
                denied++;
            }

            // If we successfully triggered above, we should now be denied until time advances. If we did not
            // successfully trigger, we should also be denied.
            assertFalse(rateLimiter.request());
            denied++;

            time.tick(Duration.ofNanos(1_000));
        }

        assertEquals(limit.toMillis() / periodMs, count);
    }

    @Test
    void realFrequencyTest() {
        var time = Time.getCurrent();

        final RateLimiter rateLimiter = new RateLimiter(time, 5000);

        long start = time.nanoTime();

        int counter = 5000;
        while (true) {
            if (rateLimiter.requestAndTrigger()) {
                counter--;
                if (counter <= 0) {
                    break;
                }
            }
        }

        System.out.println("Elapsed: " + (time.nanoTime() - start) / 1_000_000.0 + " ms");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 100})
    @DisplayName("Frequency Test")
    void frequencyTest(final int periodMs) {
        final Random random = getRandomPrintSeed();

        final FakeTime time = new FakeTime(Duration.ofNanos(1));
        final RateLimiter rateLimiter = new RateLimiter(time, 1000.0 / periodMs);

        long count = 0;
        long denied = 0;

        final Duration limit = Duration.ofSeconds(1);
        while (isLessThan(time.elapsed(), limit)) {
            assertEquals(denied, rateLimiter.getDeniedRequests(), "invalid number of denied requests");

            // Check and see if the rate limiter will allow the action to be triggered.
            final boolean requestAccepted = rateLimiter.request();
            if (!requestAccepted) {
                denied++;
            } else {
                denied = 0;
            }

            assertEquals(denied, rateLimiter.getDeniedRequests(), "invalid number of denied requests");

            if (random.nextBoolean()) {
                if (rateLimiter.request()) {
                    rateLimiter.trigger();
                    count++;
                } else {
                    denied++;
                }
            } else if (rateLimiter.requestAndTrigger()) {
                count++;
                denied = 0;
            } else {
                denied++;
            }

            // If we successfully triggered above, we should now be denied until time advances. If we did not
            // successfully trigger, we should also be denied.
            assertFalse(rateLimiter.request());
            denied++;

            time.tick(Duration.ofNanos(1_000));
        }

        assertEquals(limit.toMillis() / periodMs, count);
    }
}

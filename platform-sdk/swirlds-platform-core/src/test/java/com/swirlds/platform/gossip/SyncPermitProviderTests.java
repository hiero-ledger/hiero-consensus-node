// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.common.units.TimeUnit.UNIT_NANOSECONDS;
import static com.swirlds.common.units.TimeUnit.UNIT_SECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.sync.config.SyncConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SyncPermitProviderTests {

    /**
     * Counts the number of currently available sync permits in the permit provider.
     *
     * @param permitProvider the permit provider to measure
     * @return the number of available permits
     */
    private static int countAvailablePermits(@NonNull final SyncPermitProvider permitProvider) {
        int count = 0;
        while (permitProvider.acquire()) {
            count++;
        }
        for (int i = 0; i < count; i++) {
            permitProvider.release();
        }
        return count;
    }

    @Test
    void capacityTest() {
        final Randotron randotron = Randotron.create();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final int permitCount = randotron.nextInt(10, 20);
        final SyncPermitProvider permitProvider = new SyncPermitProvider(platformContext, permitCount);

        for (int i = 0; i < permitCount; i++) {
            assertEquals(permitCount - i, countAvailablePermits(permitProvider));
            permitProvider.acquire();
        }

        assertFalse(permitProvider.acquire());
        assertEquals(0, countAvailablePermits(permitProvider));

        for (int i = 0; i < permitCount; i++) {
            assertEquals(i, countAvailablePermits(permitProvider));
            permitProvider.release();
        }

        assertEquals(permitCount, countAvailablePermits(permitProvider));

        assertThrows(IllegalStateException.class, permitProvider::release);
    }

    /**
     * This test has the following phases:
     *
     * <ol>
     * <li>Time passes, system is healthy. Available permits should remain constant.</li>
     * <li>System becomes unhealthy. Time passes but stays within the grace period,
     *     and so no revocations should happen yet.</li>
     * <li>System stays unhealthy. We should observe more and more
     *     permits revoked over time until we lose all permits.</li>
     * <li>System becomes healthy for a while. We immediately get back the minimum permit count,
     *     followed slowly by remaining permits.</li>
     * <li>System becomes unhealthy before all permits are returned.
     *     We continue for a little while in the grace period.</li>
     * <li>System remains unhealthy until we lose all permits.</li>
     * <li>System becomes healthy and stays healthy. Eventually all permits are returned.</li>
     * </ol>
     *
     * <pre>
     *  all revoked |                      ******                     ******
     *              |                     *     *                    *
     *  min healthy |                    *      *****               *
     *              |                   *            *             *
     * none revoked |*******************               ************
     *              -+--------+--------+--------+--------+--------+--------+
     *               1        2        3        4        5        6        7
     * </pre>
     */
    @Test
    void revocationTest() {
        final Randotron randotron = Randotron.create();

        final int permitCount = randotron.nextInt(10, 20);
        final Duration gracePeriod = Duration.ofSeconds(randotron.nextInt(1, 10));
        final double permitsLostPerSecond = 0.1 + randotron.nextDouble(1.0);
        final double permitsReturnedPerSecond = 0.1 + randotron.nextDouble(1.0);
        final int minimumHealthyPermitCount = randotron.nextInt(1, 3);

        final Instant startingTime = randotron.nextInstant();
        final Duration timeStep = Duration.ofMillis(100);

        final FakeTime time = new FakeTime(startingTime, Duration.ZERO);

        final Configuration configuration = new TestConfigBuilder()
                .withValue(SyncConfig_.UNHEALTHY_GRACE_PERIOD, gracePeriod)
                .withValue(SyncConfig_.PERMITS_REVOKED_PER_SECOND, permitsLostPerSecond)
                .withValue(SyncConfig_.PERMITS_RETURNED_PER_SECOND, permitsReturnedPerSecond)
                .withValue(SyncConfig_.MINIMUM_HEALTHY_UNREVOKED_PERMIT_COUNT, minimumHealthyPermitCount)
                .withValue(SyncConfig_.KEEP_SENDING_EVENTS_WHEN_UNHEALTHY, false)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withTime(time)
                .build();

        final SyncPermitProvider permitProvider = new SyncPermitProvider(platformContext, permitCount);

        // Phase 1: System is healthy
        final Instant phase1End = startingTime.plus(Duration.ofSeconds(100));
        while (time.now().isBefore(phase1End)) {
            assertEquals(permitCount, countAvailablePermits(permitProvider));
            time.tick(timeStep);
        }

        // Phase 2: System becomes unhealthy, continue to end of grace period
        final Instant unhealthyStartTime1 = time.now();

        final Instant phase2End = unhealthyStartTime1.plus(gracePeriod);
        while (time.now().isBefore(phase2End)) {
            final Duration unhealthyDuration = Duration.between(unhealthyStartTime1, time.now());
            permitProvider.reportUnhealthyDuration(unhealthyDuration);

            assertEquals(permitCount, countAvailablePermits(permitProvider));
            time.tick(timeStep);
        }

        // Phase 3: System stays unhealthy, permits are revoked. Eventually we run out of permits.
        final int secondsToExhaustPermits1 = (int) (permitCount / permitsLostPerSecond) + 1;
        final Instant phase3End = phase2End.plusSeconds(10 + secondsToExhaustPermits1);

        while (time.now().isBefore(phase3End)) {
            final Duration unhealthyDuration = Duration.between(unhealthyStartTime1, time.now());
            permitProvider.reportUnhealthyDuration(unhealthyDuration);

            final Duration timePastGracePeriod = unhealthyDuration.minus(gracePeriod);
            final double secondsPastGracePeriod =
                    UNIT_NANOSECONDS.convertTo(timePastGracePeriod.toNanos(), UNIT_SECONDS);
            final int expectedRevocations = (int) (secondsPastGracePeriod * permitsLostPerSecond);

            final int expectedPermitCount = Math.max(0, permitCount - expectedRevocations);

            assertEquals(expectedPermitCount, countAvailablePermits(permitProvider));
            time.tick(timeStep);
        }
        assertEquals(0, countAvailablePermits(permitProvider));

        // Phase 4: become healthy and stay that way for a while, but not long enough to return all permits
        permitProvider.reportUnhealthyDuration(Duration.ZERO);
        final Instant healthyStartTime1 = time.now();

        final int permitsToReturn = permitCount / 2;
        final Duration timeToReturnPermits = Duration.ofSeconds((int) (permitsToReturn / permitsReturnedPerSecond) + 1);
        final Instant phase4End = healthyStartTime1.plus(timeToReturnPermits);
        int expectedAvailablePermits = 0;
        while (time.now().isBefore(phase4End)) {
            final Duration healthyTime = Duration.between(healthyStartTime1, time.now());
            final int returnedPermits =
                    (int) (UNIT_NANOSECONDS.convertTo(healthyTime.toNanos(), UNIT_SECONDS) * permitsReturnedPerSecond);

            expectedAvailablePermits = Math.max(minimumHealthyPermitCount, returnedPermits);

            assertEquals(expectedAvailablePermits, countAvailablePermits(permitProvider));
            time.tick(timeStep);
        }

        // Phase 5: become unhealthy again before all permits are returned, stay in grace period
        final Instant unhealthyStartTime2 = time.now();

        final Instant phase5End = unhealthyStartTime2.plus(gracePeriod);
        while (time.now().isBefore(phase5End)) {
            final Duration unhealthyDuration = Duration.between(unhealthyStartTime2, time.now());
            permitProvider.reportUnhealthyDuration(unhealthyDuration);

            // We will continue regaining permits until we exit the grace period.
            final Duration healthyTime = Duration.between(healthyStartTime1, time.now());
            final int returnedPermits =
                    (int) (UNIT_NANOSECONDS.convertTo(healthyTime.toNanos(), UNIT_SECONDS) * permitsReturnedPerSecond);

            expectedAvailablePermits = Math.min(permitCount, returnedPermits);

            assertEquals(expectedAvailablePermits, countAvailablePermits(permitProvider));
            time.tick(timeStep);
        }

        // Recompute expected available permits (it's possible the last time step caused another permit to be returned)
        final Duration healthyTime = Duration.between(healthyStartTime1, time.now());
        final int returnedPermits =
                (int) (UNIT_NANOSECONDS.convertTo(healthyTime.toNanos(), UNIT_SECONDS) * permitsReturnedPerSecond);
        expectedAvailablePermits = Math.min(permitCount, returnedPermits);
        assertEquals(expectedAvailablePermits, countAvailablePermits(permitProvider));

        // Phase 6: remain unhealthy until we lose all permits
        final int secondsToExhaustPermits2 = (int) (expectedAvailablePermits / permitsLostPerSecond) + 1;
        final Instant phase6End = phase5End.plusSeconds(10 + secondsToExhaustPermits2);
        while (time.now().isBefore(phase6End)) {
            final Duration unhealthyDuration = Duration.between(unhealthyStartTime2, time.now());
            permitProvider.reportUnhealthyDuration(unhealthyDuration);

            final Duration timePastGracePeriod = unhealthyDuration.minus(gracePeriod);
            final double secondsPastGracePeriod =
                    UNIT_NANOSECONDS.convertTo(timePastGracePeriod.toNanos(), UNIT_SECONDS);
            final int expectedRevocations = (int) (secondsPastGracePeriod * permitsLostPerSecond);

            final int expectedPermitCount = Math.max(0, expectedAvailablePermits - expectedRevocations);

            assertEquals(expectedPermitCount, countAvailablePermits(permitProvider));
            time.tick(timeStep);
        }

        // Phase 7: become healthy and stay that way until all permits are returned.
        final Instant healthyStartTime2 = time.now();
        permitProvider.reportUnhealthyDuration(Duration.ZERO);

        final int secondsToReturnPermits = (int) (permitCount / permitsReturnedPerSecond) + 1;
        final Instant phase7End = healthyStartTime2.plusSeconds(10 + secondsToReturnPermits);
        while (time.now().isBefore(phase7End)) {
            final Duration currentHealthyTime = Duration.between(healthyStartTime2, time.now());
            final int currentlyReturnedPermits = (int)
                    (UNIT_NANOSECONDS.convertTo(currentHealthyTime.toNanos(), UNIT_SECONDS) * permitsReturnedPerSecond);

            expectedAvailablePermits =
                    Math.max(minimumHealthyPermitCount, Math.min(permitCount, currentlyReturnedPermits));

            assertEquals(expectedAvailablePermits, countAvailablePermits(permitProvider));
            time.tick(timeStep);
        }

        assertEquals(permitCount, countAvailablePermits(permitProvider));
    }

    @Test
    void waitForAllPermitsToBeReleasedTest() throws InterruptedException {
        final Randotron randotron = Randotron.create();

        final int permitCount = randotron.nextInt(10, 20);
        final SyncPermitProvider permitProvider =
                new SyncPermitProvider(TestPlatformContextBuilder.create().build(), permitCount);

        for (int i = 0; i < permitCount; i++) {
            permitProvider.acquire();
        }

        final AtomicBoolean permitsReleased = new AtomicBoolean(false);
        final Thread thread = new Thread(() -> {
            permitProvider.waitForAllPermitsToBeReleased();
            permitsReleased.set(true);
        });
        thread.start();

        for (int i = 0; i < permitCount - 1; i++) {
            permitProvider.release();
            assertFalse(permitsReleased.get());
            MILLISECONDS.sleep(10);
        }

        permitProvider.release();
        assertEventuallyTrue(permitsReleased::get, Duration.ofSeconds(1), "Permits were not released");
        thread.join();
    }

    @Test
    void revokeAllTest() {
        final Randotron randotron = Randotron.create();

        final int permitCount = randotron.nextInt(10, 20);
        final double permitsReturnedPerSecond = 0.1 + randotron.nextDouble(1.0);
        final int minimumHealthyPermitCount = randotron.nextInt(1, 3);

        final Instant startingTime = randotron.nextInstant();
        final Duration timeStep = Duration.ofMillis(100);

        final FakeTime time = new FakeTime(startingTime, Duration.ZERO);

        final Configuration configuration = new TestConfigBuilder()
                .withValue(SyncConfig_.PERMITS_RETURNED_PER_SECOND, permitsReturnedPerSecond)
                .withValue(SyncConfig_.MINIMUM_HEALTHY_UNREVOKED_PERMIT_COUNT, minimumHealthyPermitCount)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withTime(time)
                .build();

        final SyncPermitProvider permitProvider = new SyncPermitProvider(platformContext, permitCount);

        assertEquals(permitCount, countAvailablePermits(permitProvider));
        permitProvider.revokeAll();
        assertEquals(minimumHealthyPermitCount, countAvailablePermits(permitProvider));

        // As time goes forward, we should slowly regain permits.
        final int secondsToRegainPermits = (int) (permitCount / permitsReturnedPerSecond) + 1;
        final Instant end = startingTime.plus(Duration.ofSeconds(10 + secondsToRegainPermits));
        while (time.now().isBefore(end)) {
            final Duration healthyTime = Duration.between(startingTime, time.now());
            final int returnedPermits =
                    (int) (UNIT_NANOSECONDS.convertTo(healthyTime.toNanos(), UNIT_SECONDS) * permitsReturnedPerSecond);

            final int expectedAvailablePermits =
                    Math.max(minimumHealthyPermitCount, Math.min(permitCount, returnedPermits));

            assertEquals(expectedAvailablePermits, countAvailablePermits(permitProvider));
            time.tick(timeStep);
        }

        assertEquals(permitCount, countAvailablePermits(permitProvider));
    }
}

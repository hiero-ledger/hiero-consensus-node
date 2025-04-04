// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.permits;

import static com.swirlds.common.units.TimeUnit.UNIT_NANOSECONDS;
import static com.swirlds.common.units.TimeUnit.UNIT_SECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hiero.base.utility.CompareTo.isLessThan;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;

/**
 * Manages sync permits.
 */
public class SyncPermitProvider {

    /**
     * The total number of available permits.
     */
    private int totalPermits;

    private final SyncConfig syncConfig;

    /**
     * The number of permits that are currently held by a sync operation.
     */
    private int usedPermits;

    /**
     * Provides wall clock time.
     */
    private final Time time;

    /**
     * If true, the system is currently in a healthy state.
     */
    private boolean healthy;

    /**
     * If {@link #healthy} is true, this is the time when the system became healthy. If {@link #healthy} is false, this
     * is the time when the system became unhealthy.
     */
    private Instant statusStartTime;

    /**
     * A variable where we accumulate revoked permits. Used to help calculate {@link #revokedPermits}.
     */
    private int revokedPermitsAccumulator;

    /**
     * When the system is in an unhealthy state, revoked permits accumulate here. When the status changes back to
     * healthy, these permits are summed into {@link #revokedPermitsAccumulator}. Used to help calculate
     * {@link #revokedPermits}.
     */
    private double revokedPermitDelta;

    /**
     * When the system is in a healthy state, permits that have been revoked are returned here. When the status changes
     * back to unhealthy, these permits are summed into {@link #revokedPermitsAccumulator}. Used to help calculate
     * {@link #revokedPermits}.
     */
    private double returnedPermitDelta;

    /**
     * The total number of revoked permits.
     */
    private int revokedPermits;

    /**
     * The number of permits that are revoked per second when the system is unhealthy.
     */
    private final double permitsRevokedPerSecond;

    /**
     * The number of revoked permits that are returned per second when the system is healthy.
     */
    private final double permitsReturnedPerSecond;

    /**
     * The grace period that the system is given to become healthy before permits begin to be revoked.
     */
    private final Duration unhealthyGracePeriod;

    /**
     * The minimum number of non-revoked permits while healthy. While unhealthy, the minimum number is always 0.
     */
    private int minimumUnrevokedPermitCount;

    /**
     * The metrics for this class.
     */
    private final SyncPermitMetrics metrics;

    /**
     * Constructor.
     *
     * @param platformContext the platform context
     * @param totalPermits    the total number of available permits
     */
    public SyncPermitProvider(@NonNull final PlatformContext platformContext, final int totalPermits) {
        this.metrics = new SyncPermitMetrics(platformContext);

        this.time = platformContext.getTime();

        this.syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        permitsRevokedPerSecond = syncConfig.permitsRevokedPerSecond();
        permitsReturnedPerSecond = syncConfig.permitsReturnedPerSecond();
        unhealthyGracePeriod = syncConfig.unhealthyGracePeriod();

        setTotalPermits(totalPermits);

        // We are healthy at startup time
        healthy = true;
        statusStartTime = time.now();
    }

    /**
     * Attempt to acquire a permit to sync with a peer.
     *
     * @return true if a permit was acquired, false otherwise
     */
    public synchronized boolean acquire() {
        if (getAvailablePermits() > 0) {
            usedPermits++;
            updateMetrics();
            return true;
        }
        return false;
    }

    /**
     * Report the health status of the system.
     *
     * @param duration the duration for which the system has been unhealthy, or {@link Duration#ZERO} if the system has
     *                 become healthy again
     */
    public synchronized void reportUnhealthyDuration(@NonNull final Duration duration) {
        if (isLessThan(duration, unhealthyGracePeriod)) {
            if (!healthy) {
                computeRevokedPermits();
                healthy = true;
                statusStartTime = time.now();
                revokedPermitsAccumulator += (int) revokedPermitDelta;
                revokedPermitDelta = 0;
            }
        } else {
            if (healthy) {
                computeRevokedPermits();
                healthy = false;
                statusStartTime = time.now();
                revokedPermitsAccumulator -= (int) returnedPermitDelta;
                returnedPermitDelta = 0;
            }
        }
    }

    /**
     * Immediately revoke all permits.
     */
    public synchronized void revokeAll() {
        returnedPermitDelta = 0;
        revokedPermitDelta = 0;
        revokedPermitsAccumulator = totalPermits;
        revokedPermits = totalPermits;
        statusStartTime = time.now();
        updateMetrics();
    }

    /**
     * Release a permit. Should be called exactly once for each call to {@link #acquire()} that returns true.
     */
    public synchronized void release() {
        if (usedPermits == 0) {
            throw new IllegalStateException("No permits to release");
        }
        usedPermits--;
        updateMetrics();
    }

    /**
     * Blocks until all permits have been released.
     */
    public void waitForAllPermitsToBeReleased() {
        while (true) {
            synchronized (this) {
                if (usedPermits == 0) {
                    return;
                }
            }
            try {
                MILLISECONDS.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted while waiting for all permits to be released", e);
            }
        }
    }

    /**
     * Get the number of permits that are currently available.
     *
     * @return the number of permits that are currently available
     */
    private int getAvailablePermits() {
        computeRevokedPermits();

        return Math.max(0, totalPermits - usedPermits - revokedPermits);
    }

    /**
     * Compute the number of permits that are currently unhealthy.
     */
    private void computeRevokedPermits() {
        if (healthy) {
            // When healthy, permits are gradually returned

            if (revokedPermits == 0) {
                // No permits are currently revoked, no further computation needed.
                return;
            }

            final Duration healthyDuration = Duration.between(statusStartTime, time.now());
            final double healthySeconds = UNIT_NANOSECONDS.convertTo(healthyDuration.toNanos(), UNIT_SECONDS);
            returnedPermitDelta = Math.min(revokedPermitsAccumulator, healthySeconds * permitsReturnedPerSecond);

            if (returnedPermitDelta == revokedPermitsAccumulator) {
                // We have returned all permits that were revoked.
                revokedPermitsAccumulator = 0;
                returnedPermitDelta = 0;
            }
        } else {
            // We mark more permits as unusable the longer the system has been unhealthy

            if (revokedPermits == totalPermits) {
                // All permits are already revoked, no further computation needed.
                return;
            }

            final Duration unhealthyDuration = Duration.between(statusStartTime, time.now());
            final double unhealthySeconds = UNIT_NANOSECONDS.convertTo(unhealthyDuration.toNanos(), UNIT_SECONDS);
            revokedPermitDelta = Math.min(totalPermits, unhealthySeconds * permitsRevokedPerSecond);

            if (revokedPermitDelta == totalPermits) {
                // We have revoked all permits.
                revokedPermitsAccumulator = totalPermits;
                revokedPermitDelta = 0;
            }
        }

        // When healthy there is a hard upper limit on the number of permits that may be revoked.
        final int maximumRevokedCount = healthy ? (totalPermits - minimumUnrevokedPermitCount) : totalPermits;

        // Combine all pieces of the revoked permit calculation into the final count.
        revokedPermits = Math.min(
                maximumRevokedCount, revokedPermitsAccumulator + (int) revokedPermitDelta - (int) returnedPermitDelta);
    }

    /**
     * Update the metrics with the current permit counts.
     */
    private void updateMetrics() {
        metrics.reportPermits(getAvailablePermits(), revokedPermits, usedPermits);
    }

    /**
     * Modify number of total permits
     * @param totalPermits new number of total permits
     */
    public synchronized void setTotalPermits(int totalPermits) {
        this.totalPermits = totalPermits;
        this.minimumUnrevokedPermitCount =
                Math.min(Math.max(totalPermits, 1), syncConfig.minimumHealthyUnrevokedPermitCount());
    }

    /**
     * Set total number of permits to previous number + passed difference
     * @param permitsDifference positive to add permits, negative to remove permits
     */
    public synchronized void adjustTotalPermits(int permitsDifference) {
        setTotalPermits(Math.max(0, totalPermits + permitsDifference));
    }
}

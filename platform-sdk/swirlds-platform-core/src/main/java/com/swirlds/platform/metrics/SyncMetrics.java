// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_3;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_15_3;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_8_1;
import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.base.units.UnitConstants;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.extensions.CountPerSecond;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphSynchronizer;
import com.swirlds.platform.gossip.shadowgraph.SyncResult;
import com.swirlds.platform.gossip.shadowgraph.SyncTiming;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.stats.AverageAndMax;
import com.swirlds.platform.stats.AverageAndMaxTimeStat;
import com.swirlds.platform.stats.AverageStat;
import com.swirlds.platform.stats.AverageTimeStat;
import com.swirlds.platform.stats.MaxStat;
import com.swirlds.platform.system.PlatformStatNames;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.temporal.ChronoUnit;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Interface to update relevant sync statistics
 */
public class SyncMetrics {

    private static final RunningAverageMetric.Config AVG_BYTES_PER_SEC_SYNC_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "bytes_per_sec_sync")
            .withDescription("average number of bytes per second transferred during a sync");
    private final RunningAverageMetric avgBytesPerSecSync;

    private static final CountPerSecond.Config CALL_SYNCS_PER_SECOND_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "sync_per_secC")
            .withDescription("(call syncs) syncs completed per second initiated by this member");
    private final CountPerSecond callSyncsPerSecond;

    private static final CountPerSecond.Config REC_SYNCS_PER_SECOND_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "sync_per_secR")
            .withDescription("(receive syncs) syncs completed per second initiated by other member");
    private final CountPerSecond recSyncsPerSecond;

    private static final RunningAverageMetric.Config TIPS_PER_SYNC_CONFIG = new RunningAverageMetric.Config(
                    INTERNAL_CATEGORY, PlatformStatNames.TIPS_PER_SYNC)
            .withDescription("the average number of tips per sync at the start of each sync")
            .withFormat(FORMAT_15_3);

    private static final CountPerSecond.Config INCOMING_SYNC_REQUESTS_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "incomingSyncRequests_per_sec")
            .withDescription("Incoming sync requests received per second");
    private final CountPerSecond incomingSyncRequestsPerSec;

    private static final CountPerSecond.Config ACCEPTED_SYNC_REQUESTS_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "acceptedSyncRequests_per_sec")
            .withDescription("Incoming sync requests accepted per second");
    private final CountPerSecond acceptedSyncRequestsPerSec;

    private static final CountPerSecond.Config OPPORTUNITIES_TO_INITIATE_SYNC_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "opportunitiesToInitiateSync_per_sec")
            .withDescription("Opportunities to initiate an outgoing sync per second");
    private final CountPerSecond opportunitiesToInitiateSyncPerSec;

    private static final CountPerSecond.Config OUTGOING_SYNC_REQUESTS_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "outgoingSyncRequests_per_sec")
            .withDescription("Outgoing sync requests sent per second");
    private final CountPerSecond outgoingSyncRequestsPerSec;

    private static final CountPerSecond.Config SYNCS_PER_SECOND_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "syncs_per_sec")
            .withDescription("Total number of syncs completed per second");
    private final CountPerSecond syncsPerSec;

    private static final RunningAverageMetric.Config SYNC_FILTER_TIME_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "syncFilterTime")
            .withDescription("the average time spent filtering events during a sync")
            .withUnit("nanoseconds");

    private static final CountPerSecond.Config DO_NOT_SYNC_PLATFORM_STATUS = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "doNotSyncPlatformStatus")
            .withUnit("hz")
            .withDescription("Number of times per second we do not sync because the platform status doesn't permit it");
    private final CountPerSecond doNoSyncPlatformStatus;

    private static final CountPerSecond.Config DO_NOT_SYNC_COOLDOWN_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "doNotSyncCooldown")
            .withUnit("hz")
            .withDescription("Number of times per second we do not sync because we are in sync cooldown");
    private final CountPerSecond doNotSyncCooldown;

    private static final CountPerSecond.Config DO_NOT_SYNC_HALTED_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "doNotSyncHalted")
            .withUnit("hz")
            .withDescription("Number of times per second we do not sync because gossip is halted");
    private final CountPerSecond doNotSyncHalted;

    private static final CountPerSecond.Config DO_NOT_SYNC_FALLEN_BEHIND_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "doNotSyncFallenBehind")
            .withUnit("hz")
            .withDescription("Number of times per second we do not sync because we have fallen behind");
    private final CountPerSecond doNotSyncFallenBehind;

    private static final CountPerSecond.Config DO_NOT_SYNC_NO_PERMITS_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "doNotSyncNoPermits")
            .withUnit("hz")
            .withDescription("Number of times per second we do not sync because we have no permits");
    private final CountPerSecond doNotSyncNoPermits;

    private static final CountPerSecond.Config DO_NOT_SYNC_INTAKE_COUNTER_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "doNotSyncIntakeCounter")
            .withUnit("hz")
            .withDescription("Number of times per second we do not sync because the intake counter is too high");
    private final CountPerSecond doNotSyncIntakeCounter;

    private final RunningAverageMetric tipsPerSync;

    private final AverageStat syncIndicatorDiff;
    private final AverageStat eventRecRate;
    private final AverageTimeStat avgSyncDuration1;
    private final AverageTimeStat avgSyncDuration2;
    private final AverageTimeStat avgSyncDuration3;
    private final AverageTimeStat avgSyncDuration4;
    private final AverageTimeStat avgSyncDuration5;
    private final AverageAndMaxTimeStat avgSyncDuration;
    private final AverageStat knownSetSize;
    private final AverageAndMax avgEventsPerSyncSent;
    private final AverageAndMax avgEventsPerSyncRec;
    private final MaxStat multiTipsPerSync;
    private final RunningAverageMetric syncFilterTime;

    /**
     * Constructor of {@code SyncMetrics}
     *
     * @param metrics a reference to the metrics-system
     * @throws IllegalArgumentException if {@code metrics} is {@code null}
     */
    public SyncMetrics(final Metrics metrics) {
        avgBytesPerSecSync = metrics.getOrCreate(AVG_BYTES_PER_SEC_SYNC_CONFIG);
        callSyncsPerSecond = new CountPerSecond(metrics, CALL_SYNCS_PER_SECOND_CONFIG);
        recSyncsPerSecond = new CountPerSecond(metrics, REC_SYNCS_PER_SECOND_CONFIG);
        tipsPerSync = metrics.getOrCreate(TIPS_PER_SYNC_CONFIG);

        incomingSyncRequestsPerSec = new CountPerSecond(metrics, INCOMING_SYNC_REQUESTS_CONFIG);
        acceptedSyncRequestsPerSec = new CountPerSecond(metrics, ACCEPTED_SYNC_REQUESTS_CONFIG);
        opportunitiesToInitiateSyncPerSec = new CountPerSecond(metrics, OPPORTUNITIES_TO_INITIATE_SYNC_CONFIG);
        outgoingSyncRequestsPerSec = new CountPerSecond(metrics, OUTGOING_SYNC_REQUESTS_CONFIG);
        syncsPerSec = new CountPerSecond(metrics, SYNCS_PER_SECOND_CONFIG);
        syncFilterTime = metrics.getOrCreate(SYNC_FILTER_TIME_CONFIG);

        doNoSyncPlatformStatus = new CountPerSecond(metrics, DO_NOT_SYNC_PLATFORM_STATUS);
        doNotSyncCooldown = new CountPerSecond(metrics, DO_NOT_SYNC_COOLDOWN_CONFIG);
        doNotSyncHalted = new CountPerSecond(metrics, DO_NOT_SYNC_HALTED_CONFIG);
        doNotSyncFallenBehind = new CountPerSecond(metrics, DO_NOT_SYNC_FALLEN_BEHIND_CONFIG);
        doNotSyncNoPermits = new CountPerSecond(metrics, DO_NOT_SYNC_NO_PERMITS_CONFIG);
        doNotSyncIntakeCounter = new CountPerSecond(metrics, DO_NOT_SYNC_INTAKE_COUNTER_CONFIG);

        avgSyncDuration = new AverageAndMaxTimeStat(
                metrics,
                ChronoUnit.SECONDS,
                INTERNAL_CATEGORY,
                "sec_per_sync",
                "duration of average successful sync (in seconds)");

        avgEventsPerSyncSent = new AverageAndMax(
                metrics, PLATFORM_CATEGORY, "ev_per_syncS", "number of events sent per successful sync", FORMAT_8_1);
        avgEventsPerSyncRec = new AverageAndMax(
                metrics,
                PLATFORM_CATEGORY,
                "ev_per_syncR",
                "number of events received per successful sync",
                FORMAT_8_1);

        syncIndicatorDiff = new AverageStat(
                metrics,
                INTERNAL_CATEGORY,
                "syncIndicatorDiff",
                "number of ancient indicators ahead (positive) or behind (negative) when syncing",
                FORMAT_8_1,
                AverageStat.WEIGHT_VOLATILE);
        eventRecRate = new AverageStat(
                metrics,
                INTERNAL_CATEGORY,
                "eventRecRate",
                "the rate at which we receive and enqueue events in ev/sec",
                FORMAT_8_1,
                AverageStat.WEIGHT_VOLATILE);

        avgSyncDuration1 = new AverageTimeStat(
                metrics,
                ChronoUnit.SECONDS,
                INTERNAL_CATEGORY,
                "sec_per_sync1",
                "duration of step 1 of average successful sync (in seconds)");
        avgSyncDuration2 = new AverageTimeStat(
                metrics,
                ChronoUnit.SECONDS,
                INTERNAL_CATEGORY,
                "sec_per_sync2",
                "duration of step 2 of average successful sync (in seconds)");
        avgSyncDuration3 = new AverageTimeStat(
                metrics,
                ChronoUnit.SECONDS,
                INTERNAL_CATEGORY,
                "sec_per_sync3",
                "duration of step 3 of average successful sync (in seconds)");
        avgSyncDuration4 = new AverageTimeStat(
                metrics,
                ChronoUnit.SECONDS,
                INTERNAL_CATEGORY,
                "sec_per_sync4",
                "duration of step 4 of average successful sync (in seconds)");
        avgSyncDuration5 = new AverageTimeStat(
                metrics,
                ChronoUnit.SECONDS,
                INTERNAL_CATEGORY,
                "sec_per_sync5",
                "duration of step 5 of average successful sync (in seconds)");

        knownSetSize = new AverageStat(
                metrics,
                PLATFORM_CATEGORY,
                "knownSetSize",
                "the average size of the known set during a sync",
                FORMAT_10_3,
                AverageStat.WEIGHT_VOLATILE);

        multiTipsPerSync = new MaxStat(
                metrics,
                PLATFORM_CATEGORY,
                PlatformStatNames.MULTI_TIPS_PER_SYNC,
                "the number of creators that have more than one tip at the start of each sync",
                "%5d");
    }

    /**
     * Supplies the event window numbers of a sync for statistics
     *
     * @param self  event window of our graph at the start of the sync
     * @param other event window of their graph at the start of the sync
     */
    public void eventWindow(@NonNull final EventWindow self, @NonNull final EventWindow other) {
        syncIndicatorDiff.update(self.getAncientThreshold() - other.getAncientThreshold());
    }

    /**
     * Supplies information about the rate of receiving events when all events are read
     *
     * @param nanosStart     The {@link System#nanoTime()} when we started receiving events
     * @param numberReceived the number of events received
     */
    public void eventsReceived(final long nanosStart, final int numberReceived) {
        if (numberReceived == 0) {
            return;
        }
        final double nanos = ((double) System.nanoTime()) - nanosStart;
        final double seconds = nanos / ChronoUnit.SECONDS.getDuration().toNanos();
        eventRecRate.update(Math.round(numberReceived / seconds));
    }

    /**
     * Record all stats related to sync timing
     *
     * @param timing object that holds the timing data
     * @param conn   the sync connections
     */
    public void recordSyncTiming(final SyncTiming timing, final Connection conn) {
        avgSyncDuration1.update(timing.getTimePoint(0), timing.getTimePoint(1));
        avgSyncDuration2.update(timing.getTimePoint(1), timing.getTimePoint(2));
        avgSyncDuration3.update(timing.getTimePoint(2), timing.getTimePoint(3));
        avgSyncDuration4.update(timing.getTimePoint(3), timing.getTimePoint(4));
        avgSyncDuration5.update(timing.getTimePoint(4), timing.getTimePoint(5));

        avgSyncDuration.update(timing.getTimePoint(0), timing.getTimePoint(5));
        final double syncDurationSec = timing.getPointDiff(5, 0) * UnitConstants.NANOSECONDS_TO_SECONDS;
        final double speed = Math.max(
                        conn.getDis().getSyncByteCounter().getCount(),
                        conn.getDos().getSyncByteCounter().getCount())
                / syncDurationSec;

        // set the bytes/sec speed of the sync currently measured
        avgBytesPerSecSync.update(speed);
    }

    /**
     * Records the size of the known set during a sync. This is the most compute intensive part of the sync, so this is
     * useful information to validate sync performance.
     *
     * @param knownSetSize the size of the known set
     */
    public void knownSetSize(final int knownSetSize) {
        this.knownSetSize.update(knownSetSize);
    }

    /**
     * Notifies the stats that a sync is done
     *
     * @param info information about the sync that occurred
     */
    public void syncDone(final SyncResult info) {
        if (info.isCaller()) {
            callSyncsPerSecond.count();
        } else {
            recSyncsPerSecond.count();
        }
        syncsPerSec.count();

        avgEventsPerSyncSent.update(info.getEventsWritten());
        avgEventsPerSyncRec.update(info.getEventsRead());
    }

    /**
     * Called by {@link ShadowgraphSynchronizer} to update the {@code tips/sync} statistic with the number of creators
     * that have more than one {@code sendTip} in the current synchronization.
     *
     * @param multiTipCount the number of creators in the current synchronization that have more than one sending tip.
     */
    public void updateMultiTipsPerSync(final int multiTipCount) {
        multiTipsPerSync.update(multiTipCount);
    }

    /**
     * Called by {@link ShadowgraphSynchronizer} to update the {@code tips/sync} statistic with the number of
     * {@code sendTips} in the current synchronization.
     *
     * @param tipCount the number of sending tips in the current synchronization.
     */
    public void updateTipsPerSync(final int tipCount) {
        tipsPerSync.update(tipCount);
    }

    /**
     * Indicate that a request to sync has been received
     */
    public void incomingSyncRequestReceived() {
        incomingSyncRequestsPerSec.count();
    }

    /**
     * Indicate that a request to sync has been accepted
     */
    public void acceptedSyncRequest() {
        acceptedSyncRequestsPerSec.count();
    }

    /**
     * Indicate that there was an opportunity to sync with a peer. The protocol may or may not take the opportunity
     */
    public void opportunityToInitiateSync() {
        opportunitiesToInitiateSyncPerSec.count();
    }

    /**
     * Indicate that a request to sync has been sent
     */
    public void outgoingSyncRequestSent() {
        outgoingSyncRequestsPerSec.count();
    }

    /**
     * Record the amount of time spent filtering events during a sync.
     *
     * @param nanoseconds the amount of time spent filtering events during a sync
     */
    public void recordSyncFilterTime(final long nanoseconds) {
        syncFilterTime.update(nanoseconds);
    }

    /**
     * Signal that we chose not to sync because of the current platform status
     */
    public void doNotSyncPlatformStatus() {
        doNoSyncPlatformStatus.count();
    }

    /**
     * Signal that we chose not to sync because we are in sync cooldown.
     */
    public void doNotSyncCooldown() {
        doNotSyncCooldown.count();
    }

    /**
     * Signal that we chose not to sync because gossip is halted.
     */
    public void doNotSyncHalted() {
        doNotSyncHalted.count();
    }

    /**
     * Signal that we chose not to sync because we have fallen behind.
     */
    public void doNotSyncFallenBehind() {
        doNotSyncFallenBehind.count();
    }

    /**
     * Signal that we chose not to sync because we have no permits.
     */
    public void doNotSyncNoPermits() {
        doNotSyncNoPermits.count();
    }

    /**
     * Signal that we chose not to sync because the intake counter is too high.
     */
    public void doNotSyncIntakeCounter() {
        doNotSyncIntakeCounter.count();
    }
}

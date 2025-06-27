// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_0;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_3;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_15_3;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_8_1;
import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.base.time.Time;
import com.swirlds.base.units.UnitConstants;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.extensions.CountPerSecond;
import com.swirlds.common.metrics.extensions.PhaseTimer;
import com.swirlds.common.metrics.extensions.PhaseTimerBuilder;
import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphSynchronizer;
import com.swirlds.platform.gossip.shadowgraph.SyncPhase;
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
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * Interface to update relevant sync statistics
 */
public class SyncMetrics {

    private static final RunningAverageMetric.Config AVG_BYTES_PER_SEC_SYNC_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "bytes_per_sec_sync")
            .withDescription("average number of bytes per second transferred during a sync");
    private final RunningAverageMetric avgBytesPerSecSync;

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

    private static final CountPerSecond.Config CALL_SYNCS_PER_SECOND_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "sync_per_secC")
            .withDescription("(call syncs) syncs completed per second initiated by this member");
    private final CountPerSecond callSyncsPerSecond;

    private static final CountPerSecond.Config REC_SYNCS_PER_SECOND_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "sync_per_secR")
            .withDescription("(receive syncs) syncs completed per second initiated by other member");
    private final CountPerSecond recSyncsPerSecond;

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

    private static final CountPerSecond.Config DO_NOT_SYNC_PEER_FALLEN_BEHIND_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "doNotSyncRemoteFallenBehind")
            .withUnit("hz")
            .withDescription("Number of times per second we do not sync because peer node has fallen behind");
    private final CountPerSecond doNotSyncPeerFallenBehind;

    private static final CountPerSecond.Config DO_NOT_SYNC_PEER_PROCESSING_EVENTS_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "doNotSyncRemoteProcessingEvents")
            .withUnit("hz")
            .withDescription(
                    "Number of times per second we do not initiate sync because peer node is still processing our events");
    private final CountPerSecond doNotSyncPeerProcessingEvents;

    private static final CountPerSecond.Config DO_NOT_SYNC_ALREADY_STARTED_CONFIG = new CountPerSecond.Config(
                    PLATFORM_CATEGORY, "doNotSyncAlreadyStarted")
            .withUnit("hz")
            .withDescription("Number of times per second we do not sync because we have already started sync");
    private final CountPerSecond doNotSyncAlreadyStarted;

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

    private final IntegerGauge.Config RPC_READ_THREAD_RUNNING_CONFIG = new IntegerGauge.Config(
                    Metrics.PLATFORM_CATEGORY, "rpcReadThreadRunning")
            .withDescription("number of rpc thread running in read mode");

    private final IntegerGauge.Config RPC_WRITE_THREAD_RUNNING_CONFIG = new IntegerGauge.Config(
                    Metrics.PLATFORM_CATEGORY, "rpcWriteThreadRunning")
            .withDescription("number of rpc thread running in write mode");

    private final IntegerGauge.Config RPC_DISPATCH_THREAD_RUNNING_CONFIG = new IntegerGauge.Config(
                    Metrics.PLATFORM_CATEGORY, "rpcDispatchThreadRunning")
            .withDescription("number of rpc thread running in dispatch mode");

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
    private final ConcurrentHashMap<NodeId, AverageAndMax> rpcOutputQueueSize = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, AverageAndMax> rpcInputQueueSize = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, PhaseTimer<SyncPhase>> syncPhasePerNode = new ConcurrentHashMap<>();
    private final Metrics metrics;
    private final AverageAndMax outputQueuePollTime;
    private final Time time;
    private final IntegerGauge rpcReadThreadRunning;
    private final IntegerGauge rpcWriteThreadRunning;
    private final IntegerGauge rpcDispatchThreadRunning;

    /**
     * Constructor of {@code SyncMetrics}
     *
     * @param metrics a reference to the metrics-system
     * @param time    time source for the system
     * @throws IllegalArgumentException if {@code metrics} is {@code null}
     */
    public SyncMetrics(final Metrics metrics, final Time time) {
        this.metrics = Objects.requireNonNull(metrics);
        this.time = Objects.requireNonNull(time);
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
        doNotSyncPeerFallenBehind = new CountPerSecond(metrics, DO_NOT_SYNC_PEER_FALLEN_BEHIND_CONFIG);
        doNotSyncPeerProcessingEvents = new CountPerSecond(metrics, DO_NOT_SYNC_PEER_PROCESSING_EVENTS_CONFIG);
        doNotSyncAlreadyStarted = new CountPerSecond(metrics, DO_NOT_SYNC_ALREADY_STARTED_CONFIG);
        doNotSyncNoPermits = new CountPerSecond(metrics, DO_NOT_SYNC_NO_PERMITS_CONFIG);
        doNotSyncIntakeCounter = new CountPerSecond(metrics, DO_NOT_SYNC_INTAKE_COUNTER_CONFIG);

        rpcReadThreadRunning = metrics.getOrCreate(RPC_READ_THREAD_RUNNING_CONFIG);
        rpcWriteThreadRunning = metrics.getOrCreate(RPC_WRITE_THREAD_RUNNING_CONFIG);
        rpcDispatchThreadRunning = metrics.getOrCreate(RPC_DISPATCH_THREAD_RUNNING_CONFIG);

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

        outputQueuePollTime = new AverageAndMax(
                metrics,
                PLATFORM_CATEGORY,
                "rpc_output_queue_poll_time",
                "amount of us spent sleeping waiting for poll to happen or timeout on rpc output queue",
                FORMAT_10_0);
    }

    /**
     * Supplies the event window numbers of a sync for statistics
     *
     * @param self  event window of our graph at the start of the sync
     * @param other event window of their graph at the start of the sync
     */
    public void eventWindow(@NonNull final EventWindow self, @NonNull final EventWindow other) {
        syncIndicatorDiff.update(self.ancientThreshold() - other.ancientThreshold());
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
     * @param info                   information about the sync that occurred
     * @param usedOutgoingConnection optional boolean which indicates if we have used outgoing connection (true),
     *                               incoming connection (false), or we don't know (null)
     */
    public void syncDone(final SyncResult info, final @Nullable Boolean usedOutgoingConnection) {
        syncsPerSec.count();

        if (usedOutgoingConnection != null) {
            if (usedOutgoingConnection) {
                callSyncsPerSecond.count();
            } else {
                recSyncsPerSecond.count();
            }
        }

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
     * Signal that we chose not to sync because peer has fallen behind.
     */
    public void doNotSyncPeerFallenBehind() {
        doNotSyncPeerFallenBehind.count();
    }

    /**
     * Signal that we chose not to sync because peer is still processing our events.
     */
    public void doNotSyncPeerProcessingEvents() {
        doNotSyncPeerProcessingEvents.count();
    }

    /**
     * Signal that we chose not to sync because we have already sent initial message
     */
    public void doNotSyncAlreadyStarted() {
        doNotSyncAlreadyStarted.count();
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

    /**
     * Report size of the outgoing queue
     *
     * @param size size of the queue
     */
    public void rpcOutputQueueSize(final NodeId node, final int size) {

        rpcOutputQueueSize
                .computeIfAbsent(
                        node,
                        nodeId -> new AverageAndMax(
                                metrics,
                                PLATFORM_CATEGORY,
                                String.format("rpc_output_queue_size_%02d", nodeId.id()),
                                String.format("gossip rpc output queue size to node %02d", nodeId.id()),
                                FloatFormats.FORMAT_10_0,
                                AverageStat.WEIGHT_VOLATILE))
                .update(size);
    }

    /**
     * Report size of the outgoing queue
     *
     * @param size size of the queue
     */
    public void rpcInputQueueSize(final NodeId node, final int size) {

        rpcInputQueueSize
                .computeIfAbsent(
                        node,
                        nodeId -> new AverageAndMax(
                                metrics,
                                PLATFORM_CATEGORY,
                                String.format("rpc_input_queue_size_%02d", nodeId.id()),
                                String.format("gossip rpc input queue size from node %02d", nodeId.id()),
                                FloatFormats.FORMAT_10_0,
                                AverageStat.WEIGHT_VOLATILE))
                .update(size);
    }

    /**
     * Time spent sleeping waiting for poll to happen or timeout. Please note that you are supposed to pass nanos here,
     * but metric will be reporting microseconds
     *
     * @param nanos amount of nanoseconds which have passed
     */
    public void outputQueuePollTime(final long nanos) {
        outputQueuePollTime.update(nanos / 1000);
    }

    /**
     * Report the current rpc sync phase with specific node
     *
     * @param node      node id of the peer this sync phase applies to
     * @param syncPhase the current rpc sync phase we are in with the peer
     * @return the previously reported phase. This will be {@link SyncPhase#OUTSIDE_OF_RPC} when invoked at the
     * beginning of a new rypc sync.
     */
    public SyncPhase reportSyncPhase(@NonNull final NodeId node, @NonNull final SyncPhase syncPhase) {
        final PhaseTimer<SyncPhase> phaseMetric = syncPhasePerNode.computeIfAbsent(
                node, nodeId -> new PhaseTimerBuilder<>(metrics, time, "platform", SyncPhase.class)
                        .enableFractionalMetrics()
                        .setInitialPhase(SyncPhase.OUTSIDE_OF_RPC)
                        .setMetricsNamePrefix(String.format("sync_phase_%02d", nodeId.id()))
                        .build());
        synchronized (phaseMetric) {
            final SyncPhase oldPhase = phaseMetric.getActivePhase();
            phaseMetric.activatePhase(syncPhase);
            return oldPhase;
        }
    }

    /**
     * Update amount of rpc read threads running concurrently
     *
     * @param change The amount of change in the number of threads running (negative numbers mean that many fewer
     *               threads running, positive numbers mean that many more thread running).
     */
    public void rpcReadThreadRunning(final int change) {
        rpcReadThreadRunning.add(change);
    }

    /**
     * Update amount of rpc write threads running concurrently
     *
     * @param change The amount of change in the number of threads running (negative numbers mean that many fewer
     *               threads running, positive numbers mean that many more thread running).
     */
    public void rpcWriteThreadRunning(final int change) {
        rpcWriteThreadRunning.add(change);
    }

    /**
     * Update amount of rpc dispatch threads running concurrently
     *
     * @param change The amount of change in the number of threads running (negative numbers mean that many fewer
     *               threads running, positive numbers mean that many more thread running).
     */
    public void rpcDispatchThreadRunning(final int change) {
        rpcDispatchThreadRunning.add(change);
    }
}

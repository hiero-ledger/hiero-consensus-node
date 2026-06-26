// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync;

import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Stats for learner reconnect.
 */
public class LearnerSyncMetrics {

    private static final String RECONNECT_MAP_CATEGORY = "reconnect_vmap";

    private final LongGauge transfersFromTeacher;
    private final LongGauge transfersFromLearner;

    private final LongGauge internalHashes;
    private final LongGauge internalCleanHashes;

    private final LongGauge leafData;
    private final LongGauge leafCleanData;

    /**
     * Create an instance of ReconnectMapMetrics.
     *
     * @param metrics a non-null Metrics object
     */
    public LearnerSyncMetrics(@NonNull final Metrics metrics) {
        Objects.requireNonNull(metrics, "metrics must not be null");

        this.transfersFromTeacher =
                metrics.getOrCreate(new LongGauge.Config(RECONNECT_MAP_CATEGORY, "transfersFromTeacherTotal")
                        .withDescription("number of transfers from teacher to learner"));
        this.transfersFromLearner =
                metrics.getOrCreate(new LongGauge.Config(RECONNECT_MAP_CATEGORY, "transfersFromLearnerTotal")
                        .withDescription("number of transfers from learner to teacher"));

        this.internalHashes = metrics.getOrCreate(new LongGauge.Config(RECONNECT_MAP_CATEGORY, "internalHashesTotal")
                .withDescription("number of internal node hashes transferred"));
        this.internalCleanHashes =
                metrics.getOrCreate(new LongGauge.Config(RECONNECT_MAP_CATEGORY, "internalCleanHashesTotal")
                        .withDescription("number of clean internal node hashes transferred"));

        this.leafData = metrics.getOrCreate(new LongGauge.Config(RECONNECT_MAP_CATEGORY, "leafDataTotal")
                .withDescription("number of leaf node data transferred"));
        this.leafCleanData = metrics.getOrCreate(new LongGauge.Config(RECONNECT_MAP_CATEGORY, "leafCleanDataTotal")
                .withDescription("number of clean leaf node data transferred"));

        // Reset metric values to zeros on reconnect start
        resetMetrics();
    }

    /**
     * Reset metrics specific to this ReconnectMapMetrics instance to zero,
     * but do NOT reset the aggregateStats, if any. The aggregateStats
     * have already been reset earlier when the reconnect process
     * has started as a whole. We're only resetting label-specific metrics here
     * before we start the reconnect for this specific label.
     */
    private void resetMetrics() {
        transfersFromTeacher.set(0L);
        transfersFromLearner.set(0L);

        internalHashes.set(0L);
        internalCleanHashes.set(0L);

        leafData.set(0L);
        leafCleanData.set(0L);
    }

    /**
     * Increment a transfers from teacher counter.
     * <p>
     * Different reconnect algorithms may define the term "transfer" differently. Examples of a transfer from teacher: <br>
     * * a lesson from the teacher, <br>
     * * a response from the teacher per a prior request from the learner.
     */
    public void incrementTransfersFromTeacher() {
        transfersFromTeacher.add(1L);
    }

    /**
     * Increment a transfers from learner counter.
     * <p>
     * Different reconnect algorithms may define the term "transfer" differently. Examples of a transfer from learner: <br>
     * * a query response to the teacher for a single hash, <br>
     * * a request from the learner.
     */
    public void incrementTransfersFromLearner() {
        transfersFromLearner.add(1L);
    }

    /**
     * Gather stats about internal nodes hashes transfers.
     *
     * @param isClean if the transferred internal node was clean
     */
    public void incrementInternalHashes(boolean isClean) {
        internalHashes.add(1L);
        if (isClean) internalCleanHashes.add(1L);
    }

    /**
     * Gather stats about leaf nodes data transfers.
     *
     * @param isClean if the transferred leaf was clean
     */
    public void incrementLeafData(boolean isClean) {
        leafData.add(1L);
        if (isClean) leafCleanData.add(1L);
    }

    @NonNull
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ReconnectMapMetrics: ");

        sb.append("transfersFromTeacher=").append(transfersFromTeacher.get()).append("; ");
        sb.append("transfersFromLearner=").append(transfersFromLearner.get()).append("; ");
        sb.append("internalHashes=").append(internalHashes.get()).append("; ");
        sb.append("internalCleanHashes=").append(internalCleanHashes.get()).append("; ");
        sb.append("leafData=").append(leafData.get()).append("; ");
        sb.append("leafCleanData=").append(leafCleanData.get());

        return sb.toString();
    }
}

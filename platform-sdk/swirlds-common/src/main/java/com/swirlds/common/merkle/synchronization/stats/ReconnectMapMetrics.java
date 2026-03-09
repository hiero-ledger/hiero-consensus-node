// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.stats;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An implementation of ReconnectMapStats that emits all the stats as LongGauge metrics.
 */
public class ReconnectMapMetrics implements ReconnectMapStats {

    /** A map label as passed to the constructor, w/o any normalization. */
    @Nullable
    private final String originalLabel;

    private final ReconnectMapStats aggregateStats;

    private final AtomicLong transfersFromTeacher = new AtomicLong(0);
    private final AtomicLong transfersFromLearner = new AtomicLong(0);

    private final AtomicLong internalHashes = new AtomicLong(0);
    private final AtomicLong internalCleanHashes = new AtomicLong(0);
    private final AtomicLong internalData = new AtomicLong(0);
    private final AtomicLong internalCleanData = new AtomicLong(0);

    private final AtomicLong leafHashes = new AtomicLong(0);
    private final AtomicLong leafCleanHashes = new AtomicLong(0);
    private final AtomicLong leafData = new AtomicLong(0);
    private final AtomicLong leafCleanData = new AtomicLong(0);

    /**
     * Create an instance of ReconnectMapMetrics.
     * @param metrics a non-null Metrics object
     * @param originalLabel an optional label, e.g. a VirtualMap name or similar, may be null.
     *     If specified, the label is added to the names of the metrics.
     * @param aggregateStats an optional aggregateStats object to which all ReconnectMapStats calls will delegate
     *     in addition to emitting metrics for this object, may be null.
     */
    public ReconnectMapMetrics(
            @NonNull final Metrics metrics,
            @Nullable final String originalLabel,
            @Nullable final ReconnectMapStats aggregateStats) {
        Objects.requireNonNull(metrics, "metrics must not be null");
        this.originalLabel = originalLabel;
        this.aggregateStats = aggregateStats;
        // Normalize the label
        final String label = originalLabel == null ? null : originalLabel.replace('.', '_');

        // Reset metric values to zeros on reconnect start
        resetMetrics();
    }

    private static String formatName(final String name, final String label) {
        return (label == null || label.isBlank() ? name : (name + "_" + label + "_")) + "Total";
    }

    private static void add(final AtomicLong metric, final long value) {
        metric.addAndGet(value);
    }

    /**
     * Reset metrics specific to this ReconnectMapMetrics instance to zero,
     * but do NOT reset the aggregateStats, if any. The aggregateStats
     * have already been reset earlier when the reconnect process
     * has started as a whole. We're only resetting label-specific metrics here
     * before we start the reconnect for this specific label.
     */
    private void resetMetrics() {
        transfersFromTeacher.set(0);
        transfersFromLearner.set(0);

        internalHashes.set(0);
        internalCleanHashes.set(0);
        internalData.set(0);
        internalCleanData.set(0);

        leafHashes.set(0);
        leafCleanHashes.set(0);
        leafData.set(0);
        leafCleanData.set(0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementTransfersFromTeacher() {
        add(transfersFromTeacher, 1);
        if (aggregateStats != null) {
            aggregateStats.incrementTransfersFromTeacher();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementTransfersFromLearner() {
        add(transfersFromLearner, 1);
        if (aggregateStats != null) {
            aggregateStats.incrementTransfersFromLearner();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementInternalHashes(final int hashNum, final int cleanHashNum) {
        if (hashNum > 0) add(internalHashes, hashNum);
        if (cleanHashNum > 0) add(internalCleanHashes, cleanHashNum);
        if (aggregateStats != null) {
            aggregateStats.incrementInternalHashes(hashNum, cleanHashNum);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementInternalData(final int dataNum, final int cleanDataNum) {
        if (dataNum > 0) add(internalData, dataNum);
        if (cleanDataNum > 0) add(internalCleanData, cleanDataNum);
        if (aggregateStats != null) {
            aggregateStats.incrementInternalData(dataNum, cleanDataNum);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementLeafHashes(final int hashNum, final int cleanHashNum) {
        if (hashNum > 0) add(leafHashes, hashNum);
        if (cleanHashNum > 0) add(leafCleanHashes, cleanHashNum);
        if (aggregateStats != null) {
            aggregateStats.incrementLeafHashes(hashNum, cleanHashNum);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementLeafData(final int dataNum, final int cleanDataNum) {
        if (dataNum > 0) add(leafData, dataNum);
        if (cleanDataNum > 0) add(leafCleanData, cleanDataNum);
        if (aggregateStats != null) {
            aggregateStats.incrementLeafData(dataNum, cleanDataNum);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String format() {
        final StringBuilder sb = new StringBuilder("ReconnectMapMetrics: ");

        if (originalLabel != null) {
            sb.append("label=").append(originalLabel).append("; ");
        }

        sb.append("transfersFromTeacher=").append(transfersFromTeacher.get()).append("; ");
        sb.append("transfersFromLearner=").append(transfersFromLearner.get()).append("; ");

        sb.append("internalHashes=").append(internalHashes.get()).append("; ");
        sb.append("internalCleanHashes=").append(internalCleanHashes.get()).append("; ");
        sb.append("internalData=").append(internalData.get()).append("; ");
        sb.append("internalCleanData=").append(internalCleanData.get()).append("; ");
        sb.append("leafHashes=").append(leafHashes.get()).append("; ");
        sb.append("leafCleanHashes=").append(leafCleanHashes.get()).append("; ");
        sb.append("leafData=").append(leafData.get()).append("; ");
        sb.append("leafCleanData=").append(leafCleanData.get());

        return sb.toString();
    }
}

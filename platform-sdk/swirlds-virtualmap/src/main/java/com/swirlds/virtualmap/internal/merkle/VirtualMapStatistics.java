// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.IntegerAccumulator;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import java.util.Objects;

/**
 * Encapsulates statistics for a virtual map.
 */
public class VirtualMapStatistics {

    public static final String STAT_CATEGORY = "virtual_map";

    /** Metric name prefix for all virtual map metric names */
    private static final String VMAP_PREFIX = "vmap_";
    /** Prefix for all metrics related to virtual map queries */
    private static final String QUERIES_PREFIX = "queries_";
    /** Prefix for all lifecycle related metric names */
    private static final String LIFECYCLE_PREFIX = "lifecycle_";

    /** Virtual Map name */
    private final String label;

    /** Virtual map entities - total number */
    private LongGauge size;

    /** Virtual map entities - adds / s */
    private LongAccumulator addedEntities;
    /** Virtual map entities - updates / s */
    private LongAccumulator updatedEntities;
    /** Virtual map entities - deletes / s */
    private LongAccumulator removedEntities;
    /** Virtual map entities - reads / s */
    private LongAccumulator readEntities;

    /** Estimated virtual node cache size, bytes*/
    private LongGauge nodeCacheSizeB;
    /** Number of virtual root copies in the pipeline */
    private IntegerGauge pipelineSize;
    /** Flush backpressure duration, ms */
    private IntegerAccumulator flushBackpressureMs;
    /** Family size backpressure duration, ms */
    private IntegerAccumulator familySizeBackpressureMs;
    /** The average time to merge virtual map copy to the next copy, ms */
    private LongAccumulator mergeDurationMs;
    /** The average time to flush virtual map copy to disk (to data source), ms */
    private LongAccumulator flushDurationMs;
    /** The number of virtual root node copy flushes to data source */
    private Counter flushCount;
    /** The average time to hash virtual map copy, ms */
    private LongAccumulator hashDurationMs;

    private static LongAccumulator buildLongAccumulator(
            final Metrics metrics, final String name, final String description) {
        return metrics.getOrCreate(new LongAccumulator.Config(STAT_CATEGORY, name)
                .withInitialValue(0)
                .withAccumulator(Long::sum)
                .withDescription(description));
    }

    private static IntegerAccumulator buildIntegerAccumulator(
            final Metrics metrics, final String name, final String description) {
        return metrics.getOrCreate(new IntegerAccumulator.Config(STAT_CATEGORY, name)
                .withInitialValue(0)
                .withAccumulator(Integer::sum)
                .withDescription(description));
    }

    /**
     * Create a new statistics instance for a virtual map family.
     *
     * @param label
     * 		the label for the virtual map
     * @throws NullPointerException in case {@code label} parameter is {@code null}
     */
    public VirtualMapStatistics(final String label) {
        Objects.requireNonNull(label, "label must not be null");
        // "." may not appear in metric names
        this.label = label.replace('.', '_');
    }

    /**
     * Register all statistics with a registry.
     *
     * @param metrics
     * 		reference to the metrics system
     * @throws NullPointerException in case {@code metrics} parameter is {@code null}
     */
    public void registerMetrics(final Metrics metrics) {
        Objects.requireNonNull(metrics, "metrics must not be null");

        // Generic
        size = metrics.getOrCreate(new LongGauge.Config(STAT_CATEGORY, VMAP_PREFIX + "size_" + label)
                .withDescription("Virtual map size, " + label));

        // Queries
        addedEntities = buildLongAccumulator(
                metrics,
                VMAP_PREFIX + QUERIES_PREFIX + "addedEntities_" + label,
                "Added virtual map entities, " + label);
        updatedEntities = buildLongAccumulator(
                metrics,
                VMAP_PREFIX + QUERIES_PREFIX + "updatedEntities_" + label,
                "Updated virtual map entities, " + label + ", per second");
        removedEntities = buildLongAccumulator(
                metrics,
                VMAP_PREFIX + QUERIES_PREFIX + "removedEntities_" + label,
                "Removed virtual map entities, " + label + ", per second");
        readEntities = buildLongAccumulator(
                metrics,
                VMAP_PREFIX + QUERIES_PREFIX + "readEntities_" + label,
                "Read virtual map entities, " + label + ", per second");

        // Lifecycle
        nodeCacheSizeB = metrics.getOrCreate(
                new LongGauge.Config(STAT_CATEGORY, VMAP_PREFIX + LIFECYCLE_PREFIX + "nodeCacheSizeB_" + label)
                        .withDescription("Virtual node cache size, " + label + ", bytes"));
        pipelineSize = metrics.getOrCreate(
                new IntegerGauge.Config(STAT_CATEGORY, VMAP_PREFIX + LIFECYCLE_PREFIX + "pipelineSize_" + label)
                        .withDescription("Virtual pipeline size, " + label));
        flushBackpressureMs = buildIntegerAccumulator(
                metrics,
                VMAP_PREFIX + LIFECYCLE_PREFIX + "flushBackpressureMs_" + label,
                "Virtual pipeline flush backpressure, " + label + ", ms");
        familySizeBackpressureMs = buildIntegerAccumulator(
                metrics,
                VMAP_PREFIX + LIFECYCLE_PREFIX + "familySizeBackpressureMs_" + label,
                "Virtual pipeline family size backpressure, " + label + ", ms");
        mergeDurationMs = buildLongAccumulator(
                metrics,
                VMAP_PREFIX + LIFECYCLE_PREFIX + "mergeDurationMs_" + label,
                "Virtual root copy merge duration, " + label + ", ms");
        flushDurationMs = buildLongAccumulator(
                metrics,
                VMAP_PREFIX + LIFECYCLE_PREFIX + "flushDurationMs_" + label,
                "Virtual root copy flush duration, " + label + ", ms");
        flushCount = metrics.getOrCreate(
                new Counter.Config(STAT_CATEGORY, VMAP_PREFIX + LIFECYCLE_PREFIX + "flushCount_" + label)
                        .withDescription("Virtual root copy flush count, " + label));
        hashDurationMs = buildLongAccumulator(
                metrics,
                VMAP_PREFIX + LIFECYCLE_PREFIX + "hashDurationMs_" + label,
                "Virtual root copy hash duration, " + label + ", ms");
    }

    /**
     * Update the size statistic for the virtual map.
     *
     * @param size the value to set
     */
    public void setSize(final long size) {
        if (this.size != null) {
            this.size.set(size);
        }
    }

    /**
     * Increments {@link #addedEntities} stat by 1.
     */
    public void countAddedEntities() {
        if (addedEntities != null) {
            addedEntities.update(1);
        }
    }

    /**
     * Increments {@link #updatedEntities} stat by 1.
     */
    public void countUpdatedEntities() {
        if (updatedEntities != null) {
            updatedEntities.update(1);
        }
    }

    /**
     * Increments {@link #removedEntities} stat by 1.
     */
    public void countRemovedEntities() {
        if (removedEntities != null) {
            removedEntities.update(1);
        }
    }

    /**
     * Increments {@link #readEntities} stat by 1.
     */
    public void countReadEntities() {
        if (readEntities != null) {
            readEntities.update(1);
        }
    }

    /**
     * Updates {@link #nodeCacheSizeB} stat to the given value.
     *
     * @param value the value to set
     */
    public void setNodeCacheSize(final long value) {
        if (this.nodeCacheSizeB != null) {
            this.nodeCacheSizeB.set(value);
        }
    }

    /**
     * Updates {@link #pipelineSize} stat to the given value.
     *
     * @param value the value to set
     */
    public void setPipelineSize(final int value) {
        if (this.pipelineSize != null) {
            this.pipelineSize.set(value);
        }
    }

    /**
     * Updates {@link #flushBackpressureMs} stat.
     *
     * @param backpressureMs flush backpressure, ms
     */
    public void recordFlushBackpressureMs(final int backpressureMs) {
        if (flushBackpressureMs != null) {
            flushBackpressureMs.update(backpressureMs);
        }
    }

    /**
     * Updates {@link #familySizeBackpressureMs} stat.
     *
     * @param backpressureMs family size backpressure, ms
     */
    public void recordFamilySizeBackpressureMs(final int backpressureMs) {
        if (familySizeBackpressureMs != null) {
            familySizeBackpressureMs.update(backpressureMs);
        }
    }

    /**
     * Record a virtual root copy is merged, and merge duration is as specified.
     *
     * @param mergeDurationMs merge duration, ms
     */
    public void recordMerge(final long mergeDurationMs) {
        if (this.mergeDurationMs != null) {
            this.mergeDurationMs.update(mergeDurationMs);
        }
    }

    /**
     * Record a virtual root copy is flushed, and flush duration is as specified.
     *
     * @param flushDurationMs flush duration, ms
     */
    public void recordFlush(final long flushDurationMs) {
        if (this.flushCount != null) {
            this.flushCount.increment();
        }
        if (this.flushDurationMs != null) {
            this.flushDurationMs.update(flushDurationMs);
        }
    }

    /**
     * Record a virtual root copy is hashed, and hash duration is as specified.
     *
     * @param hashDurationMs flush duration, ms
     */
    public void recordHash(final long hashDurationMs) {
        if (this.hashDurationMs != null) {
            this.hashDurationMs.update(hashDurationMs);
        }
    }
}

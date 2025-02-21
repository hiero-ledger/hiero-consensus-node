// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.DoubleAccumulator;
import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Encapsulates statistics for an instance of a {@link MerkleDbDataSource}.
 */
public class MerkleDbStatistics {

    public static final String STAT_CATEGORY = "merkle_db";

    /** Prefix for all data source related metrics */
    private static final String DS_PREFIX = "ds_";
    /** Prefix for all files related metrics */
    private static final String FILES_PREFIX = "files_";
    /** Prefix for all metrics related to store read queries */
    private static final String READS_PREFIX = "reads_";
    /** Prefix for all metrics related to data flushing */
    private static final String FLUSHES_PREFIX = "flushes_";
    /** Prefix for compaction related metrics */
    private static final String COMPACTIONS_PREFIX = "compactions_";

    private static final String LEVEL_PREFIX = "level_";
    /** Prefix for all off-heap related metrics */
    private static final String OFFHEAP_PREFIX = "offheap_";

    private final MerkleDbConfig dbConfig;

    private final String label;

    /** Hashes - reads / s */
    private LongAccumulator hashReads;
    /** Leaves - reads / s */
    private LongAccumulator leafReads;
    /** Leaf keys - reads / s */
    private LongAccumulator leafKeyReads;

    /** Hashes store - file count */
    private IntegerGauge hashesStoreFileCount;
    /** Hashes store - total file size in Mb */
    private IntegerGauge hashesStoreFileSizeMb;
    /** Leaves store - file count */
    private IntegerGauge leavesStoreFileCount;
    /** Leaves store - total file size in Mb */
    private IntegerGauge leavesStoreFileSizeMb;
    /** Leaf keys store - file count */
    private IntegerGauge leafKeysStoreFileCount;
    /** Leaf keys store - total file size in Mb */
    private IntegerGauge leafKeysStoreFileSizeMb;
    /** Total file size in Mb */
    // Should all file sizes be doubles?
    private IntegerGauge totalFileSizeMb;

    private LongAccumulator flushHashesWritten;
    private DoubleAccumulator flushHashesStoreFileSizeMb;
    private LongAccumulator flushLeavesWritten;
    private LongAccumulator flushLeavesDeleted;
    private DoubleAccumulator flushLeavesStoreFileSizeMb;
    private LongAccumulator flushLeafKeysWritten;
    private DoubleAccumulator flushLeafKeysStoreFileSizeMb;

    /** Hashes store compactions - time in ms */
    private final List<LongAccumulator> hashesStoreCompactionTimeMsList;
    /** Hashes store compactions - saved space in Mb */
    private final List<DoubleAccumulator> hashesStoreCompactionSavedSpaceMbList;
    /** Hashes store - cumulative file size by compaction level in Mb */
    private final List<DoubleAccumulator> hashesStoreFileSizeByLevelMbList;
    /** Leaves store compactions - time in ms */
    private final List<LongAccumulator> leavesStoreCompactionTimeMsList;
    /** Leaves store compactions - saved space in Mb */
    private final List<DoubleAccumulator> leavesStoreCompactionSavedSpaceMbList;

    /** Leaves store - cumulative file size by compaction level in Mb */
    private final List<DoubleAccumulator> leavesStoreFileSizeByLevelMbList;
    /** Leaf keys store compactions - time in ms */
    private final List<LongAccumulator> leafKeysStoreCompactionTimeMsList;
    /** Leaf keys store compactions - saved space in Mb */
    private final List<DoubleAccumulator> leafKeysStoreCompactionSavedSpaceMbList;

    /** Leaf keys store - cumulative file size by compaction level in Mb */
    private final List<DoubleAccumulator> leafKeysStoreFileSizeByLevelMbList;
    /** Off-heap usage in MB of hashes store index */
    private IntegerGauge offHeapHashesIndexMb;
    /** Off-heap usage in MB of leaves store index */
    private IntegerGauge offHeapLeavesIndexMb;
    /** Off-heap usage in MB of object keys store bucket index */
    private IntegerGauge offHeapObjectKeyBucketsIndexMb;
    /** Off-heap usage in MB of hashes list in RAM */
    private IntegerGauge offHeapHashesListMb;
    /** Total data source off-heap usage in MB */
    private IntegerGauge offHeapDataSourceMb;

    /**
     * Create a new statistics object for a MerkleDb instances.
     *
     * @param dbConfig MerkleDb config
     * @param label the label for the virtual map
     * @throws NullPointerException in case {@code label} parameter is {@code null}
     */
    public MerkleDbStatistics(final MerkleDbConfig dbConfig, final String label) {
        this.dbConfig = dbConfig;
        Objects.requireNonNull(label, "label must not be null");
        // If the label contains ".", they are replaced with "_", since metric names may not contain "."
        this.label = label.replace('.', '_');
        hashesStoreCompactionTimeMsList = new ArrayList<>();
        hashesStoreCompactionSavedSpaceMbList = new ArrayList<>();
        hashesStoreFileSizeByLevelMbList = new ArrayList<>();
        leavesStoreCompactionTimeMsList = new ArrayList<>();
        leavesStoreCompactionSavedSpaceMbList = new ArrayList<>();
        leavesStoreFileSizeByLevelMbList = new ArrayList<>();
        leafKeysStoreCompactionTimeMsList = new ArrayList<>();
        leafKeysStoreCompactionSavedSpaceMbList = new ArrayList<>();
        leafKeysStoreFileSizeByLevelMbList = new ArrayList<>();
    }

    private static IntegerGauge buildIntegerGauge(final Metrics metrics, final String name, final String description) {
        return metrics.getOrCreate(new IntegerGauge.Config(STAT_CATEGORY, name).withDescription(description));
    }

    private static LongAccumulator buildLongAccumulator(
            final Metrics metrics, final String name, final String description) {
        return metrics.getOrCreate(new LongAccumulator.Config(STAT_CATEGORY, name)
                .withInitialValue(0)
                .withAccumulator(Long::sum)
                .withDescription(description));
    }

    private static DoubleAccumulator buildDoubleAccumulator(
            final Metrics metrics, final String name, final String description) {
        return metrics.getOrCreate(new DoubleAccumulator.Config(STAT_CATEGORY, name)
                .withInitialValue(0.0)
                .withAccumulator(Double::sum)
                .withDescription(description)
                .withFormat(FloatFormats.FORMAT_9_6));
    }

    /**
     * Register all statistics with a registry.
     *
     * @param metrics
     * 		reference to the metrics system
     * @throws NullPointerException if {@code metrics} is {@code null}
     */
    public void registerMetrics(final Metrics metrics) {
        Objects.requireNonNull(metrics, "metrics must not be null");

        // Queries per second
        hashReads = buildLongAccumulator(
                metrics, DS_PREFIX + READS_PREFIX + "hashes_" + label, "Number of hash reads, " + label);
        leafReads = buildLongAccumulator(
                metrics, DS_PREFIX + READS_PREFIX + "leaves_" + label, "Number of leaf reads, " + label);
        leafKeyReads = buildLongAccumulator(
                metrics, DS_PREFIX + READS_PREFIX + "leafKeys_" + label, "Number of leaf key reads, " + label);

        // File counts and sizes
        hashesStoreFileCount = metrics.getOrCreate(
                new IntegerGauge.Config(STAT_CATEGORY, DS_PREFIX + FILES_PREFIX + "hashesStoreFileCount_" + label)
                        .withDescription("File count, hashes store, " + label));
        hashesStoreFileSizeMb = buildIntegerGauge(
                metrics,
                DS_PREFIX + FILES_PREFIX + "hashesStoreFileSizeMb_" + label,
                "File size, hashes store, " + label + ", Mb");
        leavesStoreFileCount = metrics.getOrCreate(
                new IntegerGauge.Config(STAT_CATEGORY, DS_PREFIX + FILES_PREFIX + "leavesStoreFileCount_" + label)
                        .withDescription("File count, leaf keys store, " + label));
        leavesStoreFileSizeMb = buildIntegerGauge(
                metrics,
                DS_PREFIX + FILES_PREFIX + "leavesStoreFileSizeMb_" + label,
                "File size, leaf keys store, " + label + ", Mb");
        leafKeysStoreFileCount = metrics.getOrCreate(
                new IntegerGauge.Config(STAT_CATEGORY, DS_PREFIX + FILES_PREFIX + "leafKeysStoreFileCount_" + label)
                        .withDescription("File count, leaves store, " + label));
        leafKeysStoreFileSizeMb = buildIntegerGauge(
                metrics,
                DS_PREFIX + FILES_PREFIX + "leafKeysStoreFileSizeMb_" + label,
                "File size, leaves store, " + label + ", Mb");
        totalFileSizeMb = buildIntegerGauge(
                metrics,
                DS_PREFIX + FILES_PREFIX + "totalSizeMb_" + label,
                "Total file size, data source, " + label + ", Mb");

        // Flushes
        flushHashesWritten = buildLongAccumulator(
                metrics,
                DS_PREFIX + FLUSHES_PREFIX + "hashesWritten_" + label,
                "Number of hashes written during flush, " + label);
        flushHashesStoreFileSizeMb = buildDoubleAccumulator(
                metrics,
                DS_PREFIX + FLUSHES_PREFIX + "hashesStoreFileSizeMb_" + label,
                "Size of the new hashes store file created during flush, " + label + ", Mb");
        flushLeavesWritten = buildLongAccumulator(
                metrics,
                DS_PREFIX + FLUSHES_PREFIX + "leavesWritten_" + label,
                "Number of leaves written during flush, " + label);
        flushLeavesDeleted = buildLongAccumulator(
                metrics,
                DS_PREFIX + FLUSHES_PREFIX + "leavesDeleted_" + label,
                "Number of leaves deleted during flush, " + label);
        flushLeavesStoreFileSizeMb = buildDoubleAccumulator(
                metrics,
                DS_PREFIX + FLUSHES_PREFIX + "leavesStoreFileSizeMb_" + label,
                "Size of the new leaves store file created during flush, " + label + ", Mb");
        flushLeafKeysWritten = buildLongAccumulator(
                metrics,
                DS_PREFIX + FLUSHES_PREFIX + "leafKeysWritten_" + label,
                "Number of leaf keys written during flush, " + label);
        flushLeafKeysStoreFileSizeMb = buildDoubleAccumulator(
                metrics,
                DS_PREFIX + FLUSHES_PREFIX + "leafKeysStoreFileSizeMb_" + label,
                "Size of the new leaf keys store file created during flush, " + label + ", Mb");

        // Compaction

        for (int level = 0; level <= dbConfig.maxCompactionLevel(); level++) {
            // Hashes store
            hashesStoreCompactionTimeMsList.add(buildLongAccumulator(
                    metrics,
                    DS_PREFIX + COMPACTIONS_PREFIX + LEVEL_PREFIX + level + "_hashesTimeMs_" + label,
                    "Compactions time of level %s, hashes store, %s, ms".formatted(level, label)));
            hashesStoreCompactionSavedSpaceMbList.add(buildDoubleAccumulator(
                    metrics,
                    DS_PREFIX + COMPACTIONS_PREFIX + LEVEL_PREFIX + level + "_hashesSavedSpaceMb_" + label,
                    "Saved space during compactions of level %s, hashes store, %s, Mb".formatted(level, label)));
            hashesStoreFileSizeByLevelMbList.add(buildDoubleAccumulator(
                    metrics,
                    DS_PREFIX + FILES_PREFIX + LEVEL_PREFIX + level + "_hashesFileSizeByLevelMb_" + label,
                    "Total space taken by files of level %s, hashes store, %s, Mb".formatted(level, label)));

            // Leaves store
            leavesStoreCompactionTimeMsList.add(buildLongAccumulator(
                    metrics,
                    DS_PREFIX + COMPACTIONS_PREFIX + LEVEL_PREFIX + level + "_leavesTimeMs_" + label,
                    "Compactions time of level %s, leaves store, %s, ms".formatted(level, label)));
            leavesStoreCompactionSavedSpaceMbList.add(buildDoubleAccumulator(
                    metrics,
                    DS_PREFIX + COMPACTIONS_PREFIX + LEVEL_PREFIX + level + "_leavesSavedSpaceMb_" + label,
                    "Saved space during compactions of level %s, leaves store, %s, Mb".formatted(level, label)));
            leavesStoreFileSizeByLevelMbList.add(buildDoubleAccumulator(
                    metrics,
                    DS_PREFIX + FILES_PREFIX + LEVEL_PREFIX + level + "_leavesFileSizeByLevelMb_" + label,
                    "Total space taken by files of level %s, leaves store, %s, Mb".formatted(level, label)));

            // Leaf keys store
            leafKeysStoreCompactionTimeMsList.add(buildLongAccumulator(
                    metrics,
                    DS_PREFIX + COMPACTIONS_PREFIX + LEVEL_PREFIX + level + "_leafKeysTimeMs_" + label,
                    "Compactions time of level %s, leaf keys store, %s, ms".formatted(level, label)));
            leafKeysStoreCompactionSavedSpaceMbList.add(buildDoubleAccumulator(
                    metrics,
                    DS_PREFIX + COMPACTIONS_PREFIX + LEVEL_PREFIX + level + "_leafKeysSavedSpaceMb_" + label,
                    "Saved space during compactions of level %s, leaf keys store, %s, Mb".formatted(level, label)));
            leafKeysStoreFileSizeByLevelMbList.add(buildDoubleAccumulator(
                    metrics,
                    DS_PREFIX + FILES_PREFIX + LEVEL_PREFIX + level + "_leafKeysFileSizeByLevelMb_" + label,
                    "Total space taken by files of level %s, leaf keys store, %s, Mb".formatted(level, label)));
        }

        // Off-heap usage
        offHeapHashesIndexMb = metrics.getOrCreate(
                new IntegerGauge.Config(STAT_CATEGORY, DS_PREFIX + OFFHEAP_PREFIX + "hashesIndexMb_" + label)
                        .withDescription("Off-heap usage, hashes store index, " + label + ", Mb"));
        offHeapLeavesIndexMb = metrics.getOrCreate(
                new IntegerGauge.Config(STAT_CATEGORY, DS_PREFIX + OFFHEAP_PREFIX + "leavesIndexMb_" + label)
                        .withDescription("Off-heap usage, leaves store index, " + label + ", Mb"));
        offHeapObjectKeyBucketsIndexMb = metrics.getOrCreate(
                new IntegerGauge.Config(STAT_CATEGORY, DS_PREFIX + OFFHEAP_PREFIX + "objectKeyBucketsIndexMb_" + label)
                        .withDescription("Off-heap usage, object leaf key buckets store index, " + label + ", Mb"));
        offHeapHashesListMb = metrics.getOrCreate(
                new IntegerGauge.Config(STAT_CATEGORY, DS_PREFIX + OFFHEAP_PREFIX + "hashesListMb_" + label)
                        .withDescription("Off-heap usage, hashes list, " + label + ", Mb"));
        offHeapDataSourceMb = metrics.getOrCreate(
                new IntegerGauge.Config(STAT_CATEGORY, DS_PREFIX + OFFHEAP_PREFIX + "dataSourceMb_" + label)
                        .withDescription("Off-heap usage, data source, " + label + ", Mb"));
    }

    /**
     * Increments {@link #hashReads} stat by 1
     */
    public void countHashReads() {
        if (hashReads != null) {
            hashReads.update(1);
        }
    }

    /**
     * Increments {@link #leafReads} stat by 1
     */
    public void countLeafReads() {
        if (leafReads != null) {
            leafReads.update(1);
        }
    }

    /**
     * Increment {@link #leafKeyReads} stat by 1
     */
    public void countLeafKeyReads() {
        if (leafKeyReads != null) {
            leafKeyReads.update(1);
        }
    }

    /**
     * Set the current value for the {@link #hashesStoreFileCount} stat
     *
     * @param value
     * 		the value to set
     */
    public void setHashesStoreFileCount(final int value) {
        if (hashesStoreFileCount != null) {
            hashesStoreFileCount.set(value);
        }
    }

    /**
     * Set the current value for the {@link #hashesStoreFileSizeMb} stat
     *
     * @param value
     * 		the value to set
     */
    public void setHashesStoreFileSizeMb(final int value) {
        if (hashesStoreFileSizeMb != null) {
            hashesStoreFileSizeMb.set(value);
        }
    }

    /**
     * Set the current value for the {@link #leafKeysStoreFileCount} stat
     *
     * @param value
     * 		the value to set
     */
    public void setLeafKeysStoreFileCount(final int value) {
        if (leafKeysStoreFileCount != null) {
            leafKeysStoreFileCount.set(value);
        }
    }

    /**
     * Set the current value for the {@link #leafKeysStoreFileSizeMb} stat
     *
     * @param value
     * 		the value to set
     */
    public void setLeafKeysStoreFileSizeMb(final int value) {
        if (leafKeysStoreFileSizeMb != null) {
            leafKeysStoreFileSizeMb.set(value);
        }
    }

    /**
     * Set the current value for the {@link #leavesStoreFileCount} stat
     *
     * @param value
     * 		the value to set
     */
    public void setLeavesStoreFileCount(final int value) {
        if (leavesStoreFileCount != null) {
            leavesStoreFileCount.set(value);
        }
    }

    /**
     * Set the current value for the {@link #leavesStoreFileSizeMb} stat
     *
     * @param value
     * 		the value to set
     */
    public void setLeavesStoreFileSizeMb(final int value) {
        if (leavesStoreFileSizeMb != null) {
            leavesStoreFileSizeMb.set(value);
        }
    }

    /**
     * Set the current value for the {@link #totalFileSizeMb} stat
     *
     * @param value
     * 		the value to set
     */
    public void setTotalFileSizeMb(final int value) {
        if (totalFileSizeMb != null) {
            totalFileSizeMb.set(value);
        }
    }

    public void countFlushHashesWritten(final long value) {
        if (flushHashesWritten != null) {
            flushHashesWritten.update(value);
        }
    }

    public void setFlushHashesStoreFileSizeMb(final double value) {
        if (flushHashesStoreFileSizeMb != null) {
            flushHashesStoreFileSizeMb.update(value);
        }
    }

    public void countFlushLeavesWritten(final long value) {
        if (flushLeavesWritten != null) {
            flushLeavesWritten.update(value);
        }
    }

    public void countFlushLeavesDeleted(final long value) {
        if (flushLeavesDeleted != null) {
            flushLeavesDeleted.update(value);
        }
    }

    public void setFlushLeavesStoreFileSizeMb(final double value) {
        if (flushLeavesStoreFileSizeMb != null) {
            flushLeavesStoreFileSizeMb.update(value);
        }
    }

    public void countFlushLeafKeysWritten(final long value) {
        if (flushLeafKeysWritten != null) {
            flushLeafKeysWritten.update(value);
        }
    }

    public void setFlushLeafKeysStoreFileSizeMb(final double value) {
        if (flushLeafKeysStoreFileSizeMb != null) {
            flushLeafKeysStoreFileSizeMb.update(value);
        }
    }

    /**
     * Set the current value for the accumulator corresponding to provided compaction level from
     * {@link #hashesStoreCompactionTimeMsList}
     *
     * @param value the value to set
     */
    public void setHashesStoreCompactionTimeMs(final Integer compactionLevel, final long value) {
        assert compactionLevel >= 0 && compactionLevel <= dbConfig.maxCompactionLevel();
        if (hashesStoreCompactionTimeMsList.isEmpty()) {
            // if the method called before the metrics are registered, there is nothing to do
            return;
        }
        hashesStoreCompactionTimeMsList.get(compactionLevel).update(value);
    }

    /**
     * Set the current value for the accumulator corresponding to provided compaction level from
     * {@link #hashesStoreCompactionSavedSpaceMbList}
     *
     * @param value the value to set
     */
    public void setHashesStoreCompactionSavedSpaceMb(final int compactionLevel, final double value) {
        assert compactionLevel >= 0 && compactionLevel <= dbConfig.maxCompactionLevel();
        if (hashesStoreCompactionSavedSpaceMbList.isEmpty()) {
            // if the method called before the metrics are registered, there is nothing to do
            return;
        }
        hashesStoreCompactionSavedSpaceMbList.get(compactionLevel).update(value);
    }

    /**
     * Set the current value for the accumulator corresponding to provided compaction level from
     * {@link #hashesStoreFileSizeByLevelMbList}
     *
     * @param value the value to set
     */
    public void setHashesStoreFileSizeByLevelMb(final int compactionLevel, final double value) {
        assert compactionLevel >= 0 && compactionLevel <= dbConfig.maxCompactionLevel();
        if (hashesStoreFileSizeByLevelMbList.isEmpty()) {
            // if the method called before the metrics are registered, there is nothing to do
            return;
        }
        hashesStoreFileSizeByLevelMbList.get(compactionLevel).update(value);
    }

    /**
     * Set the current value for the accumulator corresponding to provided compaction level from
     * {@link #leavesStoreCompactionTimeMsList}
     *
     * @param value the value to set
     */
    public void setLeavesStoreCompactionTimeMs(final int compactionLevel, final long value) {
        assert compactionLevel >= 0 && compactionLevel <= dbConfig.maxCompactionLevel();
        if (leavesStoreCompactionTimeMsList.isEmpty()) {
            // if the method called before the metrics are registered, there is nothing to do
            return;
        }
        leavesStoreCompactionTimeMsList.get(compactionLevel).update(value);
    }

    /**
     * Set the current value for the accumulator corresponding to provided compaction level from
     * {@link #leavesStoreCompactionSavedSpaceMbList}
     * @param value the value to set
     */
    public void setLeavesStoreCompactionSavedSpaceMb(final int compactionLevel, final double value) {
        assert compactionLevel >= 0 && compactionLevel <= dbConfig.maxCompactionLevel();
        if (leavesStoreCompactionSavedSpaceMbList.isEmpty()) {
            // if the method called before the metrics are registered, there is nothing to do
            return;
        }
        leavesStoreCompactionSavedSpaceMbList.get(compactionLevel).update(value);
    }

    /**
     * Set the current value for the accumulator corresponding to provided compaction level from
     * {@link #leavesStoreFileSizeByLevelMbList}
     * @param value the value to set
     */
    public void setLeavesStoreFileSizeByLevelMb(final int compactionLevel, final double value) {
        assert compactionLevel >= 0 && compactionLevel <= dbConfig.maxCompactionLevel();
        if (leavesStoreFileSizeByLevelMbList.isEmpty()) {
            // if the method called before the metrics are registered, there is nothing to do
            return;
        }
        leavesStoreFileSizeByLevelMbList.get(compactionLevel).update(value);
    }

    /**
     * Set the current value for the accumulator corresponding to provided compaction level from
     * {@link #leafKeysStoreCompactionTimeMsList}
     *
     * @param value the value to set
     */
    public void setLeafKeysStoreCompactionTimeMs(final int compactionLevel, final long value) {
        assert compactionLevel >= 0 && compactionLevel <= dbConfig.maxCompactionLevel();
        if (leafKeysStoreCompactionTimeMsList.isEmpty()) {
            // if the method called before the metrics are registered, there is nothing to do
            return;
        }
        leafKeysStoreCompactionTimeMsList.get(compactionLevel).update(value);
    }

    /**
     * Set the current value for the accumulator corresponding to provided compaction level from
     * {@link #leafKeysStoreCompactionSavedSpaceMbList}
     *
     * @param value the value to set
     */
    public void setLeafKeysStoreCompactionSavedSpaceMb(final int compactionLevel, final double value) {
        assert compactionLevel >= 0 && compactionLevel <= dbConfig.maxCompactionLevel();
        if (leafKeysStoreCompactionSavedSpaceMbList.isEmpty()) {
            // if the method called before the metrics are registered, there is nothing to do
            return;
        }
        leafKeysStoreCompactionSavedSpaceMbList.get(compactionLevel).update(value);
    }

    /**
     * Set the current value for the accumulator corresponding to provided compaction level from
     * {@link #leafKeysStoreCompactionSavedSpaceMbList}
     *
     * @param value the value to set
     */
    public void setLeafKeysStoreFileSizeByLevelMb(final int compactionLevel, final double value) {
        assert compactionLevel >= 0 && compactionLevel <= dbConfig.maxCompactionLevel();
        if (leafKeysStoreFileSizeByLevelMbList.isEmpty()) {
            // if the method called before the metrics are registered, there is nothing to do
            return;
        }
        leafKeysStoreFileSizeByLevelMbList.get(compactionLevel).update(value);
    }

    /**
     * Set the current value for the {@link #offHeapLeavesIndexMb} stat
     *
     * @param value the value to set
     */
    public void setOffHeapLeavesIndexMb(final int value) {
        if (offHeapLeavesIndexMb != null) {
            offHeapLeavesIndexMb.set(value);
        }
    }

    /**
     * Set the current value for the {@link #offHeapHashesIndexMb} stat
     *
     * @param value the value to set
     */
    public void setOffHeapHashesIndexMb(final int value) {
        if (offHeapHashesIndexMb != null) {
            offHeapHashesIndexMb.set(value);
        }
    }

    /**
     * Set the current value for the {@link #offHeapObjectKeyBucketsIndexMb} stat
     *
     * @param value the value to set
     */
    public void setOffHeapObjectKeyBucketsIndexMb(final int value) {
        if (offHeapObjectKeyBucketsIndexMb != null) {
            offHeapObjectKeyBucketsIndexMb.set(value);
        }
    }

    /**
     * Set the current value for {@link #offHeapHashesListMb} stat
     *
     * @param value the value to set
     */
    public void setOffHeapHashesListMb(final int value) {
        if (offHeapHashesListMb != null) {
            offHeapHashesListMb.set(value);
        }
    }

    /**
     * Set the current value for the {@link #offHeapDataSourceMb} stat
     *
     * @param value the value to set
     */
    public void setOffHeapDataSourceMb(final int value) {
        if (offHeapDataSourceMb != null) {
            offHeapDataSourceMb.set(value);
        }
    }
}

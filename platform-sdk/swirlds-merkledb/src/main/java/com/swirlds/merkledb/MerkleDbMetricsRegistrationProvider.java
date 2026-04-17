// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import org.hiero.metrics.DoubleAccumulatorGauge;
import org.hiero.metrics.LongAccumulatorGauge;
import org.hiero.metrics.LongCounter;
import org.hiero.metrics.LongGauge;
import org.hiero.metrics.ObservableGauge;
import org.hiero.metrics.core.Metric;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricsRegistrationProvider;
import org.hiero.metrics.core.Unit;

public class MerkleDbMetricsRegistrationProvider implements MetricsRegistrationProvider {

    private static final String MAIN_CATEGORY = "merkle_db";
    /** Prefix for all files related metrics */
    private static final String FILES_CATEGORY = "files";
    /** Prefix for all metrics related to store read queries */
    private static final String READS_CATEGORY = "reads";
    /** Prefix for all metrics related to data flushing */
    private static final String FLUSHES_CATEGORY = "flushes";
    /** Prefix for compaction related metrics */
    private static final String COMPACTIONS_CATEGORY = "compactions";
    /** Prefix for all off-heap related metrics */
    private static final String OFFHEAP_CATEGORY = "offheap";

    public static final String LABEL_STORE_NAME = "store_name";
    public static final String LABEL_COMPACTION_LEVEL = "compaction_level";

    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_FILES_CNT =
            LongAccumulatorGauge.key("count").addCategory(FILES_CATEGORY).addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_FILES_SIZE =
            LongAccumulatorGauge.key("size").addCategory(FILES_CATEGORY).addCategory(MAIN_CATEGORY);
    public static final MetricKey<DoubleAccumulatorGauge> METRIC_KEY_FILES_GARBAGE_RATIO = DoubleAccumulatorGauge.key(
                    "garbage_ratio")
            .addCategory(FILES_CATEGORY)
            .addCategory(MAIN_CATEGORY);

    // Read metrics keys
    public static final MetricKey<LongCounter> METRIC_KEY_HASH_READS =
            LongCounter.key("hashes").addCategory(READS_CATEGORY).addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongCounter> METRIC_KEY_LEAF_READS =
            LongCounter.key("leaves").addCategory(READS_CATEGORY).addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongCounter> METRIC_KEY_LEAF_KEY_READS =
            LongCounter.key("leafKeys").addCategory(READS_CATEGORY).addCategory(MAIN_CATEGORY);

    // Files metrics keys
    public static final MetricKey<LongGauge> METRIC_KEY_HASHES_FILES_CNT =
            LongGauge.key("hashesStoreFileCount").addCategory(FILES_CATEGORY).addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongGauge> METRIC_KEY_HASHES_FILES_SIZE =
            LongGauge.key("hashesStoreFileSize").addCategory(FILES_CATEGORY).addCategory(MAIN_CATEGORY);

    public static final MetricKey<LongGauge> METRIC_KEY_LEAVES_FILES_CNT =
            LongGauge.key("leavesStoreFileCount").addCategory(FILES_CATEGORY).addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongGauge> METRIC_KEY_LEAVES_FILES_SIZE =
            LongGauge.key("leavesStoreFileSize").addCategory(FILES_CATEGORY).addCategory(MAIN_CATEGORY);

    public static final MetricKey<LongGauge> METRIC_KEY_LEAF_KEYS_FILES_CNT =
            LongGauge.key("leafKeysStoreFileCount").addCategory(FILES_CATEGORY).addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongGauge> METRIC_KEY_LEAF_KEYS_FILES_SIZE =
            LongGauge.key("leafKeysStoreFileSize").addCategory(FILES_CATEGORY).addCategory(MAIN_CATEGORY);

    public static final MetricKey<LongGauge> METRIC_KEY_TOTAL_FILES_SIZE =
            LongGauge.key("totalSize").addCategory(FILES_CATEGORY).addCategory(MAIN_CATEGORY);

    // Flush metrics keys
    public static final MetricKey<LongCounter> METRIC_KEY_FLUSH_HASHES_CNT =
            LongCounter.key("hashesWritten").addCategory(FLUSHES_CATEGORY).addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_FLUSH_HASHES_SIZE = LongAccumulatorGauge.key(
                    "hashesStoreFileSize")
            .addCategory(FLUSHES_CATEGORY)
            .addCategory(MAIN_CATEGORY);

    public static final MetricKey<LongCounter> METRIC_KEY_FLUSH_LEAVES_CNT =
            LongCounter.key("leavesWritten").addCategory(FLUSHES_CATEGORY).addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongCounter> METRIC_KEY_FLUSH_LEAVES_DELETED_CNT =
            LongCounter.key("leavesDeleted").addCategory(FLUSHES_CATEGORY).addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_FLUSH_LEAVES_SIZE = LongAccumulatorGauge.key(
                    "leavesStoreFileSize")
            .addCategory(FLUSHES_CATEGORY)
            .addCategory(MAIN_CATEGORY);

    public static final MetricKey<LongCounter> METRIC_KEY_FLUSH_LEAF_KEYS_CNT =
            LongCounter.key("leafKeysWritten").addCategory(FLUSHES_CATEGORY).addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_FLUSH_LEAF_KEYS_SIZE = LongAccumulatorGauge.key(
                    "leafKeysStoreFileSize")
            .addCategory(FLUSHES_CATEGORY)
            .addCategory(MAIN_CATEGORY);

    // Compaction metrics keys
    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_COMPACTION_TIME_HASHES = LongAccumulatorGauge.key(
                    "hashesTime")
            .addCategory(COMPACTIONS_CATEGORY)
            .addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_COMPACTION_SAVED_SPACE_HASHES =
            LongAccumulatorGauge.key("hashesSavedSpace")
                    .addCategory(COMPACTIONS_CATEGORY)
                    .addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_COMPACTION_FILE_SIZE_HASHES =
            LongAccumulatorGauge.key("hashesFileSize")
                    .addCategory(COMPACTIONS_CATEGORY)
                    .addCategory(MAIN_CATEGORY);

    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_COMPACTION_TIME_LEAVES = LongAccumulatorGauge.key(
                    "leavesTime")
            .addCategory(COMPACTIONS_CATEGORY)
            .addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_COMPACTION_SAVED_SPACE_LEAVES =
            LongAccumulatorGauge.key("leavesSavedSpace")
                    .addCategory(COMPACTIONS_CATEGORY)
                    .addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_COMPACTION_FILE_SIZE_LEAVES =
            LongAccumulatorGauge.key("leavesFileSize")
                    .addCategory(COMPACTIONS_CATEGORY)
                    .addCategory(MAIN_CATEGORY);

    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_COMPACTION_TIME_LEAF_KEYS = LongAccumulatorGauge.key(
                    "leafKeysTime")
            .addCategory(COMPACTIONS_CATEGORY)
            .addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_COMPACTION_SAVED_SPACE_LEAF_KEYS =
            LongAccumulatorGauge.key("leafKeysSavedSpace")
                    .addCategory(COMPACTIONS_CATEGORY)
                    .addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongAccumulatorGauge> METRIC_KEY_COMPACTION_FILE_SIZE_LEAF_KEYS =
            LongAccumulatorGauge.key("leafKeysFileSize")
                    .addCategory(COMPACTIONS_CATEGORY)
                    .addCategory(MAIN_CATEGORY);

    // Off-heap metrics keys
    public static final MetricKey<LongGauge> METRIC_KEY_OFFHEAP_HASHES_INDEX =
            LongGauge.key("hashesIndexSize").addCategory(OFFHEAP_CATEGORY).addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongGauge> METRIC_KEY_OFFHEAP_LEAVES_INDEX =
            LongGauge.key("leavesIndexSize").addCategory(OFFHEAP_CATEGORY).addCategory(MAIN_CATEGORY);
    public static final MetricKey<LongGauge> METRIC_KEY_OFFHEAP_OBJECT_KEY_BUCKETS_INDEX = LongGauge.key(
                    "objectKeyBucketsIndexSize")
            .addCategory(OFFHEAP_CATEGORY)
            .addCategory(MAIN_CATEGORY);

    @NonNull
    @Override
    public Collection<Metric.Builder<?, ?>> getMetricsToRegister() {
        ArrayList<Metric.Builder<?, ?>> builders = new ArrayList<>();

        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_FILES_CNT, Long::sum)
                .addDynamicLabelNames(LABEL_STORE_NAME, LABEL_COMPACTION_LEVEL)
                .setDescription("Number of files per store and compaction level"));
        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_FILES_SIZE, Long::sum)
                .setUnit(Unit.BYTE_UNIT)
                .addDynamicLabelNames(LABEL_STORE_NAME, LABEL_COMPACTION_LEVEL)
                .setDescription("Total size of files per store and compaction level, in bytes"));

        builders.add(DoubleAccumulatorGauge.maxBuilder(METRIC_KEY_FILES_GARBAGE_RATIO)
                .addDynamicLabelNames(LABEL_STORE_NAME, LABEL_COMPACTION_LEVEL)
                .resetOnExport()
                .setDescription("Maximum garbage ratio among files of the same store and compaction level"));

        /*// Read metrics
        builders.add(LongCounter.builder(METRIC_KEY_HASH_READS).setDescription("Number of hash reads"));
        builders.add(LongCounter.builder(METRIC_KEY_LEAF_READS).setDescription("Number of leaf reads"));
        builders.add(LongCounter.builder(METRIC_KEY_LEAF_KEY_READS).setDescription("Number of leaf key reads"));

        // Hashes store file metrics
        builders.add(LongGauge.builder(METRIC_KEY_HASHES_FILES_CNT).setDescription("Files count, hashes store"));
        builders.add(LongGauge.builder(METRIC_KEY_HASHES_FILES_SIZE)
                .setDescription("Files size in bytes, hashes store")
                .setUnit(Unit.BYTE_UNIT));

        // Leaves store file metrics
        builders.add(LongGauge.builder(METRIC_KEY_LEAVES_FILES_CNT).setDescription("Files count, leaves store"));
        builders.add(LongGauge.builder(METRIC_KEY_LEAVES_FILES_SIZE)
                .setDescription("Files size in bytes, leaves store")
                .setUnit(Unit.BYTE_UNIT));

        // Leaf keys store file metrics
        builders.add(LongGauge.builder(METRIC_KEY_LEAF_KEYS_FILES_CNT).setDescription("Files count, leaf keys store"));
        builders.add(LongGauge.builder(METRIC_KEY_LEAF_KEYS_FILES_SIZE)
                .setDescription("Files size in bytes, leaf keys")
                .setUnit(Unit.BYTE_UNIT));

        builders.add(LongGauge.builder(METRIC_KEY_TOTAL_FILES_SIZE)
                .setDescription("Total files size of datasource in bytes")
                .setUnit(Unit.BYTE_UNIT));

        // Flush hashes metrics
        builders.add(LongCounter.builder(METRIC_KEY_FLUSH_HASHES_CNT)
                .setDescription("Number of hashes written during flush"));
        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_FLUSH_HASHES_SIZE, Long::sum)
                .setDescription("Size of the new hashes store file created during flush in bytes")
                .setUnit(Unit.BYTE_UNIT));

        // Flush leaves metrics
        builders.add(LongCounter.builder(METRIC_KEY_FLUSH_LEAVES_CNT)
                .setDescription("Number of leaves written during flush"));
        builders.add(LongCounter.builder(METRIC_KEY_FLUSH_LEAVES_DELETED_CNT)
                .setDescription("Number of leaves deleted during flush"));
        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_FLUSH_LEAVES_SIZE, Long::sum)
                .setDescription("Size of the new leaves store file created during flush in bytes")
                .setUnit(Unit.BYTE_UNIT));

        // Flush leaf keys metrics
        builders.add(LongCounter.builder(METRIC_KEY_FLUSH_LEAF_KEYS_CNT)
                .setDescription("Number of leaf keys written during flush"));
        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_FLUSH_LEAF_KEYS_SIZE, Long::sum)
                .setDescription("Size of the new leaf keys store file created during flush in bytes")
                .setUnit(Unit.BYTE_UNIT));

        // Hash compaction metrics
        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_COMPACTION_TIME_HASHES, Long::sum)
                .setDescription("Time spent on hashes store compaction, ms")
                .addDynamicLabelNames(LABEL_COMPACTION_LEVEL)
                .setUnit(Unit.MILLISECOND_UNIT));
        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_COMPACTION_SAVED_SPACE_HASHES, Long::sum)
                .setDescription("Space saved during hashes store compaction in bytes")
                .addDynamicLabelNames(LABEL_COMPACTION_LEVEL)
                .setUnit(Unit.BYTE_UNIT));
        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_COMPACTION_FILE_SIZE_HASHES, Long::sum)
                .setDescription("Total space taken by hashes store in bytes")
                .addDynamicLabelNames(LABEL_COMPACTION_LEVEL)
                .setUnit(Unit.BYTE_UNIT));

        // Leaves compaction metrics
        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_COMPACTION_TIME_LEAVES, Long::sum)
                .setDescription("Time spent on leaves store compaction, ms")
                .addDynamicLabelNames(LABEL_COMPACTION_LEVEL)
                .setUnit(Unit.MILLISECOND_UNIT));
        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_COMPACTION_SAVED_SPACE_LEAVES, Long::sum)
                .setDescription("Space saved during leaves store compaction in bytes")
                .addDynamicLabelNames(LABEL_COMPACTION_LEVEL)
                .setUnit(Unit.BYTE_UNIT));
        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_COMPACTION_FILE_SIZE_LEAVES, Long::sum)
                .setDescription("Total space taken by leaves store in bytes")
                .addDynamicLabelNames(LABEL_COMPACTION_LEVEL)
                .setUnit(Unit.BYTE_UNIT));

        // Leaf keys compaction metrics
        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_COMPACTION_TIME_LEAF_KEYS, Long::sum)
                .setDescription("Time spent on leaf keys store compaction, ms")
                .addDynamicLabelNames(LABEL_COMPACTION_LEVEL)
                .setUnit(Unit.MILLISECOND_UNIT));
        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_COMPACTION_SAVED_SPACE_LEAF_KEYS, Long::sum)
                .setDescription("Space saved during leaf keys store compaction in bytes")
                .addDynamicLabelNames(LABEL_COMPACTION_LEVEL)
                .setUnit(Unit.BYTE_UNIT));
        builders.add(LongAccumulatorGauge.builder(METRIC_KEY_COMPACTION_FILE_SIZE_LEAF_KEYS, Long::sum)
                .setDescription("Total space taken by leaf keys store in bytes")
                .addDynamicLabelNames(LABEL_COMPACTION_LEVEL)
                .setUnit(Unit.BYTE_UNIT));

        // Off-heap metrics
        builders.add(LongGauge.builder(METRIC_KEY_OFFHEAP_HASHES_INDEX)
                .setDescription("Off-heap memory used by hashes store index, bytes")
                .setUnit(Unit.BYTE_UNIT));
        builders.add(LongGauge.builder(METRIC_KEY_OFFHEAP_LEAVES_INDEX)
                .setDescription("Off-heap memory used by leaves store index, bytes")
                .setUnit(Unit.BYTE_UNIT));
        builders.add(LongGauge.builder(METRIC_KEY_OFFHEAP_OBJECT_KEY_BUCKETS_INDEX)
                .setDescription("Off-heap memory used by object leaf key buckets store index, bytes")
                .setUnit(Unit.BYTE_UNIT));*/

        builders.add(
                ObservableGauge.builder(ObservableGauge.key("merkledb_count").addCategory(MAIN_CATEGORY))
                        .setDescription("The number of MerkleDb instances that have been created but not released")
                        .observe(MerkleDbDataSource::getCountOfOpenDatabases));

        return builders;
    }
}

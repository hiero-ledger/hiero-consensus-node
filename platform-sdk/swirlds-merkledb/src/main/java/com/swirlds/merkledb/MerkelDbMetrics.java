// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.LABEL_COMPACTION_LEVEL;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_COMPACTION_FILE_SIZE_HASHES;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_COMPACTION_FILE_SIZE_LEAF_KEYS;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_COMPACTION_FILE_SIZE_LEAVES;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_COMPACTION_SAVED_SPACE_HASHES;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_COMPACTION_SAVED_SPACE_LEAF_KEYS;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_COMPACTION_SAVED_SPACE_LEAVES;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_COMPACTION_TIME_HASHES;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_COMPACTION_TIME_LEAF_KEYS;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_COMPACTION_TIME_LEAVES;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_FLUSH_HASHES_CNT;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_FLUSH_HASHES_SIZE;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_FLUSH_LEAF_KEYS_CNT;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_FLUSH_LEAF_KEYS_SIZE;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_FLUSH_LEAVES_CNT;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_FLUSH_LEAVES_DELETED_CNT;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_FLUSH_LEAVES_SIZE;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_HASHES_FILES_CNT;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_HASHES_FILES_SIZE;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_HASH_READS;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_LEAF_KEYS_FILES_CNT;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_LEAF_KEYS_FILES_SIZE;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_LEAF_KEY_READS;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_LEAF_READS;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_LEAVES_FILES_CNT;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_LEAVES_FILES_SIZE;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_OFFHEAP_HASHES_INDEX;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_OFFHEAP_LEAVES_INDEX;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_OFFHEAP_OBJECT_KEY_BUCKETS_INDEX;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_TOTAL_FILES_SIZE;

import com.swirlds.merkledb.collections.OffHeapUser;
import com.swirlds.merkledb.files.DataFileReader;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LongSummaryStatistics;
import java.util.function.LongConsumer;
import org.hiero.metrics.LongAccumulatorGauge;
import org.hiero.metrics.LongCounter;
import org.hiero.metrics.LongGauge;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricRegistry;

public class MerkelDbMetrics {

    private final LongCounter.Measurement countHashReads;
    private final LongCounter.Measurement countLeavesReads;
    private final LongCounter.Measurement countLeafKeysReads;

    private final LongCounter.Measurement flushHashesWritten;
    private final LongAccumulatorGauge.Measurement flushHashStoreFileSize;

    private final LongCounter.Measurement flushLeavesWritten;
    private final LongCounter.Measurement flushLeavesDeleted;
    private final LongAccumulatorGauge.Measurement flushLeavesStoreFileSize;

    private final LongCounter.Measurement flushLeafKeysWritten;
    private final LongAccumulatorGauge.Measurement flushLeafKeysStoreFileSize;

    private final CompactionStat hashesCompactionStat;
    private final CompactionStat leavesCompactionStat;
    private final CompactionStat leafKeysCompactionStat;

    private final StoreFileStat storeFileStat;

    public MerkelDbMetrics(@NonNull MetricRegistry registry) {
        countHashReads = registry.getMetric(METRIC_KEY_HASH_READS).getOrCreateNotLabeled();
        countLeavesReads = registry.getMetric(METRIC_KEY_LEAF_READS).getOrCreateNotLabeled();
        countLeafKeysReads = registry.getMetric(METRIC_KEY_LEAF_KEY_READS).getOrCreateNotLabeled();

        flushHashesWritten = registry.getMetric(METRIC_KEY_FLUSH_HASHES_CNT).getOrCreateNotLabeled();
        flushHashStoreFileSize =
                registry.getMetric(METRIC_KEY_FLUSH_HASHES_SIZE).getOrCreateNotLabeled();

        flushLeavesWritten = registry.getMetric(METRIC_KEY_FLUSH_LEAVES_CNT).getOrCreateNotLabeled();
        flushLeavesDeleted =
                registry.getMetric(METRIC_KEY_FLUSH_LEAVES_DELETED_CNT).getOrCreateNotLabeled();
        flushLeavesStoreFileSize =
                registry.getMetric(METRIC_KEY_FLUSH_LEAVES_SIZE).getOrCreateNotLabeled();

        flushLeafKeysWritten =
                registry.getMetric(METRIC_KEY_FLUSH_LEAF_KEYS_CNT).getOrCreateNotLabeled();
        flushLeafKeysStoreFileSize =
                registry.getMetric(METRIC_KEY_FLUSH_LEAF_KEYS_SIZE).getOrCreateNotLabeled();

        hashesCompactionStat = new CompactionStat(
                registry,
                METRIC_KEY_COMPACTION_TIME_HASHES,
                METRIC_KEY_COMPACTION_SAVED_SPACE_HASHES,
                METRIC_KEY_COMPACTION_FILE_SIZE_HASHES);

        leavesCompactionStat = new CompactionStat(
                registry,
                METRIC_KEY_COMPACTION_TIME_LEAVES,
                METRIC_KEY_COMPACTION_SAVED_SPACE_LEAVES,
                METRIC_KEY_COMPACTION_FILE_SIZE_LEAVES);

        leafKeysCompactionStat = new CompactionStat(
                registry,
                METRIC_KEY_COMPACTION_TIME_LEAF_KEYS,
                METRIC_KEY_COMPACTION_SAVED_SPACE_LEAF_KEYS,
                METRIC_KEY_COMPACTION_FILE_SIZE_LEAF_KEYS);

        storeFileStat = new StoreFileStat(registry);
    }

    public StoreFileStat getStoreFileStat() {
        return storeFileStat;
    }

    public CompactionStat getHashesCompactionStat() {
        return hashesCompactionStat;
    }

    public CompactionStat getLeavesCompactionStat() {
        return leavesCompactionStat;
    }

    public CompactionStat getLeafKeysCompactionStat() {
        return leafKeysCompactionStat;
    }

    /** Updates statistics with leaf keys store file size. */
    void setFlushLeafKeysStoreFileSize(final DataFileReader newLeafKeysFile) {
        if (newLeafKeysFile != null) {
            flushLeafKeysStoreFileSize.accumulate(newLeafKeysFile.getSize());
        }
    }

    /** Updates statistics with leaf store file size. */
    void setFlushLeavesStoreFileSize(final DataFileReader newLeavesFile) {
        if (newLeavesFile != null) {
            flushLeavesStoreFileSize.accumulate(newLeavesFile.getSize());
        }
    }

    /** Updates statistics with hashes store file size. */
    void setFlushHashesStoreFileSize(final DataFileReader newHashesFile) {
        if (newHashesFile != null) {
            flushHashStoreFileSize.accumulate(newHashesFile.getSize());
        }
    }

    /** Updates statistics with number of leaf reads. */
    void countLeafReads() {
        countLeavesReads.increment();
    }

    /** Updates statistics with number of leaf key reads. */
    void countLeafKeyReads() {
        countLeafKeysReads.increment();
    }

    /** Updates statistics with number of hash reads. */
    void countHashReads() {
        countHashReads.increment();
    }

    /** Increments count of leaves written during a flush*/
    void countFlushLeavesWritten(long count) {
        flushLeavesWritten.increment(count);
    }

    /** Increments count of leaf keys written during a flush*/
    void countFlushLeafKeysWritten(long count) {
        flushLeafKeysWritten.increment(count);
    }

    /** Increments count of leaves deleted during a flush*/
    void countFlushLeavesDeleted(long count) {
        flushLeavesDeleted.increment(count);
    }

    /** Increments count of hashes written during a flush*/
    void countFlushHashesWritten() {
        flushHashesWritten.increment();
    }

    public static class CompactionStat {

        private final LongAccumulatorGauge duration;
        private final LongAccumulatorGauge savedSpace;
        private final LongAccumulatorGauge fileSize;

        public CompactionStat(
                MetricRegistry registry,
                MetricKey<LongAccumulatorGauge> durationKey,
                MetricKey<LongAccumulatorGauge> savedSpaceKey,
                MetricKey<LongAccumulatorGauge> fileSizeKey) {
            duration = registry.getMetric(durationKey);
            savedSpace = registry.getMetric(savedSpaceKey);
            fileSize = registry.getMetric(fileSizeKey); // TODO should not be part of compaction stats
        }

        public void setDuration(int compactionLevel, long timeMs) {
            duration.getOrCreateLabeled(LABEL_COMPACTION_LEVEL, String.valueOf(compactionLevel))
                    .accumulate(timeMs);
        }

        public void setSavedSpace(int compactionLevel, long savedSpaceBytes) {
            savedSpace
                    .getOrCreateLabeled(LABEL_COMPACTION_LEVEL, String.valueOf(compactionLevel))
                    .accumulate(savedSpaceBytes);
        }

        public void setFileSize(int compactionLevel, long fileSizeBytes) {
            fileSize.getOrCreateLabeled(LABEL_COMPACTION_LEVEL, String.valueOf(compactionLevel))
                    .accumulate(fileSizeBytes);
        }
    }

    public static class StoreFileStat {

        private final LongGauge.Measurement hashesStoreFilesCount;
        private final LongGauge.Measurement hashesStoreFilesSize;

        private final LongGauge.Measurement leavesStoreFilesCount;
        private final LongGauge.Measurement leavesStoreFilesSize;

        private final LongGauge.Measurement leafKeysStoreFilesCount;
        private final LongGauge.Measurement leafKeysFilesSize;

        private final LongGauge.Measurement storeFilesTotalSize;

        private final LongGauge.Measurement offHeapHashesIndexSize;
        private final LongGauge.Measurement offHeapLeavesIndexSize;
        private final LongGauge.Measurement offHeapObjectKeyBucketIndexSize;

        public StoreFileStat(MetricRegistry registry) {
            hashesStoreFilesCount =
                    registry.getMetric(METRIC_KEY_HASHES_FILES_CNT).getOrCreateNotLabeled();
            hashesStoreFilesSize =
                    registry.getMetric(METRIC_KEY_HASHES_FILES_SIZE).getOrCreateNotLabeled();

            leavesStoreFilesCount =
                    registry.getMetric(METRIC_KEY_LEAVES_FILES_CNT).getOrCreateNotLabeled();
            leavesStoreFilesSize =
                    registry.getMetric(METRIC_KEY_LEAVES_FILES_SIZE).getOrCreateNotLabeled();

            leafKeysStoreFilesCount =
                    registry.getMetric(METRIC_KEY_LEAF_KEYS_FILES_CNT).getOrCreateNotLabeled();
            leafKeysFilesSize =
                    registry.getMetric(METRIC_KEY_LEAF_KEYS_FILES_SIZE).getOrCreateNotLabeled();

            storeFilesTotalSize =
                    registry.getMetric(METRIC_KEY_TOTAL_FILES_SIZE).getOrCreateNotLabeled();

            offHeapHashesIndexSize =
                    registry.getMetric(METRIC_KEY_OFFHEAP_HASHES_INDEX).getOrCreateNotLabeled();
            offHeapLeavesIndexSize =
                    registry.getMetric(METRIC_KEY_OFFHEAP_LEAVES_INDEX).getOrCreateNotLabeled();
            offHeapObjectKeyBucketIndexSize = registry.getMetric(METRIC_KEY_OFFHEAP_OBJECT_KEY_BUCKETS_INDEX)
                    .getOrCreateNotLabeled();
        }

        public void updateStats(final MerkleDbDataSource dataSource) {
            updateStoreFileStats(dataSource);
            updateOffHeapStats(dataSource);
        }

        /** Calculate updates statistics for all the storages and then updates total usage */
        private void updateStoreFileStats(final MerkleDbDataSource dataSource) {
            storeFilesTotalSize.set(updateHashesStoreFileStats(dataSource)
                    + updateLeavesStoreFileStats(dataSource)
                    + updateLeafKeysStoreFileStats(dataSource));
        }

        /**
         * Updates statistics with off-heap memory consumption.
         */
        private void updateOffHeapStats(final MerkleDbDataSource dataSource) {
            updateOffHeapStat(dataSource.getIdToDiskLocationHashChunks(), offHeapHashesIndexSize::set);
            updateOffHeapStat(dataSource.getPathToDiskLocationLeafNodes(), offHeapLeavesIndexSize::set);
            // TODO replace with index or make all using store
            updateOffHeapStat(dataSource.getKeyToPath(), offHeapObjectKeyBucketIndexSize::set);
        }

        /**
         * Updates hashes store file stats: file count and total size in bytes. No-op if all hashes
         * are cached in RAM.
         */
        private long updateHashesStoreFileStats(final MerkleDbDataSource dataSource) {
            final LongSummaryStatistics hashFileStats =
                    dataSource.getHashChunkStore().getFilesSizeStatistics();
            hashesStoreFilesCount.set(hashFileStats.getCount());
            hashesStoreFilesSize.set(hashFileStats.getSum());
            return hashFileStats.getSum();
        }

        /**
         * Updates leaves store file stats: file count and total size in bytes.
         */
        private long updateLeavesStoreFileStats(final MerkleDbDataSource dataSource) {
            final LongSummaryStatistics leavesDataFileStats =
                    dataSource.getKeyValueStore().getFilesSizeStatistics();
            leavesStoreFilesCount.set(leavesDataFileStats.getCount());
            leavesStoreFilesSize.set(leavesDataFileStats.getSum());
            return leavesDataFileStats.getSum();
        }

        /**
         * Updates leaf keys store file stats: file count and total size in bytes. No-op if keys are
         * longs and stored in a LongList rather than in a store on disk.
         *
         * @return leaf keys store file size, Mb
         */
        private long updateLeafKeysStoreFileStats(final MerkleDbDataSource dataSource) {
            final LongSummaryStatistics leafKeysDataFileStats =
                    dataSource.getKeyToPath().getFilesSizeStatistics();
            leafKeysStoreFilesCount.set(leafKeysDataFileStats.getCount());
            leafKeysFilesSize.set(leafKeysDataFileStats.getSum());
            return leafKeysDataFileStats.getSum();
        }

        private static void updateOffHeapStat(final Object obj, final LongConsumer updateFunction) {
            if (obj instanceof OffHeapUser offHeapUser) {
                updateFunction.accept(offHeapUser.getOffHeapConsumption());
            }
        }
    }
}

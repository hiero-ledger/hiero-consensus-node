// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.base.units.UnitConstants.BYTES_TO_MEBIBYTES;

import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.OffHeapUser;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LongSummaryStatistics;
import java.util.function.IntConsumer;

/**
 * This class is responsible for updating statistics for a MerkleDb instance.
 */
public class MerkleDbStatisticsUpdater {

    private static final FunctionGauge.Config<Long> COUNT_OF_OPEN_DATABASES_CONFIG = new FunctionGauge.Config<>(
                    MerkleDbStatistics.STAT_CATEGORY,
                    "merkledb_count",
                    Long.class,
                    MerkleDbDataSource::getCountOfOpenDatabases)
            .withDescription("the number of MerkleDb instances that have been created but not" + " released")
            .withFormat("%d");

    private final MerkleDbStatistics statistics;

    public MerkleDbStatisticsUpdater(@NonNull final MerkleDbConfig config, @NonNull final String tableName) {
        statistics = new MerkleDbStatistics(config, tableName);
    }

    /**
     * Register metrics for this instance.
     * @param metrics the metrics to register
     */
    public void registerMetrics(final Metrics metrics) {
        // register static/global statistics
        metrics.getOrCreate(COUNT_OF_OPEN_DATABASES_CONFIG);

        // register instance statistics
        statistics.registerMetrics(metrics);
    }

    /** Updates statistics with leaf keys store file size. */
    void setFlushLeafKeysStoreFileSize(final DataFileReader newLeafKeysFile) {
        statistics.setFlushLeafKeysStoreFileSizeMb(
                newLeafKeysFile == null ? 0 : newLeafKeysFile.getSize() * BYTES_TO_MEBIBYTES);
    }

    /** Updates statistics with leaf store file size. */
    void setFlushLeavesStoreFileSize(final DataFileReader newLeafKeysFile) {
        statistics.setFlushLeavesStoreFileSizeMb(
                newLeafKeysFile == null ? 0 : newLeafKeysFile.getSize() * BYTES_TO_MEBIBYTES);
    }

    /** Updates statistics with hashes store file size. */
    void setFlushHashesStoreFileSize(final DataFileReader newHashesFile) {
        statistics.setFlushHashesStoreFileSizeMb(
                newHashesFile == null ? 0 : newHashesFile.getSize() * BYTES_TO_MEBIBYTES);
    }

    /**
     * Updates hashes store file stats: file count and total size in Mb. No-op if all hashes
     * are cached in RAM.
     *
     * @return hashes store file size, Mb
     */
    int updateHashesStoreFileStats(final MerkleDbDataSource dataSource) {
        if (dataSource.getHashStoreDisk() != null) {
            final LongSummaryStatistics internalHashesFileSizeStats =
                    dataSource.getHashStoreDisk().getFilesSizeStatistics();
            statistics.setHashesStoreFileCount((int) internalHashesFileSizeStats.getCount());
            final int fileSizeInMb = (int) (internalHashesFileSizeStats.getSum() * BYTES_TO_MEBIBYTES);
            statistics.setHashesStoreFileSizeMb(fileSizeInMb);
            return fileSizeInMb;
        }
        return 0;
    }

    /**
     * Updates leaves store file stats: file count and total size in Mb.
     *
     * @return leaves store file size, Mb
     */
    private int updateLeavesStoreFileStats(final MerkleDbDataSource dataSource) {
        final LongSummaryStatistics leafDataFileSizeStats =
                dataSource.getPathToKeyValue().getFilesSizeStatistics();
        statistics.setLeavesStoreFileCount((int) leafDataFileSizeStats.getCount());
        final int fileSizeInMb = (int) (leafDataFileSizeStats.getSum() * BYTES_TO_MEBIBYTES);
        statistics.setLeavesStoreFileSizeMb(fileSizeInMb);
        return fileSizeInMb;
    }

    /**
     * Updates leaf keys store file stats: file count and total size in Mb. No-op if keys are
     * longs and stored in a LongList rather than in a store on disk.
     *
     * @return leaf keys store file size, Mb
     */
    private int updateLeafKeysStoreFileStats(final MerkleDbDataSource dataSource) {
        if (dataSource.getKeyToPath() != null) {
            final LongSummaryStatistics leafKeyFileSizeStats =
                    dataSource.getKeyToPath().getFilesSizeStatistics();
            statistics.setLeafKeysStoreFileCount((int) leafKeyFileSizeStats.getCount());
            final int fileSizeInMb = (int) (leafKeyFileSizeStats.getSum() * BYTES_TO_MEBIBYTES);
            statistics.setLeafKeysStoreFileSizeMb(fileSizeInMb);
            return fileSizeInMb;
        }
        return 0;
    }

    /** Calculate updates statistics for all the storages and then updates total usage */
    void updateStoreFileStats(final MerkleDbDataSource dataSource) {
        statistics.setTotalFileSizeMb(updateHashesStoreFileStats(dataSource)
                + updateLeavesStoreFileStats(dataSource)
                + updateLeafKeysStoreFileStats(dataSource));
    }

    /**
     * Updates statistics with off-heap memory consumption.
     */
    void updateOffHeapStats(final MerkleDbDataSource dataSource) {
        int totalOffHeapMemoryConsumption = updateOffHeapStat(
                        dataSource.getPathToDiskLocationInternalNodes(), statistics::setOffHeapHashesIndexMb)
                + updateOffHeapStat(dataSource.getPathToDiskLocationLeafNodes(), statistics::setOffHeapLeavesIndexMb);
        if (dataSource.getKeyToPath() != null) {
            totalOffHeapMemoryConsumption += updateOffHeapStat(
                    (OffHeapUser) dataSource.getKeyToPath(), statistics::setOffHeapObjectKeyBucketsIndexMb);
        }
        if (dataSource.getHashStoreRam() != null) {
            totalOffHeapMemoryConsumption +=
                    updateOffHeapStat((OffHeapUser) dataSource.getHashStoreRam(), statistics::setOffHeapHashesListMb);
        }
        statistics.setOffHeapDataSourceMb(totalOffHeapMemoryConsumption);
    }

    /** Updates statistics with number of leaf reads. */
    void countLeafReads() {
        statistics.countLeafReads();
    }

    /** Updates statistics with number of leaf key reads. */
    void countLeafKeyReads() {
        statistics.countLeafKeyReads();
    }

    /** Updates statistics with number of hash reads. */
    void countHashReads() {
        statistics.countHashReads();
    }

    /** Increments count of leaves written during a flush*/
    void countFlushLeavesWritten() {
        statistics.countFlushLeavesWritten(1);
    }

    /** Increments count of leaf keys written during a flush*/
    void countFlushLeafKeysWritten() {
        statistics.countFlushLeafKeysWritten(1);
    }

    /** Increments count of leaves deleted during a flush*/
    void countFlushLeavesDeleted() {
        statistics.countFlushLeavesDeleted(1);
    }

    /** Increments count of hashes written during a flush*/
    void countFlushHashesWritten() {
        statistics.countFlushHashesWritten(1);
    }

    private static int updateOffHeapStat(final LongList longList, final IntConsumer updateFunction) {
        if (longList instanceof OffHeapUser longListOffHeap) {
            final int result = (int) (longListOffHeap.getOffHeapConsumption() * BYTES_TO_MEBIBYTES);
            updateFunction.accept(result);
            return result;
        } else {
            return 0;
        }
    }

    private static int updateOffHeapStat(final OffHeapUser offHeapUser, final IntConsumer updateFunction) {
        final int usage = (int) (offHeapUser.getOffHeapConsumption() * BYTES_TO_MEBIBYTES);
        updateFunction.accept(usage);
        return usage;
    }

    void setLeafKeysStoreCompactionTimeMs(Integer compactionLevel, Long time) {
        statistics.setLeafKeysStoreCompactionTimeMs(compactionLevel, time);
    }

    void setLeafKeysStoreCompactionSavedSpaceMb(Integer compactionLevel, Double savedSpace) {
        statistics.setLeafKeysStoreCompactionSavedSpaceMb(compactionLevel, savedSpace);
    }

    void setLeafKeysStoreFileSizeByLevelMb(Integer compactionLevel, Double savedSpace) {
        statistics.setLeafKeysStoreFileSizeByLevelMb(compactionLevel, savedSpace);
    }

    void setHashesStoreCompactionTimeMs(Integer compactionLevel, Long time) {
        statistics.setHashesStoreCompactionTimeMs(compactionLevel, time);
    }

    void setHashesStoreCompactionSavedSpaceMb(Integer compactionLevel, Double savedSpace) {
        statistics.setHashesStoreCompactionSavedSpaceMb(compactionLevel, savedSpace);
    }

    void setHashesStoreFileSizeByLevelMb(Integer compactionLevel, Double savedSpace) {
        statistics.setHashesStoreFileSizeByLevelMb(compactionLevel, savedSpace);
    }

    void setLeavesStoreCompactionTimeMs(Integer compactionType, Long time) {
        statistics.setLeavesStoreCompactionTimeMs(compactionType, time);
    }

    void setLeavesStoreCompactionSavedSpaceMb(Integer compactionType, Double savedSpace) {
        statistics.setLeavesStoreCompactionSavedSpaceMb(compactionType, savedSpace);
    }

    void setLeavesStoreFileSizeByLevelMb(Integer compactionType, Double savedSpace) {
        statistics.setLeavesStoreFileSizeByLevelMb(compactionType, savedSpace);
    }
}

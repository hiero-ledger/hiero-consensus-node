// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.base.units.UnitConstants.KIBIBYTES_TO_BYTES;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;

import com.swirlds.merkledb.collections.CASableLongIndex;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Traverses the in-memory index for a file collection and computes per-file garbage statistics.
 *
 * <p>The scanner counts the number of index entries that point to each data file and compares
 * this to the total number of items recorded in the file's metadata. The ratio of dead items
 * to total items is the garbage ratio.
 *
 * <p>For {@link MemoryIndexDiskKeyValueStore}-backed stores (IdToHashChunk, PathToKeyValue),
 * each index entry corresponds to a single data item, so the garbage ratio reflects individual
 * item liveness. For the {@link HalfDiskHashMap}-backed store (ObjectKeyToPath), each index
 * entry corresponds to a bucket that may contain multiple keys. The garbage ratio is therefore
 * computed at bucket granularity: a bucket is "alive" if the index still points to it, and
 * "dead" otherwise. This may slightly underestimate garbage, since an alive bucket can
 * internally contain stale key entries that have migrated to other buckets. Underestimating
 * garbage is a safe direction for compaction decisions.
 */
public class GarbageScanner {

    private static final Logger logger = LogManager.getLogger(GarbageScanner.class);

    private final CASableLongIndex index;
    private final DataFileCollection dataFileCollection;
    private final String storeName;
    private final double garbageThreshold;
    private final long maxCompactionDataPerLevelInKB;
    private final int maxCompactionLevel;

    public GarbageScanner(
            final CASableLongIndex index,
            final DataFileCollection dataFileCollection,
            final String storeName,
            final MerkleDbConfig config) {
        this.index = index;
        this.dataFileCollection = dataFileCollection;
        this.storeName = storeName;
        this.garbageThreshold = config.garbageThreshold();
        this.maxCompactionDataPerLevelInKB = config.maxCompactionDataPerLevelInKB();
        this.maxCompactionLevel = config.maxCompactionLevel();
    }

    /**
     * Traverse the index and produce per-file stats with total and alive item counts.
     *
     * <p>This method is intended to be called from a single background thread. It is read-only
     * with respect to both the index and the data files — no data is copied or modified.
     *
     * @return a map from level to a list of files that should be compacted at that level
     */
    public Map<Integer, List<DataFileReader>> scan() {
        final long start = System.currentTimeMillis();

        final Map<Integer, GarbageFileStats> statsByFileIndex = createStatsByFileIndexMap();
        try {
            index.forEach(
                    (key, dataLocation) -> {
                        final int fileIndex = DataFileCommon.fileIndexFromDataLocation(dataLocation);
                        final GarbageFileStats fileStats = statsByFileIndex.get(fileIndex);
                        assert fileStats != null;
                        fileStats.incrementAliveItems();
                    },
                    null);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn(MERKLE_DB.getMarker(), "[{}] Garbage scan was interrupted", storeName);
            return Map.of();
        }

        final Map<Integer, List<DataFileReader>> result = new HashMap<>();
        final long[] sizeByLevel = new long[maxCompactionLevel + 1];
        for (GarbageFileStats value : statsByFileIndex.values()) {
            if (isToBeCompacted(value, sizeByLevel)) {
                result.computeIfAbsent(value.compactionLevel(), level -> new ArrayList<>())
                        .add(value.fileReader);
            }
            sizeByLevel[value.compactionLevel()] += value.fileReader.getSize();
        }

        logLevelStats(statsByFileIndex);

        final long tookMillis = System.currentTimeMillis() - start;
        logger.info(MERKLE_DB.getMarker(), "[{}] Garbage scan finished in {} ms", storeName, tookMillis);

        return result;
    }

    private boolean isToBeCompacted(GarbageFileStats value, long[] sizeByLevel) {
        return (maxCompactionDataPerLevelInKB <= 0
                        || sizeByLevel[value.compactionLevel()] < maxCompactionDataPerLevelInKB * KIBIBYTES_TO_BYTES)
                && value.garbageRatio() > garbageThreshold;
    }

    private void logLevelStats(Map<Integer, GarbageFileStats> statsByFileIndex) {
        final Map<Integer, long[]> totalsByLevel = new TreeMap<>();
        for (final GarbageFileStats stats : statsByFileIndex.values()) {
            final long[] levelTotals = totalsByLevel.computeIfAbsent(stats.compactionLevel(), level -> new long[3]);
            levelTotals[0]++;
            levelTotals[1] += stats.totalItems();
            levelTotals[2] += stats.aliveItems();
        }

        totalsByLevel.forEach((level, totals) -> {
            final long levelFilesCount = totals[0];
            final long levelTotalItems = totals[1];
            final long levelAliveItems = totals[2];
            final double levelGarbageRatio =
                    levelTotalItems == 0 ? 1.0 : 1.0 - ((double) levelAliveItems / levelTotalItems);

            logger.info(
                    MERKLE_DB.getMarker(),
                    "[{}] Garbage scan level {}: files={}, totalItems={}, aliveItems={}, garbageRatio={}",
                    storeName,
                    level,
                    levelFilesCount,
                    levelTotalItems,
                    levelAliveItems,
                    levelGarbageRatio);
        });
    }

    private Map<Integer, GarbageFileStats> createStatsByFileIndexMap() {
        return dataFileCollection.getAllCompletedFiles().stream()
                .collect(Collectors.toMap(DataFileReader::getIndex, GarbageFileStats::new));
    }

    /**
     * Per-file garbage statistics. Tracks total items (from file metadata) and alive items
     * (counted during index traversal). Not thread-safe — intended for use within a single
     * scanner thread.
     */
    public static final class GarbageFileStats {

        private final DataFileReader fileReader;
        private long aliveItems;

        /**
         * Creates stats with zero alive items. Used by the scanner during index traversal;
         * alive items are incremented as entries are encountered.
         */
        public GarbageFileStats(final DataFileReader fileReader) {
            this(fileReader, 0);
        }

        /**
         * Creates stats with a known alive item count. Useful for testing and for constructing
         * stats from pre-computed data.
         */
        public GarbageFileStats(final DataFileReader fileReader, final long aliveItems) {
            this.fileReader = fileReader;
            this.aliveItems = aliveItems;
        }

        public int compactionLevel() {
            return fileReader.getMetadata().getCompactionLevel();
        }

        public long totalItems() {
            return fileReader.getMetadata().getItemsCount();
        }

        public long aliveItems() {
            return aliveItems;
        }

        void incrementAliveItems() {
            aliveItems++;
        }

        /**
         * Returns the fraction of items in this file that are no longer referenced by the index.
         *
         * Returns 1.0 for empty files (totalItems == 0). This value may be in two cases:
         * 1) the file is truly empty, in which case the compaction task will be no-op which is acceptable, or
         * 2) the file metadata is of the previous version which didn't support the item count. In this case we need to do the full compaction.
         */
        public double garbageRatio() {
            if (totalItems() == 0) {
                return 1.0;
            }
            return 1.0 - ((double) aliveItems / totalItems());
        }
    }
}

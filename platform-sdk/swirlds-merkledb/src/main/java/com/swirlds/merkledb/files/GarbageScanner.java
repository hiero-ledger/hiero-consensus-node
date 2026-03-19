// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;

import com.swirlds.merkledb.collections.CASableLongIndex;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

    public GarbageScanner(
            final CASableLongIndex index,
            final DataFileCollection dataFileCollection,
            final String storeName,
            final MerkleDbConfig config) {
        this.index = index;
        this.dataFileCollection = dataFileCollection;
        this.storeName = storeName;
        this.garbageThreshold = config.garbageThreshold();
    }

    /**
     * Traverse the index, compute per-file garbage statistics, and produce compaction candidates
     * grouped by level.
     *
     * <p>This method is intended to be called from a single background thread. It is read-only
     * with respect to both the index and the data files — no data is copied or modified.
     *
     * <p>The method applies garbage threshold filter: files whose garbage ratio exceeds {@code garbageThreshold}
     *  are collected as candidates for their level.
     *
     * @return a map from level to a list of files that should be compacted at that level
     */
    public ScanResult scan() {
        final long start = System.currentTimeMillis();

        final IndexedGarbageFileStats statsByFileIndex = createStatsByFileIndexArray();
        try {
            index.forEach(
                    (_, dataLocation) -> {
                        final int fileIndex = DataFileCommon.fileIndexFromDataLocation(dataLocation);
                        final int idx = fileIndex - statsByFileIndex.offset;
                        if (idx < 0 || idx >= statsByFileIndex.garbageFileStats.length) {
                            return;
                        }
                        final GarbageFileStats fileStats = statsByFileIndex.garbageFileStats[idx];
                        if (fileStats != null) {
                            fileStats.incrementAliveItems();
                        }
                    },
                    null);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn(MERKLE_DB.getMarker(), "[{}] Garbage scan was interrupted", storeName);
            return ScanResult.EMPTY;
        }

        final Map<Integer, List<DataFileReader>> result = new HashMap<>();
        for (final GarbageFileStats stats : statsByFileIndex.garbageFileStats) {
            if (stats != null && stats.garbageRatio() > garbageThreshold) {
                result.computeIfAbsent(stats.compactionLevel(), _ -> new ArrayList<>())
                        .add(stats.fileReader);
            }
        }

        logLevelStats(statsByFileIndex);

        final long tookMillis = System.currentTimeMillis() - start;
        logger.info(MERKLE_DB.getMarker(), "[{}] Garbage scan finished in {} ms", storeName, tookMillis);

        return new ScanResult(result, statsByFileIndex);
    }

    /**
     * Estimates the total alive data size across the given candidate files. For each file,
     * the alive fraction is derived from the garbage stats, and the file's on-disk size is
     * scaled accordingly. Files with zero total items contribute nothing.
     *
     * @param candidates files selected for compaction
     * @param statsByFileIndex per-file garbage statistics
     * @return estimated alive data size in bytes
     */
    static long estimateAliveSize(
            final List<DataFileReader> candidates, final IndexedGarbageFileStats statsByFileIndex) {
        long estimatedAliveSizeBytes = 0;
        for (final DataFileReader reader : candidates) {
            final GarbageFileStats stats =
                    statsByFileIndex.garbageFileStats[reader.getIndex() - statsByFileIndex.offset];
            if (stats != null && stats.totalItems() > 0) {
                final double aliveRatio = (double) stats.aliveItems() / stats.totalItems();
                estimatedAliveSizeBytes += (long) (reader.getSize() * aliveRatio);
            }
            // Files with totalItems == 0 are truly empty and contribute 0 bytes
        }
        return estimatedAliveSizeBytes;
    }

    private void logLevelStats(IndexedGarbageFileStats statsByFileIndex) {
        final Map<Integer, long[]> totalsByLevel = new TreeMap<>();
        for (final GarbageFileStats stats : statsByFileIndex.garbageFileStats) {
            if (stats == null) {
                continue;
            }
            final long[] levelTotals = totalsByLevel.computeIfAbsent(stats.compactionLevel(), _ -> new long[3]);
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
                    "[%s] Garbage scan level %d: files=%d, totalItems=%d, aliveItems=%d, garbageRatio=%1.2f"
                            .formatted(
                                    storeName,
                                    level,
                                    levelFilesCount,
                                    levelTotalItems,
                                    levelAliveItems,
                                    levelGarbageRatio));
        });
    }

    private IndexedGarbageFileStats createStatsByFileIndexArray() {
        List<DataFileReader> allCompletedFiles = new ArrayList<>(dataFileCollection.getAllCompletedFiles());
        allCompletedFiles.sort(Comparator.comparing(DataFileReader::getIndex));
        final int size = allCompletedFiles.getLast().getIndex()
                - allCompletedFiles.getFirst().getIndex();
        final int offset = allCompletedFiles.getFirst().getIndex();
        final GarbageFileStats[] statsByFileIndex = new GarbageFileStats[size + 1];
        for (DataFileReader fileReader : allCompletedFiles) {
            statsByFileIndex[fileReader.getIndex() - offset] = new GarbageFileStats(fileReader);
        }
        return new IndexedGarbageFileStats(offset, statsByFileIndex);
    }

    public record ScanResult(Map<Integer, List<DataFileReader>> candidatesByLevel, IndexedGarbageFileStats stats) {
        public static final ScanResult EMPTY = new ScanResult(Map.of(), null);
    }

    public record IndexedGarbageFileStats(int offset, GarbageFileStats[] garbageFileStats) {}

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

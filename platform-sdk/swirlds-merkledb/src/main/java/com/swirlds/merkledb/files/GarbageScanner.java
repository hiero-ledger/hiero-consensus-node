// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.base.units.UnitConstants.KIBIBYTES_TO_BYTES;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.merkledb.files.DataFileCommon.formatSizeBytes;

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
     * Traverse the index, compute per-file garbage statistics, and produce compaction candidates
     * grouped by level.
     *
     * <p>This method is intended to be called from a single background thread. It is read-only
     * with respect to both the index and the data files — no data is copied or modified.
     *
     * <p>The method applies three filters in sequence:
     * <ol>
     *   <li><b>Garbage threshold</b>: files whose garbage ratio exceeds {@code garbageThreshold}
     *       are collected as candidates for their level.</li>
     *   <li><b>Projected output size cap</b>: candidates at each level are sorted by garbage ratio
     *       (highest first) and selected greedily until the cumulative projected alive size would
     *       exceed {@code maxCompactionDataPerLevelInKB}. At least one file is always selected.</li>
     *   <li><b>Minimum alive size</b>: levels where the estimated alive data is below
     *       {@code minCompactionSizeKB × 2^level} are excluded entirely.</li>
     * </ol>
     *
     * @return a map from level to a list of files that should be compacted at that level
     */
    public ScanResult scan() {
        final long start = System.currentTimeMillis();

        // Count alive items per file by traversing the index
        final IndexedGarbageFileStats statsByFileIndex = createStatsByFileIndexArray();
        try {
            index.forEach(
                    (_, dataLocation) -> {
                        final int fileIndex = DataFileCommon.fileIndexFromDataLocation(dataLocation);
                        final GarbageFileStats fileStats =
                                statsByFileIndex.garbageFileStats[fileIndex - statsByFileIndex.offset];
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

        // Phase 1: collect all files exceeding the garbage threshold, grouped by level
        final Map<Integer, List<DataFileReader>> filesToCompactByLevel = new HashMap<>();
        final Map<Integer, List<DataFileReader>> filesToPromoteByLevel = new HashMap<>();
        final long[] estimatedSizeByLevel = new long[maxCompactionLevel + 1];
        for (final GarbageFileStats stats : statsByFileIndex.garbageFileStats) {
            if (stats == null) {
                continue;
            }
            if (stats.garbageRatio() > garbageThreshold) {
                filesToCompactByLevel
                        .computeIfAbsent(stats.compactionLevel(), _ -> new ArrayList<>())
                        .add(stats.fileReader);
                estimatedSizeByLevel[stats.compactionLevel()] +=
                        (long) (stats.fileReader.getSize() * (1.0 - stats.garbageRatio()));
            }
        }

        // Phase 2: cap projected output size per level.
        // Candidates are sorted by garbage ratio descending (the highest garbage = cheapest to
        // process, the smallest contribution to output) and selected greedily until the cumulative
        // projected alive size would exceed the cap. The first file is always included.
        if (maxCompactionDataPerLevelInKB > 0) {
            for (final Map.Entry<Integer, List<DataFileReader>> entry : filesToCompactByLevel.entrySet()) {
                // we don't need to filter the level if we know that that projected size is already below the threshold
                long maxProjectedBytes = maxCompactionDataPerLevelInKB * KIBIBYTES_TO_BYTES;
                if (estimatedSizeByLevel[entry.getKey()] < maxProjectedBytes) {
                    logger.info(
                            MERKLE_DB.getMarker(),
                            "[{}] Skipping filtering by max file size at level {}: estimated projected size {} is below cap {}",
                            storeName,
                            entry.getKey(),
                            formatSizeBytes(estimatedSizeByLevel[entry.getKey()]),
                            formatSizeBytes(maxProjectedBytes));
                    continue;
                }
                filesToPromoteByLevel.put(entry.getKey(), filterCandidates(entry, statsByFileIndex));
            }
        }

        logLevelStats(statsByFileIndex);

        final long tookMillis = System.currentTimeMillis() - start;
        logger.info(MERKLE_DB.getMarker(), "[{}] Garbage scan finished in {} ms", storeName, tookMillis);

        return new ScanResult(filesToCompactByLevel, filesToPromoteByLevel);
    }

    private List<DataFileReader> filterCandidates(
            Map.Entry<Integer, List<DataFileReader>> entry, IndexedGarbageFileStats statsByFileIndex) {
        final int indexOffset = statsByFileIndex.offset;
        final GarbageFileStats[] statsArray = statsByFileIndex.garbageFileStats;
        final long maxProjectedBytes = maxCompactionDataPerLevelInKB * KIBIBYTES_TO_BYTES;
        final List<DataFileReader> candidates = entry.getValue();
        candidates.sort((a, b) -> Double.compare(
                statsArray[b.getIndex() - indexOffset].garbageRatio(),
                statsArray[a.getIndex() - indexOffset].garbageRatio()));

        long projectedOutputSize = 0;
        final List<DataFileReader> readersToCompact = new ArrayList<>();
        final List<DataFileReader> readersToPromote = new ArrayList<>();
        for (final DataFileReader reader : candidates) {
            final GarbageFileStats stats = statsArray[reader.getIndex() - indexOffset];
            final long projectedAlive =
                    (stats.totalItems() == 0) ? 0 : (long) (reader.getSize() * (1.0 - stats.garbageRatio()));
            if (!readersToCompact.isEmpty() && projectedOutputSize + projectedAlive > maxProjectedBytes) {
                readersToPromote.add(reader);
                continue;
            }
            readersToCompact.add(reader);
            projectedOutputSize += projectedAlive;
        }
        entry.setValue(readersToCompact);
        logger.info(
                MERKLE_DB.getMarker(),
                "[{}] Selected {} files for compaction at level {} with projected output size {}",
                storeName,
                readersToCompact.size(),
                entry.getKey(),
                formatSizeBytes(projectedOutputSize));
        return readersToPromote;
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

    record IndexedGarbageFileStats(int offset, GarbageFileStats[] garbageFileStats) {}

    /**
     * Result of a garbage scan for a single store. Contains two disjoint sets of files per level:
     * files selected for compaction (within the projected output size cap) and overflow files
     * (above the garbage threshold but excluded by the cap, eligible for promotion).
     */
    public record ScanResult(
            Map<Integer, List<DataFileReader>> filesToCompact, Map<Integer, List<DataFileReader>> filesToPromote) {

        public static final ScanResult EMPTY = new ScanResult(Map.of(), Map.of());
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

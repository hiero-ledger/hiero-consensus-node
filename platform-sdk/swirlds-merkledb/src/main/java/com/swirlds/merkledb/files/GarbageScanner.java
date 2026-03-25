// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.base.units.UnitConstants.KIBIBYTES_TO_BYTES;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.merkledb.files.DataFileCommon.formatSizeBytes;
import static java.util.Objects.requireNonNull;

import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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
 *
 * <p>The scan operates in two phases:
 * <ol>
 *   <li><b>Phase 1 — Select eligible files:</b> files whose {@code liveToDeadRatio} is below
 *       {@code gcRateThreshold} are collected as compaction candidates for their level.</li>
 *   <li><b>Phase 2 — Absorb small files:</b> for each level where the aggregate live/dead ratio
 *       is still below {@code gcRateThreshold} and the projected output size is below
 *       {@code maxCompactedFileSizeInKB}, the scanner sorts remaining files (those NOT
 *       selected in phase 1) by size ascending and greedily absorbs them until either limit is
 *       reached. This consolidates small files that individually don't have enough garbage but
 *       can be absorbed within the budget provided by dirtier files from phase 1.</li>
 * </ol>
 */
public class GarbageScanner {

    private static final Logger logger = LogManager.getLogger(GarbageScanner.class);

    private final LongList index;
    private final DataFileCollection dataFileCollection;
    private final String storeName;
    private final double gcRateThreshold;
    private final long maxCompactedFileSizeInKB;
    private final boolean deduplicateMirroredEntries;

    /**
     * Creates a new scanner with {@code deduplicateMirroredEntries} disabled.
     *
     * @param index             the in-memory index to traverse
     * @param dataFileCollection the file collection whose files are scanned
     * @param storeName         store name used for logging
     * @param config            MerkleDb configuration
     */
    public GarbageScanner(
            @NonNull final LongList index,
            @NonNull final DataFileCollection dataFileCollection,
            @NonNull final String storeName,
            @NonNull final MerkleDbConfig config) {
        this(index, dataFileCollection, storeName, config, false);
    }

    /**
     * Creates a new scanner.
     *
     * @param index                      the in-memory index to traverse
     * @param dataFileCollection         the file collection whose files are scanned
     * @param storeName                  store name used for logging
     * @param config                     MerkleDb configuration
     * @param deduplicateMirroredEntries if {@code true}, enables HDHM bucket deduplication mode
     *                                   (see class javadoc for details)
     */
    public GarbageScanner(
            @NonNull final LongList index,
            @NonNull final DataFileCollection dataFileCollection,
            @NonNull final String storeName,
            @NonNull final MerkleDbConfig config,
            final boolean deduplicateMirroredEntries) {
        requireNonNull(index);
        requireNonNull(dataFileCollection);
        requireNonNull(storeName);
        requireNonNull(config);

        this.index = index;
        this.dataFileCollection = dataFileCollection;
        this.storeName = storeName;
        this.gcRateThreshold = config.gcRateThreshold();
        this.maxCompactedFileSizeInKB = config.maxCompactedFileSizeInKB();
        this.deduplicateMirroredEntries = deduplicateMirroredEntries;
    }

    /**
     * Traverse the index, compute per-file garbage statistics, and produce compaction candidates
     * grouped by level.
     *
     * <p>This method is intended to be called from a single background thread. It is read-only
     * with respect to both the index and the data files — no data is copied or modified.
     *
     * @return scan result containing candidates grouped by level and per-file statistics
     */
    @NonNull
    public ScanResult scan() {
        final long start = System.currentTimeMillis();

        // Count alive items per file by traversing the index
        final IndexedGarbageFileStats statsByFileIndex = createStatsByFileIndexArray();
        if (deduplicateMirroredEntries) {
            final long halfSize = index.size() / 2;
            for (long i = 0; i < halfSize; i++) {
                final long locationLow = index.get(i, 0);
                final long locationHigh = index.get(i + halfSize, 0);
                if (locationLow != 0) {
                    countAlive(locationLow, statsByFileIndex);
                }
                if (locationHigh != 0 && locationHigh != locationLow) {
                    countAlive(locationHigh, statsByFileIndex);
                }
            }
        } else {
            for (long i = 0; i < index.size(); i++) {
                final long location = index.get(i, 0);
                if (location != 0) {
                    countAlive(location, statsByFileIndex);
                }
            }
        }

        // Phase 1: select files whose live/dead ratio is below the threshold
        final Map<Integer, List<DataFileReader>> selectedByLevel = new HashMap<>();
        final Map<Integer, List<DataFileReader>> remainingByLevel = new HashMap<>();
        for (final GarbageFileStats stats : statsByFileIndex.garbageFileStats) {
            if (stats == null) {
                continue;
            }
            final int level = stats.compactionLevel();
            if (stats.liveToDeadRatio() < gcRateThreshold) {
                selectedByLevel.computeIfAbsent(level, _ -> new ArrayList<>()).add(stats.fileReader);
            } else {
                remainingByLevel.computeIfAbsent(level, _ -> new ArrayList<>()).add(stats.fileReader);
            }
        }

        // Phase 2: absorb small remaining files when there's headroom in both ratio and size
        absorbSmallFiles(selectedByLevel, remainingByLevel, statsByFileIndex);

        logLevelStats(statsByFileIndex);

        final long tookMillis = System.currentTimeMillis() - start;
        logger.info(MERKLE_DB.getMarker(), "[{}] Garbage scan finished in {} ms", storeName, tookMillis);

        return new ScanResult(selectedByLevel, statsByFileIndex);
    }

    /**
     * Phase 2: for each level with selected candidates, check if there is headroom in both
     * the aggregate live/dead ratio and the projected output size. If so, sort the remaining
     * files at this level by size ascending and greedily absorb them until either limit is
     * reached.
     *
     * <p>This consolidates small low-garbage files that would otherwise proliferate. The dirty
     * files from phase 1 provide a "budget" of GC efficiency that is spent absorbing clean files.
     */
    private void absorbSmallFiles(
            @NonNull final Map<Integer, List<DataFileReader>> selectedByLevel,
            @NonNull final Map<Integer, List<DataFileReader>> remainingByLevel,
            @NonNull final IndexedGarbageFileStats stats) {

        final long maxProjectedBytes =
                maxCompactedFileSizeInKB > 0 ? maxCompactedFileSizeInKB * KIBIBYTES_TO_BYTES : Long.MAX_VALUE;

        for (final var entry : selectedByLevel.entrySet()) {
            final int level = entry.getKey();
            final List<DataFileReader> selected = entry.getValue();
            final List<DataFileReader> remaining = remainingByLevel.getOrDefault(level, List.of());
            if (remaining.isEmpty()) {
                continue;
            }

            // Compute aggregate live/dead and projected size for the phase 1 candidates
            long totalLive = 0;
            long totalDead = 0;
            long projectedSize = 0;
            for (final DataFileReader reader : selected) {
                final GarbageFileStats fs = lookupStats(reader, stats);
                if (fs != null) {
                    totalLive += fs.aliveItems();
                    totalDead += fs.deadItems();
                    projectedSize += estimateAliveBytes(reader, fs);
                }
            }

            final double aggregateRatio = totalDead == 0 ? Double.MAX_VALUE : (double) totalLive / totalDead;

            // No headroom — skip absorption for this level
            if (aggregateRatio >= gcRateThreshold || projectedSize >= maxProjectedBytes) {
                continue;
            }

            // Sort remaining files by size ascending — absorb smallest first
            final List<DataFileReader> sortedRemaining = new ArrayList<>(remaining);
            sortedRemaining.sort(Comparator.comparingLong(DataFileReader::getSize));

            int absorbed = 0;
            for (final DataFileReader reader : sortedRemaining) {
                final GarbageFileStats fs = lookupStats(reader, stats);
                if (fs == null) {
                    continue;
                }

                final long fileLive = fs.aliveItems();
                final long fileDead = fs.deadItems();
                final long fileProjectedAlive = estimateAliveBytes(reader, fs);

                final long newTotalLive = totalLive + fileLive;
                final long newTotalDead = totalDead + fileDead;
                final double newRatio = newTotalDead == 0 ? Double.MAX_VALUE : (double) newTotalLive / newTotalDead;
                final long newProjectedSize = projectedSize + fileProjectedAlive;

                // Stop if adding this file would breach either limit
                if (newRatio >= gcRateThreshold || newProjectedSize >= maxProjectedBytes) {
                    break;
                }

                selected.add(reader);
                totalLive = newTotalLive;
                totalDead = newTotalDead;
                projectedSize = newProjectedSize;
                absorbed++;
            }

            if (absorbed > 0) {
                final String finalRatio = totalDead == 0
                        ? "n/a"
                        : String.valueOf(Math.round((double) totalLive / totalDead * 100) / 100.0);
                logger.info(
                        MERKLE_DB.getMarker(),
                        "[{}] Phase 2: absorbed {} small files at level {}, "
                                + "aggregate live/dead={}, projected output={}",
                        storeName,
                        absorbed,
                        level,
                        finalRatio,
                        formatSizeBytes(projectedSize));
            }
        }
    }

    private static void countAlive(final long dataLocation, @NonNull final IndexedGarbageFileStats statsByFileIndex) {
        final int fileIndex = DataFileCommon.fileIndexFromDataLocation(dataLocation);
        final int idx = fileIndex - statsByFileIndex.offset;
        if (idx < 0 || idx >= statsByFileIndex.garbageFileStats.length) {
            return;
        }
        final GarbageFileStats fileStats = statsByFileIndex.garbageFileStats[idx];
        if (fileStats != null) {
            fileStats.incrementAliveItems();
        }
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
    public static long estimateAliveSize(
            @NonNull final List<DataFileReader> candidates, @NonNull final IndexedGarbageFileStats statsByFileIndex) {
        long estimatedAliveSizeBytes = 0;
        for (final DataFileReader reader : candidates) {
            final GarbageFileStats stats = lookupStats(reader, statsByFileIndex);
            if (stats != null) {
                estimatedAliveSizeBytes += estimateAliveBytes(reader, stats);
            }
        }
        return estimatedAliveSizeBytes;
    }

    private static long estimateAliveBytes(
            @NonNull final DataFileReader reader, @NonNull final GarbageFileStats stats) {
        if (stats.totalItems() == 0) {
            return 0;
        }
        return (long) (reader.getSize() * (1.0 - stats.garbageRatio()));
    }

    @Nullable
    private static GarbageFileStats lookupStats(
            @NonNull final DataFileReader reader, @NonNull final IndexedGarbageFileStats stats) {
        final int idx = reader.getIndex() - stats.offset;
        if (idx < 0 || idx >= stats.garbageFileStats.length) {
            return null;
        }
        return stats.garbageFileStats[idx];
    }

    private void logLevelStats(@NonNull final IndexedGarbageFileStats statsByFileIndex) {
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
            final long levelDeadItems = levelTotalItems - levelAliveItems;
            final String levelLiveToDeadRatio = levelDeadItems == 0
                    ? "n/a"
                    : String.valueOf(Math.round((double) levelAliveItems / levelDeadItems * 100) / 100.0);

            logger.info(
                    MERKLE_DB.getMarker(),
                    "[%s] Garbage scan level %d: files=%d, totalItems=%d, aliveItems=%d, garbageRatio=%1.2f, live/dead=%s"
                            .formatted(
                                    storeName,
                                    level,
                                    levelFilesCount,
                                    levelTotalItems,
                                    levelAliveItems,
                                    levelGarbageRatio,
                                    levelLiveToDeadRatio));
        });
    }

    @NonNull
    private IndexedGarbageFileStats createStatsByFileIndexArray() {
        final List<DataFileReader> allCompletedFiles = new ArrayList<>(dataFileCollection.getAllCompletedFiles());
        allCompletedFiles.sort(Comparator.comparing(DataFileReader::getIndex));
        if (allCompletedFiles.isEmpty()) {
            return new IndexedGarbageFileStats(0, new GarbageFileStats[0]);
        }
        final int firstIndex = allCompletedFiles.getFirst().getIndex();
        final int lastIndex = allCompletedFiles.getLast().getIndex();
        final int size = lastIndex - firstIndex;
        final GarbageFileStats[] statsByFileIndex = new GarbageFileStats[size + 1];
        for (final DataFileReader fileReader : allCompletedFiles) {
            statsByFileIndex[fileReader.getIndex() - firstIndex] = new GarbageFileStats(fileReader);
        }
        return new IndexedGarbageFileStats(firstIndex, statsByFileIndex);
    }

    /**
     * Scan result containing compaction candidates grouped by level and per-file statistics
     * for use by the coordinator when splitting work into groups.
     *
     * @param candidatesByLevel compaction candidates grouped by compaction level
     * @param stats             per-file garbage statistics indexed by file index
     */
    public record ScanResult(
            @NonNull Map<Integer, List<DataFileReader>> candidatesByLevel,
            @Nullable IndexedGarbageFileStats stats) {

        /** Empty scan result with no candidates and no statistics. */
        public static final ScanResult EMPTY = new ScanResult(Map.of(), null);
    }

    /**
     * Per-file garbage statistics indexed by file index. The {@code offset} is subtracted from
     * a file's index to obtain the array position in {@code garbageFileStats}.
     *
     * @param offset           the file index of the first element in the array
     * @param garbageFileStats array of per-file statistics; entries may be {@code null} for
     *                         gaps in the file index sequence
     */
    public record IndexedGarbageFileStats(
            int offset, @NonNull GarbageFileStats[] garbageFileStats) {}

    /**
     * Per-file garbage statistics. Tracks total items (from file metadata) and alive items
     * (counted during index traversal). Not thread-safe — intended for use within a single
     * scanner thread.
     */
    public static final class GarbageFileStats {

        @NonNull
        final DataFileReader fileReader;

        private long aliveItems;

        /**
         * Creates stats with zero alive items. Used by the scanner during index traversal;
         * alive items are incremented as entries are encountered.
         *
         * @param fileReader the data file this stats object tracks
         */
        public GarbageFileStats(@NonNull final DataFileReader fileReader) {
            this(fileReader, 0);
        }

        /**
         * Creates stats with a known alive item count. Useful for testing and for constructing
         * stats from pre-computed data.
         *
         * @param fileReader the data file this stats object tracks
         * @param aliveItems initial alive item count
         */
        public GarbageFileStats(@NonNull final DataFileReader fileReader, final long aliveItems) {
            this.fileReader = requireNonNull(fileReader);
            this.aliveItems = aliveItems;
        }

        /**
         * Returns the compaction level of the file.
         *
         * @return compaction level
         */
        public int compactionLevel() {
            return fileReader.getMetadata().getCompactionLevel();
        }

        /**
         * Returns the total number of items recorded in the file's metadata.
         *
         * @return total item count
         */
        public long totalItems() {
            return fileReader.getMetadata().getItemsCount();
        }

        /**
         * Returns the number of items still referenced by the index.
         *
         * @return alive item count
         */
        public long aliveItems() {
            return aliveItems;
        }

        /**
         * Returns the number of items no longer referenced by the index.
         *
         * @return dead item count ({@code totalItems - aliveItems})
         */
        public long deadItems() {
            return totalItems() - aliveItems;
        }

        void incrementAliveItems() {
            aliveItems++;
        }

        /**
         * Returns the fraction of items in this file that are no longer referenced by the index.
         * Used for estimating projected output file size.
         *
         * <p>Returns 1.0 for empty files (totalItems == 0). This value may occur in two cases:
         * 1) the file is truly empty, in which case the compaction task will be a no-op which
         * is acceptable, or 2) the file metadata is of the previous version which didn't support
         * the item count. In this case we need to do the full compaction.
         *
         * @return garbage ratio in the range [0.0, 1.0]
         */
        public double garbageRatio() {
            if (totalItems() == 0) {
                return 1.0;
            }
            return 1.0 - ((double) aliveItems / totalItems());
        }

        /**
         * Returns the ratio of live items to dead items. This describes the compaction speed —
         * lower ratio means faster GC (less data to copy per dead item reclaimed).
         *
         * <p>Returns 0.0 for files with no alive items or zero total items (best possible —
         * nothing to copy). Returns {@link Double#MAX_VALUE} for files with no dead items
         * (no garbage — never individually selected for compaction).
         *
         * <p>The ratio is "additive" across files: for a set of files, the aggregate ratio
         * is {@code Σlive / Σdead}, which describes the combined compaction speed of the batch.
         *
         * @return live-to-dead ratio, or 0.0 / {@link Double#MAX_VALUE} for edge cases
         */
        public double liveToDeadRatio() {
            if (totalItems() == 0) {
                return 0.0;
            }
            final long dead = deadItems();
            if (dead == 0) {
                return Double.MAX_VALUE;
            }
            if (aliveItems == 0) {
                return 0.0;
            }
            return (double) aliveItems / dead;
        }
    }
}

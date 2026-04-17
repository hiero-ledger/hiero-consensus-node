// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static java.lang.Math.max;
import static java.util.Objects.requireNonNull;

import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileCommon;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.merkledb.files.MemoryIndexDiskKeyValueStore;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>The scanner is a pure data collector — it computes statistics for all files but does not
 * filter or group them. Filtering (by {@code gcRateThreshold}), grouping (by projected output
 * size), and phase 2 absorption are the responsibility of
 * {@link MerkleDbCompactionCoordinator}.
 */
public class GarbageScanner {

    private static final Logger logger = LogManager.getLogger(GarbageScanner.class);

    private final LongList index;
    private final DataFileCollection dataFileCollection;
    private final String storeName;
    private final boolean deduplicateMirroredEntries;

    /**
     * Creates a new scanner with {@code deduplicateMirroredEntries} disabled.
     *
     * @param index              the in-memory index to traverse
     * @param dataFileCollection the file collection whose files are scanned
     */
    public GarbageScanner(@NonNull final LongList index, @NonNull final DataFileCollection dataFileCollection) {
        this(index, dataFileCollection, false);
    }

    /**
     * Creates a new scanner.
     *
     * @param index                      the in-memory index to traverse
     * @param dataFileCollection         the file collection whose files are scanned
     * @param deduplicateMirroredEntries if {@code true}, enables HDHM bucket deduplication mode.
     *                                   After a {@link HalfDiskHashMap} doubles its bucket count,
     *                                   entries at index {@code x} and {@code x + N/2} may point
     *                                   to the same data location (unsanitized copy). This mode
     *                                   iterates only the lower half and compares pairs, counting
     *                                   duplicates only once.
     */
    public GarbageScanner(
            @NonNull final LongList index,
            @NonNull final DataFileCollection dataFileCollection,
            final boolean deduplicateMirroredEntries) {
        requireNonNull(index);
        requireNonNull(dataFileCollection);

        this.index = index;
        this.dataFileCollection = dataFileCollection;
        this.storeName = dataFileCollection.getStoreName();
        this.deduplicateMirroredEntries = deduplicateMirroredEntries;
    }

    /**
     * Traverse the index and compute per-file garbage statistics for all completed files
     * in the collection.
     *
     * <p>This method is intended to be called from a single background thread. It is read-only
     * with respect to both the index and the data files — no data is copied or modified.
     *
     * @return per-file statistics for all completed files, indexed by file index
     */
    @NonNull
    public IndexedGarbageFileStats scan() {
        final long start = System.currentTimeMillis();

        // Count alive items per file by traversing the index
        final IndexedGarbageFileStats statsByFileIndex = createStatsByFileIndexArray();
        if (deduplicateMirroredEntries) {
            final long halfSize = index.size() / 2;
            for (long i = max(0, index.getMinValidIndex()); i < halfSize; i++) {
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
            for (long i = max(0, index.getMinValidIndex()); i < index.size(); i++) {
                final long location = index.get(i, 0);
                if (location != 0) {
                    countAlive(location, statsByFileIndex);
                }
            }
        }

        logLevelStats(statsByFileIndex);

        final long tookMillis = System.currentTimeMillis() - start;
        logger.info(MERKLE_DB.getMarker(), "[{}] Garbage scan finished in {} ms", storeName, tookMillis);

        return statsByFileIndex;
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
            final String levelDeadToAliveRatio = levelAliveItems == 0
                    ? "n/a"
                    : String.valueOf(Math.round((double) levelDeadItems / levelAliveItems * 100) / 100.0);

            logger.info(
                    MERKLE_DB.getMarker(),
                    "[%s] Garbage scan level %d: files=%d, totalItems=%d, aliveItems=%d, garbageRatio=%1.2f, dead/alive=%s"
                            .formatted(
                                    storeName,
                                    level,
                                    levelFilesCount,
                                    levelTotalItems,
                                    levelAliveItems,
                                    levelGarbageRatio,
                                    levelDeadToAliveRatio));
        });
    }

    @NonNull
    private IndexedGarbageFileStats createStatsByFileIndexArray() {
        final List<DataFileReader> allCompletedFiles = new ArrayList<>(dataFileCollection.getAllCompletedFiles());
        allCompletedFiles.removeIf(DataFileReader::isCompactionInProgress);
        allCompletedFiles.sort(Comparator.comparing(DataFileReader::getIndex));
        if (allCompletedFiles.isEmpty()) {
            return new IndexedGarbageFileStats(0, new GarbageFileStats[0]);
        }
        final int firstIndex = allCompletedFiles.getFirst().getIndex();
        final int lastIndex = allCompletedFiles.getLast().getIndex();
        final int size = lastIndex - firstIndex + 1;
        final GarbageFileStats[] statsByFileIndex = new GarbageFileStats[size];
        for (final DataFileReader fileReader : allCompletedFiles) {
            statsByFileIndex[fileReader.getIndex() - firstIndex] = new GarbageFileStats(fileReader);
        }
        return new IndexedGarbageFileStats(firstIndex, statsByFileIndex);
    }

    /**
     * Per-file garbage statistics indexed by file index. The {@code offset} is subtracted from
     * a file's index to obtain the array position in {@code garbageFileStats}.
     *
     * @param offset           the file index of the first element in the array
     * @param garbageFileStats array of per-file statistics; entries may be {@code null} for
     *                         gaps in the file index sequence
     */
    record IndexedGarbageFileStats(int offset, @NonNull GarbageFileStats[] garbageFileStats) {
        @NonNull
        List<GarbageFileStats> getNonNullGarbageStats() {
            final List<GarbageFileStats> nonNullStats = new ArrayList<>();
            for (final GarbageFileStats stats : garbageFileStats) {
                if (stats != null) {
                    nonNullStats.add(stats);
                }
            }
            return nonNullStats;
        }

        /**
         * Estimates the projected alive bytes for a single file based on scan statistics.
         * Returns 0 for files with unknown item counts or files not found in the stats
         * (e.g. deleted between scan and grouping).
         */
        static long estimateAliveBytes(
                final @NonNull DataFileReader reader, final @Nullable GarbageFileStats fileStats) {
            if (fileStats == null || fileStats.totalItems() == 0) {
                return 0;
            }
            return (long) (reader.getSize() * (1.0 - fileStats.garbageRatio()));
        }

        @NonNull
        GarbageFileStats lookupStats(final @NonNull DataFileReader reader) {
            final int idx = reader.getIndex() - this.offset();
            if (idx < 0 || idx >= this.garbageFileStats().length) {
                throw new IllegalArgumentException(
                        "File index %d is out of bounds for stats with offset %d and length %d"
                                .formatted(reader.getIndex(), this.offset(), this.garbageFileStats().length));
            }
            GarbageFileStats fileStats = garbageFileStats()[idx];
            if (fileStats == null) {
                throw new IllegalStateException(
                        "No stats found for file index %d at offset %d".formatted(reader.getIndex(), this.offset()));
            }
            return fileStats;
        }
    }

    /**
     * Per-file garbage statistics. Tracks total items (from file metadata) and alive items
     * (counted during index traversal). Not thread-safe — intended for use within a single
     * scanner thread.
     */
    static final class GarbageFileStats {

        @NonNull
        final DataFileReader fileReader;

        private long aliveItems;

        /**
         * Creates stats with zero alive items.
         *
         * @param fileReader the data file this stats object tracks
         */
        public GarbageFileStats(@NonNull final DataFileReader fileReader) {
            this.fileReader = requireNonNull(fileReader);
        }

        /** @return compaction level */
        public int compactionLevel() {
            return fileReader.getMetadata().getCompactionLevel();
        }

        /** @return total item count from file metadata */
        public long totalItems() {
            return fileReader.getMetadata().getItemsCount();
        }

        /** @return number of items still referenced by the index */
        public long aliveItems() {
            // This `min` is to address the rare case of subsequent doubling of bucket count in HDHM.
            // Even though the scanner is designed to deduplicate mirrored entries, it is possible that
            // some buckets are still double-counted because they were not updated since the previous doubling.
            // In this case, alive items count may slightly exceed the total items count, which would lead to negative
            // dead items and garbage ratio below 0.
            // The `min` caps alive items at total items, ensuring that the garbage ratio is always between 0 and 1.
            return Math.min(totalItems(), aliveItems);
        }

        /** @return {@code totalItems - aliveItems} */
        public long deadItems() {
            return totalItems() - aliveItems();
        }

        void incrementAliveItems() {
            aliveItems++;
        }

        void incrementAliveItemsBy(long count) {
            aliveItems += count;
        }

        /**
         * Fraction of dead items. Used for projected output size estimation.
         * Returns 1.0 when {@code totalItems == 0}.
         *
         * @return garbage ratio in [0.0, 1.0]
         */
        public double garbageRatio() {
            if (totalItems() == 0) {
                return 1.0;
            }
            return 1.0 - ((double) aliveItems() / totalItems());
        }

        /**
         * Ratio of dead items to alive items — describes GC speed. Higher is better.
         * Returns {@link Double#MAX_VALUE} when {@code aliveItems == 0} or {@code totalItems == 0}.
         * Returns 0.0 when {@code deadItems == 0}.
         *
         * @return dead-to-alive ratio
         */
        public double deadToAliveRatio() {
            if (aliveItems() == 0) {
                return Double.MAX_VALUE;
            }
            final long dead = deadItems();

            return (double) dead / aliveItems();
        }
    }
}

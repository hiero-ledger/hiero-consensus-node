// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.base.units.UnitConstants.GIBIBYTES_TO_BYTES;
import static com.swirlds.base.units.UnitConstants.KIBIBYTES_TO_BYTES;
import static com.swirlds.base.units.UnitConstants.MEBIBYTES_TO_BYTES;
import static com.swirlds.common.formatting.HorizontalAlignment.ALIGNED_RIGHT;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static java.util.stream.Collectors.joining;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.swirlds.merkledb.KeyRange;
import com.swirlds.merkledb.collections.IndexedObject;
import com.swirlds.merkledb.collections.LongList;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Common static content for data files. As much as possible is package protected but some is used
 * outside.
 */
public final class DataFileCommon {

    private static final Logger logger = LogManager.getLogger(DataFileCommon.class);

    /** The inverse of the minimum decimal value to be reflected in rounding (that is, 0.01). */
    private static final int ROUNDING_SCALE_FACTOR = 100;
    /** Size of right-justified field to use when printing indexes. */
    private static final int PRINTED_INDEX_FIELD_WIDTH = 10;
    /**
     * Nominal value to indicate a non-existent data location. This was carefully crafted to be 0 so
     * that a new long array of data location pointers will be initialized to be all non-existent.
     */
    public static final long NON_EXISTENT_DATA_LOCATION = 0;

    /**
     * The data item byte offset is packed into lower 40 bits and file index upper 24 bits. This
     * allows for 16 million files 1 trillion bytes of data. So at one file per minute we have 30
     * years of 1Tb files.
     */
    /* FUTURE WORK - https://github.com/swirlds/swirlds-platform/issues/3927 */
    private static final int DATA_ITEM_OFFSET_BITS = 40;
    /** The maximum size a data file can be, 1Tb with DATA_ITEM_OFFSET_BITS = 40 */
    private static final long MAX_ADDRESSABLE_DATA_FILE_SIZE_BYTES = 1L << DATA_ITEM_OFFSET_BITS;
    /** Bit mask to remove file index from data location long */
    private static final long ITEM_OFFSET_MASK = MAX_ADDRESSABLE_DATA_FILE_SIZE_BYTES - 1;

    /** Date formatter for dates used in data file names */
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS").withZone(ZoneId.of("Z"));
    /** Extension to use for Merkle DB data files in protobuf format */
    public static final String FILE_EXTENSION = ".pbj";
    /**
     * System page size used in calculations, could be read from system but for linux we are pretty
     * safe assuming 4k
     */
    public static final int PAGE_SIZE = 4096;

    // Data file protobuf fields
    static final FieldDefinition FIELD_DATAFILE_METADATA =
            new FieldDefinition("metadata", FieldType.MESSAGE, false, false, false, 1);
    static final FieldDefinition FIELD_DATAFILE_ITEMS =
            new FieldDefinition("items", FieldType.MESSAGE, true, true, false, 11);

    private DataFileCommon() {
        throw new IllegalStateException("Utility class; should not be instantiated.");
    }

    /**
     * Get path for file given prefix, index and parent directory. This standardizes out file naming
     * convention.
     *
     * @param filePrefix the prefix for file name
     * @param dataFileDir the files parent directory
     * @param index the file index
     * @param creationInstant the date and time the file was created
     * @return path to file
     */
    static Path createDataFilePath(
            final String filePrefix,
            final Path dataFileDir,
            final int index,
            final Instant creationInstant,
            final String extension) {
        return dataFileDir.resolve(filePrefix
                + "_"
                + DATE_FORMAT.format(creationInstant)
                + "_"
                + ALIGNED_RIGHT.pad(Integer.toString(index), '_', PRINTED_INDEX_FIELD_WIDTH, false)
                + extension);
    }

    /**
     * Get the packed data location from file index and byte offset.
     *
     * @param fileIndex the index for the file
     * @param byteOffset the offset for the data within the file in bytes
     * @return packed data location
     */
    public static long dataLocation(final int fileIndex, final long byteOffset) {
        // we add 1 to file index so that 0 works for NON_EXISTENT_DATA_LOCATION
        final long indexShifted = (long) (fileIndex + 1) << DATA_ITEM_OFFSET_BITS;
        final long byteOffsetMasked = byteOffset & ITEM_OFFSET_MASK;
        return indexShifted | byteOffsetMasked;
    }

    /**
     * Get a friendly string with the disk data location split into its file and offset parts. Very
     * useful for debugging and logging.
     *
     * @param dataLocation Packed disk location containing file and offset.
     * @return String with split file and offset
     */
    public static String dataLocationToString(final long dataLocation) {
        if (dataLocation <= 0) {
            return String.valueOf(dataLocation);
        }
        return "{" + fileIndexFromDataLocation(dataLocation) + "," + byteOffsetFromDataLocation(dataLocation) + "}";
    }

    /**
     * Extract the file index from packed data location, this is the upper 24 bits. So in the range
     * of 0 to 16 million.
     *
     * @param dataLocation packed data location
     * @return file index
     */
    public static int fileIndexFromDataLocation(final long dataLocation) {
        // we subtract 1 from file index so that 0 works for NON_EXISTENT_DATA_LOCATION
        return (int) (dataLocation >> DATA_ITEM_OFFSET_BITS) - 1;
    }

    /**
     * Extract the data byte offset from packed data location, this is the lower 40 bits so in the 0
     * to 1 trillion range.
     *
     * @param dataLocation packed data location
     * @return data offset in bytes
     */
    static long byteOffsetFromDataLocation(final long dataLocation) {
        return dataLocation & ITEM_OFFSET_MASK;
    }

    /**
     * Check if a file at path, is a data file based on name. Also checks if there is an existing
     * write lock file.
     *
     * @param filePrefix the prefix for the set of data files
     * @param path the path to the data file
     * @return true if the name starts with prefix and has right extension
     */
    static boolean isFullyWrittenDataFile(final String filePrefix, final Path path) {
        if (filePrefix == null) {
            return false;
        }
        final String fileName = path.getFileName().toString();
        return fileName.startsWith(filePrefix) && !fileName.contains("metadata") && fileName.endsWith(FILE_EXTENSION);
    }

    /**
     * print debug info showing if all links in index are still valid
     */
    static <D> void printDataLinkValidation(
            final String storeName,
            final LongList index,
            final Set<Integer> newFileIndexes,
            final List<DataFileReader> fileList,
            final KeyRange validKeyRange) {
        final SortedSet<Integer> validFileIds = new TreeSet<>();
        int newestFileIndex = 0;
        for (final DataFileReader file : fileList) {
            final int fileIndex = file.getMetadata().getIndex();
            validFileIds.add(fileIndex);
            if (fileIndex > newestFileIndex) {
                newestFileIndex = fileIndex;
            }
        }
        final int finalNewestFileIndex = newestFileIndex;

        final ConcurrentMap<Integer, Long> missingFileCounts = LongStream.range(
                        validKeyRange.getMinValidKey(), validKeyRange.getMaxValidKey() + 1)
                .parallel()
                .map(key -> index.get(key, NON_EXISTENT_DATA_LOCATION))
                // the index could've been modified while we were iterating over it, so non-existent data locations
                // possible
                .filter(location -> location != NON_EXISTENT_DATA_LOCATION)
                .filter(location -> {
                    final int fileIndex = DataFileCommon.fileIndexFromDataLocation(location);
                    return !(validFileIds.contains(fileIndex)
                            || fileIndex > finalNewestFileIndex
                            || newFileIndexes.contains(fileIndex));
                })
                .boxed()
                .collect(Collectors.groupingByConcurrent(
                        DataFileCommon::fileIndexFromDataLocation, Collectors.counting()));

        if (!missingFileCounts.isEmpty()) {
            logger.trace(
                    MERKLE_DB.getMarker(),
                    "{}:printDataLinkValidation index size={} numOfFiles={}, fileIndexes={}, newFileIndexes={}",
                    () -> storeName,
                    index::size,
                    fileList::size,
                    () -> Arrays.toString(
                            fileList.stream().mapToInt(IndexedObject::getIndex).toArray()),
                    () -> newFileIndexes);
            missingFileCounts.forEach((id, count) -> logger.trace(
                    MERKLE_DB.getMarker(), "{}:       missing file {} has {} references", storeName, id, count));
            logger.error(
                    EXCEPTION.getMarker(),
                    "{} has references to files {} that don't exists in the index. " + "Latest new files = {}",
                    storeName,
                    missingFileCounts.keySet().toString(),
                    newFileIndexes);
        }
    }

    /**
     * Get total size fo a collection of files.
     *
     * @param filePaths collection of paths to files
     * @return total number of bytes take for all the files in filePaths
     * @throws IOException If there was a problem getting file sizes
     */
    public static long getSizeOfFilesByPath(final Iterable<Path> filePaths) throws IOException {
        long totalSize = 0;
        for (final Path path : filePaths) {
            totalSize += Files.size(path);
        }
        return totalSize;
    }

    /**
     * Get total size fo a collection of files.
     *
     * @param fileReaders collection of paths to files
     * @return total number of bytes take for all the files in fileReaders
     */
    public static long getSizeOfFiles(final Iterable<? extends DataFileReader> fileReaders) {
        long totalSize = 0;
        for (final DataFileReader dataFileReader : fileReaders) {
            totalSize += dataFileReader.getSize();
        }
        return totalSize;
    }

    /**
     * Return a nice string for size of bytes.
     *
     * @param numOfBytes number of bytes
     * @return formatted string
     */
    public static String formatSizeBytes(final long numOfBytes) {
        if (numOfBytes <= KIBIBYTES_TO_BYTES) {
            return numOfBytes + " bytes";
        } else {
            return formatLargeDenomBytes(numOfBytes);
        }
    }

    private static String formatLargeDenomBytes(final long numOfBytes) {
        if (numOfBytes > GIBIBYTES_TO_BYTES) {
            final double numOfGb = numOfBytes / (double) GIBIBYTES_TO_BYTES;
            return roundTwoDecimals(numOfGb) + " GB";
        } else if (numOfBytes > MEBIBYTES_TO_BYTES) {
            final double numOfMb = numOfBytes / (double) MEBIBYTES_TO_BYTES;
            return roundTwoDecimals(numOfMb) + " MB";
        } else {
            final double numOfKb = numOfBytes / (double) KIBIBYTES_TO_BYTES;
            return roundTwoDecimals(numOfKb) + " KB";
        }
    }

    /**
     * Round a decimal to two decimal places
     *
     * @param d number to round
     * @return rounded number
     */
    public static double roundTwoDecimals(final double d) {
        return (double) Math.round(d * ROUNDING_SCALE_FACTOR) / ROUNDING_SCALE_FACTOR;
    }

    public static void logCompactStats(
            final String storeName,
            final double tookMillis,
            final Collection<? extends DataFileReader> filesToMerge,
            final long filesToMergeSize,
            final List<Path> mergedFiles,
            int targetCompactionLevel,
            final DataFileCollection fileCollection)
            throws IOException {
        final long mergedFilesCount = mergedFiles.size();
        final long mergedFilesSize = getSizeOfFilesByPath(mergedFiles);
        final double tookSeconds = tookMillis / 1000;
        String levelsCompacted = filesToMerge.stream()
                .map(v -> v.getMetadata().getCompactionLevel())
                .distinct()
                .map(v -> Integer.toString(v))
                .sorted()
                .collect(joining(","));

        Object[] fileToMergeIndexes = filesToMerge.stream()
                .map(reader -> reader.getMetadata().getIndex())
                .toArray();
        Object[] allFileIndexes = fileCollection.getAllCompletedFiles().stream()
                .map(reader -> reader.getMetadata().getIndex())
                .toArray();
        logger.info(
                MERKLE_DB.getMarker(),
                // Note that speed of read and write doesn't exactly map to the real read/write speed
                // because we consult in-memory index and skip some entries. Effective read/write speed
                // in this context means how much data files were covered by the compaction.
                """
                        [{}] Compacted {} file(s) / {} at level {} into {} file(s) of level {} / {} in {} second(s)
                                effectively read at {} effectively written at {},
                                compactedFiles[{}] = {},
                                filesToMerge[{}] = {}
                                allFilesAfter[{}] = {}""",
                storeName,
                filesToMerge.size(),
                formatSizeBytes(filesToMergeSize),
                levelsCompacted,
                mergedFilesCount,
                targetCompactionLevel,
                formatSizeBytes(mergedFilesSize),
                tookSeconds,
                formatSizeBytes((long) (filesToMergeSize / tookSeconds)) + "/sec",
                formatSizeBytes((long) (mergedFilesSize / tookSeconds)) + "/sec",
                mergedFilesCount,
                Arrays.toString(mergedFiles.stream().map(Path::getFileName).toArray()),
                fileToMergeIndexes.length,
                Arrays.toString(fileToMergeIndexes),
                allFileIndexes.length,
                Arrays.toString(allFileIndexes));
    }

    /**
     * Delete a directory and all its contents if it exists. Does nothing if directory does not
     * exist.
     *
     * @param dir The directory to delete
     */
    public static void deleteDirectoryAndContents(final Path dir) {
        if (Files.exists(dir) && Files.isDirectory(dir)) {
            try (Stream<Path> filesStream = Files.walk(dir)) {
                //noinspection ResultOfMethodCallIgnored
                filesStream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                Files.deleteIfExists(dir);
                logger.info(
                        MERKLE_DB.getMarker(),
                        "Deleted data directory [{}]",
                        dir.toFile().getAbsolutePath());
            } catch (Exception e) {
                logger.warn(
                        EXCEPTION.getMarker(),
                        "Failed to delete test directory [{}]",
                        dir.toFile().getAbsolutePath(),
                        e);
            }
        }
    }
}

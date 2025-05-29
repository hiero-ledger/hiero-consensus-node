// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.merkledb.KeyRange.INVALID_KEY_RANGE;
import static com.swirlds.merkledb.files.DataFileCommon.FILE_EXTENSION;
import static com.swirlds.merkledb.files.DataFileCommon.byteOffsetFromDataLocation;
import static com.swirlds.merkledb.files.DataFileCommon.fileIndexFromDataLocation;
import static com.swirlds.merkledb.files.DataFileCommon.isFullyWrittenDataFile;
import static com.swirlds.merkledb.files.DataFileCompactor.INITIAL_COMPACTION_LEVEL;
import static java.util.Collections.singletonList;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.merkledb.FileStatisticAware;
import com.swirlds.merkledb.KeyRange;
import com.swirlds.merkledb.Snapshotable;
import com.swirlds.merkledb.collections.CASableLongIndex;
import com.swirlds.merkledb.collections.ImmutableIndexedObjectList;
import com.swirlds.merkledb.collections.ImmutableIndexedObjectListUsingArray;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.config.MerkleDbConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * DataFileCollection manages a set of data files and the compaction of them over time. It stores
 * data items which are key,value pairs and returns a long representing the location it was stored.
 * You can then retrieve that data item later using the location you got when storing. There is not
 * understanding of what the keys mean and no way to look up data by key. The reason the keys are
 * separate from the values is so that we can merge data items with matching keys. We only keep the
 * newest data item for any matching key. It may look like a map, but it is not. You need an
 * external index outside this class to be able to store key-to-data location mappings.
 *
 * <p>The keys are assumed to be a contiguous block of long values. We do not have an explicit way
 * of deleting data, we depend on the range of valid keys. Any data items with keys outside the
 * current valid range will be deleted the next time they are merged. This works for our VirtualMap
 * use cases where the key is always a path and there is a valid range of path keys for internal and
 * leaf nodes. It allows very easy and efficient deleting without the need to maintain a list of
 * deleted keys.
 */
@SuppressWarnings({"unused", "unchecked"})
public class DataFileCollection implements FileStatisticAware, Snapshotable {

    private static final Logger logger = LogManager.getLogger(DataFileCollection.class);

    /**
     * Maximum number of data items that can be in a data file. This is dictated by the maximum size
     * of the movesMap used during merge, which in turn is limited by the maximum RAM to be used for
     * merging.
     */
    private static final int MOVE_LIST_CHUNK_SIZE = 500_000;
    /** The version number for format of current data files. */
    private static final int METADATA_FILE_FORMAT_VERSION = 1;
    /**
     * Metadata file name suffix. Full metadata file name is storeName + suffix. If legacy store
     * name is provided, and metadata file with the name above isn't found, metadata file with name
     * legacyStoreName + suffix is tried.
     */
    private static final String METADATA_FILENAME_SUFFIX = "_metadata.pbj";

    /** The number of times to retry index based reads */
    private static final int NUM_OF_READ_RETRIES = 5;

    /** File collection metadata fields */
    private static final FieldDefinition FIELD_FILECOLLECTION_MINVALIDKEY =
            new FieldDefinition("minValidKey", FieldType.UINT64, false, true, false, 1);

    private static final FieldDefinition FIELD_FILECOLLECTION_MAXVALIDKEY =
            new FieldDefinition("maxValidKey", FieldType.UINT64, false, true, false, 2);

    private final MerkleDbConfig dbConfig;

    /** The directory to store data files */
    private final Path storeDir;
    /**
     * Base name for the data files, allowing more than one DataFileCollection to share a directory
     */
    private final String storeName;
    /**
     * Another base name for the data files. If files with this base name exist, they are loaded by
     * this file collection at startup. New files will have storeName as the prefix, not
     * legacyStoreName *
     */
    private final String legacyStoreName;
    /** True if this DataFileCollection was loaded from an existing set of files */
    private final boolean loadedFromExistingFiles;
    /** The index to use for the next file we create */
    private final AtomicInteger nextFileIndex = new AtomicInteger();
    /** The range of valid data item keys for data currently stored by this data file collection. */
    private volatile KeyRange validKeyRange = INVALID_KEY_RANGE;

    /**
     * The list of current files in this data file collection. The files are added to this list
     * during flushes in {@link #endWriting()}, after the file is completely written. They
     * are also added during compaction in {@link DataFileCompactor#compactFiles(CASableLongIndex, List, int)}, even
     * before compaction is complete. In the end of compaction, all the compacted files are removed
     * from this list.
     *
     * <p>The list is used to read data items and to make snapshots. Reading from the file, which is
     * being written to during compaction, is possible because both readers and writers use Java
     * file channel APIs. Snapshots are an issue, though. Snapshots must be as fast as possible, so
     * they are implemented as to make hard links in the target folder to all data files in
     * collection. If compaction is in progress, the last file in the list isn't fully written yet,
     * so it can't be hard linked easily. To solve it, before a snapshot is taken, the current
     * compaction file is flushed to disk, and compaction is put on hold using {@link
     * DataFileCompactor#pauseCompaction()} and then resumed after snapshot is complete.
     */
    private final AtomicReference<ImmutableIndexedObjectList<DataFileReader>> dataFiles = new AtomicReference<>();

    /**
     * The current open file writer, if we are in the middle of writing a new file during flush, or
     * null if not writing.
     */
    private final AtomicReference<DataFileWriter> currentDataFileWriter = new AtomicReference<>();
    /**
     * Data file reader for the file, which is being written with the writer above, or null if not
     * writing. The reader is created right after writing to the file is started.
     */
    private final AtomicReference<DataFileReader> currentDataFileReader = new AtomicReference<>();
    /** Constructor for creating ImmutableIndexedObjectLists */
    private final Function<List<DataFileReader>, ImmutableIndexedObjectList<DataFileReader>>
            indexedObjectListConstructor;
    /**
     * Set if all indexes of new files currently being written. This is only maintained if logging
     * is trace level.
     */
    private final ConcurrentSkipListSet<Integer> setOfNewFileIndexes =
            logger.isTraceEnabled() ? new ConcurrentSkipListSet<>() : null;

    /**
     * Construct a new DataFileCollection.
     *
     * @param config MerkleDb config
     * @param storeDir The directory to store data files
     * @param storeName Base name for the data files, allowing more than one DataFileCollection to
     *     share a directory
     * @param loadedDataCallback Callback for rebuilding indexes from existing files, can be null if
     *     not needed. Using this is expensive as it requires all files to be read and parsed.
     * @throws IOException If there was a problem creating new data set or opening existing one
     */
    public DataFileCollection(
            final MerkleDbConfig config,
            final Path storeDir,
            final String storeName,
            final LoadedDataCallback loadedDataCallback)
            throws IOException {
        this(
                config,
                storeDir,
                storeName,
                null,
                loadedDataCallback,
                l -> new ImmutableIndexedObjectListUsingArray<>(DataFileReader[]::new, l));
    }

    /**
     * Construct a new DataFileCollection with a custom legacy store name. If data files and/or
     * metadata file exist with the legacy store name prefix, they will be processed by this file
     * collection. New data files will be written with {@code storeName} as the prefix.
     *
     * @param dbConfig MerkleDb dbConfig
     * @param storeDir The directory to store data files
     * @param storeName Base name for the data files, allowing more than one DataFileCollection to
     *     share a directory
     * @param legacyStoreName Base name for the data files. If not null, data files with this prefix
     *     are processed by this file collection at startup same way as files prefixed with
     *     storeName
     * @param loadedDataCallback Callback for rebuilding indexes from existing files, can be null if
     *     not needed. Using this is expensive as it requires all files to be read and parsed.
     * @throws IOException If there was a problem creating new data set or opening existing one
     */
    public DataFileCollection(
            final MerkleDbConfig dbConfig,
            final Path storeDir,
            final String storeName,
            final String legacyStoreName,
            final LoadedDataCallback loadedDataCallback)
            throws IOException {
        this(
                dbConfig,
                storeDir,
                storeName,
                legacyStoreName,
                loadedDataCallback,
                l -> new ImmutableIndexedObjectListUsingArray<>(DataFileReader[]::new, l));
    }

    /**
     * Construct a new DataFileCollection with custom legacy store name and indexed object list
     * constructor. If data files and/or metadata file exist with the legacy store name prefix, they
     * will be processed by this file collection. New data files will be written with {@code
     * storeName} as the prefix.
     *
     * @param dbConfig MerkleDb dbConfig
     * @param storeDir The directory to store data files
     * @param storeName Base name for the data files, allowing more than one DataFileCollection to
     *     share a directory
     * @param legacyStoreName Base name for the data files. If not null, data files with this prefix
     *     are processed by this file collection at startup same way as files prefixed with
     *     storeName
     * @param loadedDataCallback Callback for rebuilding indexes from existing files, can be null if
     *     not needed. Using this is expensive as it requires all files to be read and parsed.
     * @param indexedObjectListConstructor Constructor for creating ImmutableIndexedObjectList
     *     instances.
     * @throws IOException If there was a problem creating new data set or opening existing one
     */
    protected DataFileCollection(
            final MerkleDbConfig dbConfig,
            final Path storeDir,
            final String storeName,
            final String legacyStoreName,
            final LoadedDataCallback loadedDataCallback,
            final Function<List<DataFileReader>, ImmutableIndexedObjectList<DataFileReader>>
                    indexedObjectListConstructor)
            throws IOException {
        this.dbConfig = dbConfig;
        this.storeDir = storeDir;
        this.storeName = storeName;
        this.legacyStoreName = legacyStoreName;
        this.indexedObjectListConstructor = indexedObjectListConstructor;

        // check if exists, if so open existing files
        if (Files.exists(storeDir)) {
            loadedFromExistingFiles = tryLoadFromExistingStore(loadedDataCallback);
        } else {
            loadedFromExistingFiles = false;
            // create store dir
            Files.createDirectories(storeDir);
            // next file will have index zero
            nextFileIndex.set(0);
        }
    }

    /**
     * Get the valid range of keys for data items currently stored by this data file collection. Any
     * data items with keys below this can be deleted during a merge.
     *
     * @return valid key range
     */
    public KeyRange getValidKeyRange() {
        return validKeyRange;
    }

    /**
     * Get if this data file collection was loaded from an existing set of files or if it was a new
     * empty collection
     *
     * @return true if loaded from existing, false if new set of files
     */
    public boolean isLoadedFromExistingFiles() {
        return loadedFromExistingFiles;
    }

    /** Get the number of files in this DataFileCollection */
    public int getNumOfFiles() {
        return dataFiles.get().size();
    }

    /**
     * Get a list of all files in this collection that have been fully finished writing, are read
     * only and ready to be compacted.
     */
    public List<DataFileReader> getAllCompletedFiles() {
        final ImmutableIndexedObjectList<DataFileReader> activeIndexedFiles = dataFiles.get();
        if (activeIndexedFiles == null) {
            return Collections.emptyList();
        }
        return activeIndexedFiles.stream()
                .filter(DataFileReader::isFileCompleted)
                .toList();
    }

    /**
     * Get statistics for sizes of all files
     *
     * @return statistics for sizes of all fully written files, in bytes
     */
    @Override
    public LongSummaryStatistics getFilesSizeStatistics() {
        final ImmutableIndexedObjectList<DataFileReader> activeIndexedFiles = dataFiles.get();
        return activeIndexedFiles == null
                ? new LongSummaryStatistics()
                : activeIndexedFiles.stream()
                        .filter(DataFileReader::isFileCompleted)
                        .mapToLong(DataFileReader::getSize)
                        .summaryStatistics();
    }

    /** Close all the data files */
    public void close() throws IOException {
        // finish writing if we still are
        final DataFileWriter currentDataFileForWriting = currentDataFileWriter.getAndSet(null);
        if (currentDataFileForWriting != null) {
            currentDataFileForWriting.close();
        }
        // calling startSnapshot causes the metadata file to be written
        saveMetadata(storeDir);
        // close all files
        final ImmutableIndexedObjectList<DataFileReader> fileList = dataFiles.getAndSet(null);
        if (fileList != null) {
            for (final DataFileReader file : (Iterable<DataFileReader>) fileList.stream()::iterator) {
                file.close();
            }
        }
    }

    /**
     * Start writing a new data file
     *
     * @throws IOException If there was a problem opening a new data file
     */
    public void startWriting() throws IOException {
        final DataFileWriter activeDataFileWriter = currentDataFileWriter.get();
        if (activeDataFileWriter != null) {
            throw new IOException("Tried to start writing when we were already writing.");
        }
        final DataFileWriter writer = newDataFile(Instant.now(), INITIAL_COMPACTION_LEVEL);
        currentDataFileWriter.set(writer);
        final DataFileMetadata metadata = writer.getMetadata();
        final DataFileReader reader = addNewDataFileReader(writer.getPath(), metadata);
        currentDataFileReader.set(reader);
    }

    /**
     * Store a data item into the current file opened with startWriting().
     *
     * @param dataItem The data item to write into file
     * @return the location where data item was stored. This contains both the file and the location
     *     within the file.
     * @throws IOException If there was a problem writing this data item to the file.
     */
    public long storeDataItem(final BufferedData dataItem) throws IOException {
        final DataFileWriter currentDataFileForWriting = currentDataFileWriter.get();
        if (currentDataFileForWriting == null) {
            throw new IOException("Tried to put data " + dataItem + " when we never started writing.");
        }
        return currentDataFileForWriting.storeDataItem(dataItem);
    }

    /**
     * Store a data item into the current file opened with startWriting().
     *
     * @param dataItemWriter The data item to write into file
     * @param dataItemSize the data item size, in bytes
     * @return the location where data item was stored. This contains both the file and the location
     *     within the file.
     * @throws IOException If there was a problem writing this data item to the file.
     */
    public long storeDataItem(final Consumer<BufferedData> dataItemWriter, final int dataItemSize) throws IOException {
        final DataFileWriter currentDataFileForWriting = currentDataFileWriter.get();
        if (currentDataFileForWriting == null) {
            throw new IOException("Tried to put data " + dataItemWriter + " when we never started writing.");
        }
        return currentDataFileForWriting.storeDataItem(dataItemWriter, dataItemSize);
    }

    /**
     * End writing current data file and returns the corresponding reader. The current reader is marked
     * as completed (fully written, read only, and ready for compaction), so any indexes or other data structures that
     * are in-sync with the file content should be updated before calling this method.
     *
     * @return data file reader for the file written
     * @throws IOException If there was a problem closing the data file
     */
    public DataFileReader endWriting() throws IOException {
        final DataFileWriter dataWriter = currentDataFileWriter.getAndSet(null);
        if (dataWriter == null) {
            throw new IOException("Tried to end writing when we never started writing.");
        }
        // finish writing the file and write its footer
        dataWriter.close();
        final DataFileReader dataReader = currentDataFileReader.getAndSet(null);
        if (logger.isTraceEnabled()) {
            final DataFileMetadata metadata = dataReader.getMetadata();
            setOfNewFileIndexes.remove(metadata.getIndex());
        }
        dataReader.setFileCompleted();
        return dataReader;
    }

    /**
     * Sets the current key range for this file collection.
     *
     * @param minimumValidKey The minimum valid data key at this point in time, can be used for
     *     cleaning out old data
     * @param maximumValidKey The maximum valid data key at this point in time, can be used for
     *     cleaning out old data
     */
    public void updateValidKeyRange(final long minimumValidKey, final long maximumValidKey) {
        validKeyRange = new KeyRange(minimumValidKey, maximumValidKey);
    }

    /**
     * Gets the data file reader for a given data location. This method checks that a file with
     * the specified index exists in this file collection, and that the file is open. Note,
     * however, there is unavoidable race here, when the file reader is open while this method
     * is running, but closed immediately after the method is complete. This is why calls to
     * this method are wrapped into a retry loop in {@link #readDataItemUsingIndex}.
     */
    private DataFileReader readerForDataLocation(final long dataLocation) throws IOException {
        // check if found
        if (dataLocation == 0) {
            return null;
        }
        // split up location
        final int fileIndex = fileIndexFromDataLocation(dataLocation);
        // check if file for fileIndex exists
        DataFileReader file;
        final ImmutableIndexedObjectList<DataFileReader> currentIndexedFileList = dataFiles.get();
        if (fileIndex < 0 || currentIndexedFileList == null || (file = currentIndexedFileList.get(fileIndex)) == null) {
            throw new IOException("Got a data location from index for a file that doesn't exist. "
                    + "dataLocation="
                    + DataFileCommon.dataLocationToString(dataLocation)
                    + " fileIndex="
                    + fileIndex
                    + " validKeyRange="
                    + validKeyRange
                    + "\ncurrentIndexedFileList="
                    + currentIndexedFileList);
        }
        // Check that file is not closed
        if (file.isOpen()) {
            return file;
        } else {
            // Let's log this as it should happen very rarely but if we see it a lot then we should
            // have a rethink.
            logger.warn(
                    EXCEPTION.getMarker(), "Store [{}] DataFile was closed while trying to read from file", storeName);
            return null;
        }
    }

    /**
     * Read data item bytes at a given location (file index + offset). If the file is not found or
     * already closed, this method returns {@code null}. This may happen, if the index, where the data
     * location originated from, has just been updated in a parallel compaction thread.
     *
     * <p>NOTE: this method may not be used for data types, which can be of multiple different
     * versions. This is because there is no way for a caller to know the version of the returned
     * bytes.
     *
     * @param dataLocation Data item location, which combines file index and offset
     * @return Data item bytes if the data location was found in files
     *
     * @throws IOException If there was a problem reading the data item.
     * @throws ClosedChannelException In the very rare case merging closed the file between us
     *     checking if file is open and reading
     */
    public BufferedData readDataItem(final long dataLocation) throws IOException {
        final DataFileReader file = readerForDataLocation(dataLocation);
        return (file != null) ? file.readDataItem(dataLocation) : null;
    }

    /**
     * Read a data item from any file that has finished being written. Uses a LongList that maps
     * key-&gt;dataLocation, this allows for multiple retries going back to the index each time. The
     * allows us to cover the cracks where threads can slip though.
     *
     * <p>This depends on the fact that LongList has a nominal value of
     * LongList.IMPERMISSIBLE_VALUE=0 for non-existent values.
     *
     * @param index key-&gt;dataLocation index
     * @param keyIntoIndex The key to lookup in index
     * @return Data item if the data location was found in files. If contained in the index but not in files
     *     after a number of retries then an exception is thrown. A null is returned if not found in index
     *
     * @throws IOException If there was a problem reading the data item.
     */
    public BufferedData readDataItemUsingIndex(final LongList index, final long keyIntoIndex) throws IOException {
        // Try reading up to 5 times, 99.999% should work first try but there is a small chance the
        // file was closed by
        // merging when we are half way though reading, and we will see  file.isOpen() = false or a
        // ClosedChannelException. Doing a retry should get a different result because dataLocation
        // should be different
        // on the next try, because merging had a chance to update it to the new file.
        for (int retries = 0; retries < NUM_OF_READ_RETRIES; retries++) {
            // get from index
            final long dataLocation = index.get(keyIntoIndex, LongList.IMPERMISSIBLE_VALUE);
            // check if found
            if (dataLocation == LongList.IMPERMISSIBLE_VALUE) {
                return null;
            }
            // read data
            try {
                final BufferedData readData = readDataItem(dataLocation);
                // check we actually read data, this could be null if the file was closed half way
                // though us reading
                if (readData != null) {
                    return readData;
                }
            } catch (final IOException e) {
                // For up to 5 retries we ignore this exception because next retry should get a new
                // file location from
                // index. So should never hit a closed file twice.
                final int currentRetry = retries;
                // Log as much useful information that we can to help diagnose this problem before
                // throwing exception.
                logger.warn(
                        EXCEPTION.getMarker(),
                        () -> {
                            final String currentFiles = dataFiles.get() == null
                                    ? "UNKNOWN"
                                    : dataFiles.get().prettyPrintedIndices();

                            return "Store ["
                                    + storeName
                                    + "] had IOException while trying to read "
                                    + "key ["
                                    + keyIntoIndex
                                    + "] at "
                                    + "offset ["
                                    + byteOffsetFromDataLocation(dataLocation)
                                    + "] from "
                                    + "file ["
                                    + fileIndexFromDataLocation(dataLocation)
                                    + "] "
                                    + "on retry ["
                                    + currentRetry
                                    + "]. "
                                    + "Current files are ["
                                    + currentFiles
                                    + "]"
                                    + ", validKeyRange="
                                    + validKeyRange
                                    + ", storeDir=["
                                    + storeDir.toAbsolutePath()
                                    + "]";
                        },
                        e);
            }
        }
        throw new IOException("Read failed after 5 retries");
    }

    /** {@inheritDoc} */
    @Override
    public void snapshot(final Path snapshotDirectory) throws IOException {
        saveMetadata(snapshotDirectory);
        final List<DataFileReader> snapshotIndexedFiles = getAllCompletedFiles();
        for (final DataFileReader fileReader : snapshotIndexedFiles) {
            final Path existingFile = fileReader.getPath();
            Files.createLink(snapshotDirectory.resolve(existingFile.getFileName()), existingFile);
        }
    }

    /**
     * Get the set of new file indexes. This is only callable if trace logging is enabled.
     *
     * @return Direct access to set of all indexes of files that are currently being written
     */
    Set<Integer> getSetOfNewFileIndexes() {
        if (logger.isTraceEnabled()) {
            return setOfNewFileIndexes;
        } else {
            throw new IllegalStateException("getSetOfNewFileIndexes can only be called if trace logging is enabled");
        }
    }

    // =================================================================================================================
    // Index Callback Class

    /**
     * Simple callback class during reading an existing set of files during startup, so that indexes
     * can be built.
     */
    @FunctionalInterface
    public interface LoadedDataCallback {
        /** Add an index entry for the given data location and value */
        void newIndexEntry(long dataLocation, @NonNull BufferedData dataValue);
    }

    // =================================================================================================================
    // Private API

    /**
     * Used by tests to get data files for checking
     *
     * @param index data file index
     * @return the data file if one exists at that index
     */
    DataFileReader getDataFile(final int index) {
        final ImmutableIndexedObjectList<DataFileReader> fileList = dataFiles.get();
        return fileList == null ? null : fileList.get(index);
    }

    /**
     * Create and add a new data file reader to end of indexedFileList
     *
     * @param filePath the path for the new data file
     * @param metadata The metadata for the file at filePath, to save reading from file
     * @return The newly added DataFileReader.
     */
    DataFileReader addNewDataFileReader(final Path filePath, final DataFileMetadata metadata) throws IOException {
        final DataFileReader newDataFileReader = new DataFileReader(dbConfig, filePath, metadata);
        dataFiles.getAndUpdate(currentFileList -> {
            try {
                return (currentFileList == null)
                        ? indexedObjectListConstructor.apply(singletonList(newDataFileReader))
                        : currentFileList.withAddedObject(newDataFileReader);
            } catch (final IllegalArgumentException e) {
                logger.error(EXCEPTION.getMarker(), "Failed when updated the indexed files list", e);
                throw e;
            }
        });
        return newDataFileReader;
    }

    /**
     * Delete a list of files from indexedFileList and then from disk
     *
     * @param filesToDelete the list of files to delete
     * @throws IOException If there was a problem deleting the files
     */
    void deleteFiles(@NonNull final Collection<?> filesToDelete) throws IOException {
        // necessary workaround to remove compiler requirement for certain generic type which not required for deletion
        Collection<DataFileReader> files = (Collection<DataFileReader>) new HashSet<>(filesToDelete);
        // remove files from index
        dataFiles.getAndUpdate(
                currentFileList -> (currentFileList == null) ? null : currentFileList.withDeletedObjects(files));
        // now close and delete all the files
        for (final DataFileReader fileReader : files) {
            fileReader.close();
            Files.delete(fileReader.getPath());
        }
    }

    /**
     * Create a new data file writer
     *
     * @param creationTime The creation time for the data in the new file. It could be now or old in
     *     case of merge.
     * @return the newly created data file
     */
    DataFileWriter newDataFile(final Instant creationTime, int compactionLevel) throws IOException {
        final int newFileIndex = nextFileIndex.getAndIncrement();
        if (logger.isTraceEnabled()) {
            setOfNewFileIndexes.add(newFileIndex);
        }
        return new DataFileWriter(storeName, storeDir, newFileIndex, creationTime, compactionLevel);
    }

    /**
     * Saves the metadata to the given directory. A valid database must have the metadata.
     *
     * @param directory The location to save the metadata. The directory will be created (and all
     *     parent directories) if needed.
     * @throws IOException Thrown if a lower level IOException occurs.
     */
    private void saveMetadata(final Path directory) throws IOException {
        Files.createDirectories(directory);
        // write metadata, this will be incredibly fast, and we need to capture min and max key
        // while in save lock
        final KeyRange keyRange = validKeyRange;
        final Path metadataFile = directory.resolve(storeName + METADATA_FILENAME_SUFFIX);
        try (final OutputStream fileOut = Files.newOutputStream(metadataFile)) {
            final WritableSequentialData out = new WritableStreamingData(fileOut);
            if (keyRange.getMinValidKey() != 0) {
                ProtoWriterTools.writeTag(out, FIELD_FILECOLLECTION_MINVALIDKEY);
                out.writeVarLong(keyRange.getMinValidKey(), false);
            }
            if (keyRange.getMaxValidKey() != 0) {
                ProtoWriterTools.writeTag(out, FIELD_FILECOLLECTION_MAXVALIDKEY);
                out.writeVarLong(keyRange.getMaxValidKey(), false);
            }
            fileOut.flush();
        }
    }

    private boolean tryLoadFromExistingStore(final LoadedDataCallback loadedDataCallback) throws IOException {
        if (!Files.isDirectory(storeDir)) {
            throw new IOException("Tried to initialize DataFileCollection with a storage "
                    + "directory that is not a directory. ["
                    + storeDir.toAbsolutePath()
                    + "]");
        }
        try (final Stream<Path> storePaths = Files.list(storeDir)) {
            final Path[] fullWrittenFilePaths = storePaths
                    .filter(path ->
                            isFullyWrittenDataFile(storeName, path) || isFullyWrittenDataFile(legacyStoreName, path))
                    .toArray(Path[]::new);
            final DataFileReader[] dataFileReaders = new DataFileReader[fullWrittenFilePaths.length];
            try {
                for (int i = 0; i < fullWrittenFilePaths.length; i++) {
                    assert fullWrittenFilePaths[i].toString().endsWith(FILE_EXTENSION);
                    dataFileReaders[i] = new DataFileReader(dbConfig, fullWrittenFilePaths[i]);
                }
                // sort the readers into data file index order
                Arrays.sort(dataFileReaders);
            } catch (final IOException e) {
                // clean up any successfully created readers
                for (final DataFileReader dataFileReader : dataFileReaders) {
                    if (dataFileReader != null) {
                        dataFileReader.close();
                    }
                }
                // rethrow exception now that we have cleaned up
                throw e;
            }
            if (dataFileReaders.length > 0) {
                loadFromExistingFiles(dataFileReaders, loadedDataCallback);
                return true;
            } else {
                // next file will have index zero as we did not find any files even though the
                // directory existed
                nextFileIndex.set(0);
                return false;
            }
        }
    }

    private boolean loadMetadata() throws IOException {
        boolean loadedLegacyMetadata = false;
        Path metadataFile = storeDir.resolve(storeName + METADATA_FILENAME_SUFFIX);
        if (!Files.exists(metadataFile)) {
            metadataFile = storeDir.resolve(legacyStoreName + METADATA_FILENAME_SUFFIX);
            loadedLegacyMetadata = true;
        }
        if (!Files.exists(metadataFile)) {
            return false;
        }
        try (final ReadableStreamingData in = new ReadableStreamingData(metadataFile)) {
            long minValidKey = 0;
            long maxValidKey = 0;
            while (in.hasRemaining()) {
                final int tag = in.readVarInt(false);
                final int fieldNum = tag >> TAG_FIELD_OFFSET;
                if (fieldNum == FIELD_FILECOLLECTION_MINVALIDKEY.number()) {
                    minValidKey = in.readVarLong(false);
                } else if (fieldNum == FIELD_FILECOLLECTION_MAXVALIDKEY.number()) {
                    maxValidKey = in.readVarLong(false);
                } else {
                    throw new IllegalArgumentException("Unknown file collection metadata field: " + fieldNum);
                }
            }
            validKeyRange = new KeyRange(minValidKey, maxValidKey);
        }
        if (loadedLegacyMetadata) {
            Files.delete(metadataFile);
        }
        return true;
    }

    private void loadFromExistingFiles(
            final DataFileReader[] dataFileReaders, final LoadedDataCallback loadedDataCallback) throws IOException {
        logger.info(
                MERKLE_DB.getMarker(),
                "Loading existing set of [{}] data files for DataFileCollection [{}]",
                dataFileReaders.length,
                storeName);
        // read metadata
        if (!loadMetadata()) {
            logger.warn(
                    EXCEPTION.getMarker(),
                    "Loading existing set of data files but no metadata file was found in [{}]",
                    storeDir.toAbsolutePath());
        }
        // create indexed file list
        dataFiles.set(indexedObjectListConstructor.apply(List.of(dataFileReaders)));
        // work out what the next index would be, the highest current index plus one
        nextFileIndex.set(getMaxFileReaderIndex(dataFileReaders) + 1);
        // now call indexEntryCallback
        if (loadedDataCallback != null) {
            // now iterate over every file and every key
            for (final DataFileReader reader : dataFileReaders) {
                try (final DataFileIterator iterator = reader.createIterator()) {
                    while (iterator.next()) {
                        loadedDataCallback.newIndexEntry(
                                iterator.getDataItemDataLocation(), iterator.getDataItemData());
                    }
                }
            }
        }
        // Mark all files we loaded as being available for compactions
        for (final DataFileReader dataFileReader : dataFileReaders) {
            dataFileReader.setFileCompleted();
        }
        logger.info(
                MERKLE_DB.getMarker(), "Finished loading existing data files for DataFileCollection [{}]", storeName);
    }

    private int getMaxFileReaderIndex(final DataFileReader[] dataFileReaders) {
        return Stream.of(dataFileReaders)
                .mapToInt(DataFileReader::getIndex)
                .max()
                .orElse(-1);
    }
}

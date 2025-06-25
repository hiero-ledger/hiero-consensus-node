// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.FileStatisticAware;
import com.swirlds.merkledb.KeyRange;
import com.swirlds.merkledb.Snapshotable;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCollection.LoadedDataCallback;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LongSummaryStatistics;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A specialized map like disk based data store with long keys. It is assumed the keys are a single
 * sequential block of numbers that does not need to start at zero. The index from long key to disk
 * location for value is in RAM and the value data is stored in a set of files on disk.
 * <p>
 * There is an assumption that keys are a contiguous range of incrementing numbers. This allows
 * easy deletion during merging by accepting any key/value with a key outside this range is not
 * needed any more. This design comes from being used where keys are leaf paths in a binary tree.
 */
@SuppressWarnings({"DuplicatedCode"})
public class MemoryIndexDiskKeyValueStore implements AutoCloseable, Snapshotable, FileStatisticAware {

    private static final Logger logger = LogManager.getLogger(MemoryIndexDiskKeyValueStore.class);

    /**
     * Index mapping, it uses our key as the index within the list and the value is the dataLocation
     * in fileCollection where the key/value pair is stored.
     */
    private final LongList index;
    /** On disk set of DataFiles that contain our key/value pairs */
    final DataFileCollection fileCollection;

    /**
     * The name for the data store, this allows more than one data store in a single directory.
     * Also, useful for identifying what files are used by what part of the code.
     */
    private final String storeName;

    /**
     * Construct a new MemoryIndexDiskKeyValueStore
     *
     * @param storeDir The directory to store data files in
     * @param storeName The name for the data store, this allows more than one data store in a single directory.
     * @param legacyStoreName Base name for the data store. If not null, the store will process files with this prefix at startup. New files in the store will be prefixed with {@code storeName}
     * @param loadedDataCallback call back for handing loaded data from existing files on startup. Can be null if not needed.
     * @param keyToDiskLocationIndex The index to use for keys to disk locations. Having this passed in allows multiple MemoryIndexDiskKeyValueStore stores to share the same index if there
     * key ranges do not overlap. For example with internal node and leaf paths in a virtual map tree. It also lets the caller decide the LongList implementation to use. This does mean the caller is responsible for snapshot of the index.
     * @throws IOException If there was a problem opening data files
     */
    public MemoryIndexDiskKeyValueStore(
            final MerkleDbConfig config,
            final Path storeDir,
            final String storeName,
            final String legacyStoreName,
            final LoadedDataCallback loadedDataCallback,
            final LongList keyToDiskLocationIndex)
            throws IOException {
        this.storeName = storeName;
        index = keyToDiskLocationIndex;
        // create store dir
        Files.createDirectories(storeDir);
        // create file collection
        fileCollection = new DataFileCollection(config, storeDir, storeName, legacyStoreName, loadedDataCallback);
    }

    /**
     * Updates valid key range for this store. This method need to be called before we start putting
     * values into the index, otherwise we could put a value by index that is not yet valid.
     *
     * @param min min valid key, inclusive
     * @param max max valid key, inclusive
     */
    public void updateValidKeyRange(final long min, final long max) {
        // By calling `updateMinValidIndex` we compact the index if it's applicable.
        index.updateValidRange(min, max);
        // Data file collection key range is updated in endWriting()
    }

    /**
     * Start a writing session ready for calls to put(). Make sure to update the valid key range
     * using {@link #updateValidKeyRange(long, long)} before this method is called.
     *
     * @throws IOException If there was a problem opening a writing session
     */
    public void startWriting() throws IOException {
        fileCollection.startWriting();
    }

    /**
     * Put a value into this store, you must be in a writing session started with startWriting()
     *
     * @param key The key to store value for
     * @param dataItemWriter a function to write data item bytes
     * @param dataItemSize the data item size, in bytes
     * @throws IOException If there was a problem write key/value to the store
     */
    public void put(final long key, final Consumer<BufferedData> dataItemWriter, final int dataItemSize)
            throws IOException {
        final long dataLocation = fileCollection.storeDataItem(dataItemWriter, dataItemSize);
        // store data location in index
        index.put(key, dataLocation);
    }

    /**
     * End a session of writing
     *
     * @return Data file reader for the file written
     * @throws IOException If there was a problem closing the writing session
     */
    @Nullable
    public DataFileReader endWriting() throws IOException {
        final long currentMinValidKey = index.getMinValidIndex();
        final long currentMaxValidKey = index.getMaxValidIndex();
        fileCollection.updateValidKeyRange(currentMinValidKey, currentMaxValidKey);
        final DataFileReader dataFileReader = fileCollection.endWriting();
        logger.info(
                MERKLE_DB.getMarker(),
                "{} Ended writing, newFile={}, numOfFiles={}, minimumValidKey={}, maximumValidKey={}",
                storeName,
                dataFileReader.getIndex(),
                fileCollection.getNumOfFiles(),
                currentMinValidKey,
                currentMaxValidKey);
        return dataFileReader;
    }

    private boolean checkKeyInRange(final long key) {
        // Check if out of range
        final KeyRange keyRange = fileCollection.getValidKeyRange();
        if (!keyRange.withinRange(key)) {
            // Key 0 is the root node and always supported, but if it doesn't exist, just return
            // null,
            // even when no data has yet been written.
            if (key != 0) {
                logger.trace(MERKLE_DB.getMarker(), "Key [{}] is outside valid key range of {}", key, keyRange);
            }
            return false;
        }
        return true;
    }

    /**
     * Get a value by reading it from disk.
     *
     * @param key The key to find and read value for
     * @return Array of serialization version for data if the value was read or null if not found
     * @throws IOException If there was a problem reading the value from file
     */
    public BufferedData get(final long key) throws IOException {
        if (!checkKeyInRange(key)) {
            return null;
        }
        // read from files via index lookup
        return fileCollection.readDataItemUsingIndex(index, key);
    }

    /**
     * Close all files being used
     *
     * @throws IOException If there was a problem closing files
     */
    @Override
    public void close() throws IOException {
        fileCollection.close();
    }

    /** {@inheritDoc} */
    @Override
    public void snapshot(final Path snapshotDirectory) throws IOException {
        fileCollection.snapshot(snapshotDirectory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LongSummaryStatistics getFilesSizeStatistics() {
        return fileCollection.getFilesSizeStatistics();
    }

    public DataFileCollection getFileCollection() {
        return fileCollection;
    }
}

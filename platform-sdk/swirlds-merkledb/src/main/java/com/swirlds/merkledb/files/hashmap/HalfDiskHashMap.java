// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.concurrent.AbstractTask;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.FileStatisticAware;
import com.swirlds.merkledb.Snapshotable;
import com.swirlds.merkledb.collections.CASableLongIndex;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListDisk;
import com.swirlds.merkledb.collections.LongListOffHeap;
import com.swirlds.merkledb.collections.OffHeapUser;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileCollection.LoadedDataCallback;
import com.swirlds.merkledb.files.DataFileReader;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LongSummaryStatistics;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.collections.api.tuple.primitive.IntObjectPair;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

/**
 * This is a hash map implementation where the bucket index is in RAM and the buckets are on disk.
 * It maps a VirtualKey to a long value. This allows very large maps with minimal RAM usage and the
 * best performance profile as by using an in memory index we avoid the need for random disk writes.
 * Random disk writes are horrible performance wise in our testing.
 *
 * <p>This implementation depends on good hashCode() implementation on the keys, if there are too
 * many hash collisions the performance can get bad.
 *
 * <p><b>IMPORTANT: This implementation assumes a single writing thread. There can be multiple
 * readers while writing is happening.</b>
 */
public class HalfDiskHashMap implements AutoCloseable, Snapshotable, FileStatisticAware, OffHeapUser {

    private static final Logger logger = LogManager.getLogger(HalfDiskHashMap.class);

    /** The version number for format of current data files */
    private static final int METADATA_FILE_FORMAT_VERSION = 1;
    /** Metadata file name suffix with extension. */
    private static final String METADATA_FILENAME_SUFFIX = "_metadata.hdhm";
    /** Bucket index file name suffix with extension */
    private static final String BUCKET_INDEX_FILENAME_SUFFIX = "_bucket_index.ll";
    /**
     * A marker to indicate that a value should be deleted from the map, or that there is
     * no old value to compare against in putIfEqual/deleteIfEqual
     */
    protected static final long INVALID_VALUE = Long.MIN_VALUE;

    /**
     * This is the average number of entries per bucket we aim for when filled to mapSize. It is a
     * heuristic used in calculation for how many buckets to create. The larger this number the
     * slower lookups will be but the more even distribution of entries across buckets will be. So
     * it is a matter of balance.
     */
    private static final long GOOD_AVERAGE_BUCKET_ENTRY_COUNT = 32;

    /** The limit on the number of concurrent read tasks in {@code endWriting()} */
    private static final int MAX_IN_FLIGHT = 1024;

    /** Platform configuration */
    @NonNull
    private final MerkleDbConfig merkleDbConfig;

    /**
     * Long list used for mapping bucketIndex(index into list) to disk location for latest copy of
     * bucket
     */
    private final LongList bucketIndexToBucketLocation;
    /** DataFileCollection manages the files storing the buckets on disk */
    private final DataFileCollection fileCollection;

    /**
     * This is the next power of 2 bigger than minimumBuckets. It needs to be a power of two, so
     * that we can optimize and avoid the cost of doing a % to find the bucket index from hash code.
     */
    private final int numOfBuckets;
    /**
     * The requested max size for the map, this is the maximum number of key/values expected to be
     * stored in this map.
     */
    private final long mapSize;
    /** The name to use for the files prefix on disk */
    private final String storeName;

    /** Bucket pool used by this HDHM */
    private final ReusableBucketPool bucketPool;
    /** Store for session data during a writing transaction */
    private IntObjectHashMap<BucketMutation> oneTransactionsData = null;

    // Fields related to flushes

    /**
     * The thread that called startWriting. We use it to check that other writing calls are done on
     * same thread
     */
    private Thread writingThread;

    private final AtomicReference<SubmitTask> currentSubmitTask = new AtomicReference<>();

    /** Number of buckets updated during flush */
    private final AtomicInteger numBuckets = new AtomicInteger();

    /**
     * Number of bucket tasks that can be scheduled at the moment, i.e. MAX_IN_FLIGHT minus
     * the number of buckets currently being processed
     */
    private final AtomicInteger bucketPermits = new AtomicInteger(MAX_IN_FLIGHT);

    /**
     * The last created "store bucket" task, or null if no such tasks have been created yet in
     * the current flush. This reference is used to set store task dependencies to make sure
     * only a single bucket is written to disk at a time
     */
    private final AtomicReference<StoreBucketTask> lastStoreTask = new AtomicReference<>();

    /**
     * Number of "store bucket" tasks created so far in the current flush. This counter is
     * compared against {@link #numBuckets} to manage the first and the last "store bucket"
     * task dependencies
     */
    private final AtomicInteger storeBucketTasksCreated = new AtomicInteger();

    /**
     * A task used in {@link #endWriting()} to wait for all submitted tasks to complete. It
     * is set as a "next" dependency for the last created "store bucket" task.
     */
    private final AtomicReference<AbstractTask> notifyTaskRef = new AtomicReference<>();

    /** A holder for the first exception occurred during endWriting() tasks */
    private final AtomicReference<Throwable> exceptionOccurred = new AtomicReference<>();

    /** Fork-join pool for HDHM.endWriting() */
    private static volatile ForkJoinPool flushingPool = null;

    /**
     * This method is invoked from a non-static method and uses the provided configuration.
     * Consequently, the flushing pool will be initialized using the configuration provided
     * by the first instance of HalfDiskHashMap class that calls the relevant non-static method.
     * Subsequent calls will reuse the same pool, regardless of any new configurations provided.
     * </br>
     * FUTURE WORK: it can be moved to MerkleDb.
     */
    private static ForkJoinPool getFlushingPool(final @NonNull MerkleDbConfig merkleDbConfig) {
        requireNonNull(merkleDbConfig);

        ForkJoinPool pool = flushingPool;
        if (pool == null) {
            synchronized (HalfDiskHashMap.class) {
                pool = flushingPool;
                if (pool == null) {
                    final int hashingThreadCount = merkleDbConfig.getNumHalfDiskHashMapFlushThreads();
                    pool = new ForkJoinPool(hashingThreadCount);
                    flushingPool = pool;
                }
            }
        }
        return pool;
    }

    /**
     * Construct a new HalfDiskHashMap
     *
     * @param configuration                  Platform configuration.
     * @param mapSize                        The maximum map number of entries. This should be more than big enough to
     *                                       avoid too many key collisions.
     * @param storeDir                       The directory to use for storing data files.
     * @param storeName                      The name for the data store, this allows more than one data store in a
     *                                       single directory.
     * @param legacyStoreName                Base name for the data store. If not null, the store will process
     *                                       files with this prefix at startup. New files in the store will be prefixed with {@code
     *                                       storeName}
     * @param preferDiskBasedIndex           When true we will use disk based index rather than ram where
     *                                       possible. This will come with a significant performance cost, especially for writing. It
     *                                       is possible to load a data source that was written with memory index with disk based
     *                                       index and vice versa.
     * @throws IOException If there was a problem creating or opening a set of data files.
     */
    public HalfDiskHashMap(
            final @NonNull Configuration configuration,
            final long mapSize,
            final Path storeDir,
            final String storeName,
            final String legacyStoreName,
            final boolean preferDiskBasedIndex)
            throws IOException {
        requireNonNull(configuration);
        this.merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        this.mapSize = mapSize;
        this.storeName = storeName;
        Path indexFile = storeDir.resolve(storeName + BUCKET_INDEX_FILENAME_SUFFIX);
        // create bucket pool
        this.bucketPool = new ReusableBucketPool(Bucket::new);
        // load or create new
        LoadedDataCallback loadedDataCallback;
        if (Files.exists(storeDir)) {
            // load metadata
            Path metaDataFile = storeDir.resolve(storeName + METADATA_FILENAME_SUFFIX);
            boolean loadedLegacyMetadata = false;
            if (!Files.exists(metaDataFile)) {
                metaDataFile = storeDir.resolve(legacyStoreName + METADATA_FILENAME_SUFFIX);
                indexFile = storeDir.resolve(legacyStoreName + BUCKET_INDEX_FILENAME_SUFFIX);
                loadedLegacyMetadata = true;
            }
            if (Files.exists(metaDataFile)) {
                try (DataInputStream metaIn = new DataInputStream(Files.newInputStream(metaDataFile))) {
                    final int fileVersion = metaIn.readInt();
                    if (fileVersion != METADATA_FILE_FORMAT_VERSION) {
                        throw new IOException("Tried to read a file with incompatible file format version ["
                                + fileVersion
                                + "], expected ["
                                + METADATA_FILE_FORMAT_VERSION
                                + "].");
                    }
                    metaIn.readInt(); // backwards compatibility, was: minimumBuckets
                    numOfBuckets = metaIn.readInt();
                }
                if (loadedLegacyMetadata) {
                    Files.delete(metaDataFile);
                }
            } else {
                logger.error(
                        EXCEPTION.getMarker(),
                        "Loading existing set of data files but no metadata file was found in [{}]",
                        storeDir.toAbsolutePath());
                throw new IOException("Can not load an existing HalfDiskHashMap from ["
                        + storeDir.toAbsolutePath()
                        + "] because metadata file is missing");
            }
            // load or rebuild index
            final boolean forceIndexRebuilding = merkleDbConfig.indexRebuildingEnforced();
            if (Files.exists(indexFile) && !forceIndexRebuilding) {
                bucketIndexToBucketLocation = preferDiskBasedIndex
                        ? new LongListDisk(indexFile, numOfBuckets, configuration)
                        : new LongListOffHeap(indexFile, numOfBuckets, configuration);
                loadedDataCallback = null;
            } else {
                // create new index and setup call back to rebuild
                bucketIndexToBucketLocation = preferDiskBasedIndex
                        ? new LongListDisk(numOfBuckets, configuration)
                        : new LongListOffHeap(numOfBuckets, configuration);
                loadedDataCallback = (dataLocation, bucketData) -> {
                    final Bucket bucket = bucketPool.getBucket();
                    bucket.readFrom(bucketData);
                    bucketIndexToBucketLocation.put(bucket.getBucketIndex(), dataLocation);
                };
            }
        } else {
            // create store dir
            Files.createDirectories(storeDir);
            // calculate number of entries we can store in a disk page
            final int minimumBuckets = (int) (mapSize / GOOD_AVERAGE_BUCKET_ENTRY_COUNT);
            // numOfBuckets is the nearest power of two greater than minimumBuckets with a min of 2
            numOfBuckets = Math.max(Integer.highestOneBit(minimumBuckets) * 2, 2);
            // create new index
            bucketIndexToBucketLocation = preferDiskBasedIndex
                    ? new LongListDisk(numOfBuckets, configuration)
                    : new LongListOffHeap(numOfBuckets, configuration);
            // we are new so no need for a loadedDataCallback
            loadedDataCallback = null;
            // write metadata
            writeMetadata(storeDir);
            logger.info(
                    MERKLE_DB.getMarker(),
                    "HalfDiskHashMap [{}] created with minimumBuckets={} and numOfBuckets={}",
                    storeName,
                    minimumBuckets,
                    numOfBuckets);
        }
        bucketIndexToBucketLocation.updateValidRange(0, numOfBuckets - 1);
        // create file collection
        fileCollection = new DataFileCollection(
                // Need: propagate MerkleDb merkleDbConfig from the database
                merkleDbConfig, storeDir, storeName, legacyStoreName, loadedDataCallback);
    }

    private void writeMetadata(final Path dir) throws IOException {
        try (DataOutputStream metaOut =
                new DataOutputStream(Files.newOutputStream(dir.resolve(storeName + METADATA_FILENAME_SUFFIX)))) {
            metaOut.writeInt(METADATA_FILE_FORMAT_VERSION);
            metaOut.writeInt(0); // backwards compatibility, was: minimumBuckets
            metaOut.writeInt(numOfBuckets);
            metaOut.flush();
        }
    }

    /** {@inheritDoc} */
    public void snapshot(final Path snapshotDirectory) throws IOException {
        // create snapshot directory if needed
        Files.createDirectories(snapshotDirectory);
        // write index to file
        bucketIndexToBucketLocation.writeToFile(snapshotDirectory.resolve(storeName + BUCKET_INDEX_FILENAME_SUFFIX));
        // snapshot files
        fileCollection.snapshot(snapshotDirectory);
        // write metadata
        writeMetadata(snapshotDirectory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getOffHeapConsumption() {
        if (bucketIndexToBucketLocation instanceof LongListOffHeap offheapIndex) {
            return offheapIndex.getOffHeapConsumption();
        }
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public LongSummaryStatistics getFilesSizeStatistics() {
        return fileCollection.getAllCompletedFilesSizeStatistics();
    }

    /**
     * Close this HalfDiskHashMap's data files. Once closed this HalfDiskHashMap can not be reused.
     * You should make sure you call close before system exit otherwise any files being written
     * might not be in a good state.
     *
     * @throws IOException If there was a problem closing the data files.
     */
    @Override
    public void close() throws IOException {
        // Close the files first, then the index. If done in a different order, there may be
        // file operations still running, but the index is already closed
        fileCollection.close();
        bucketIndexToBucketLocation.close();
    }

    // =================================================================================================================
    // Writing API - Single thead safe

    /**
     * Start a writing session to the map. Each new writing session results in a new data file on
     * disk, so you should ideally batch up map writes.
     */
    public void startWriting() {
        oneTransactionsData = new IntObjectHashMap<>();
        writingThread = Thread.currentThread();
    }

    private BucketMutation findBucketForUpdate(
            final Bytes keyBytes, final int keyHashCode, final long oldValue, final long value) {
        if (keyBytes == null) {
            throw new IllegalArgumentException("Can not write a null key");
        }
        if (oneTransactionsData == null) {
            throw new IllegalStateException(
                    "Trying to write to a HalfDiskHashMap when you have not called startWriting().");
        }
        if (Thread.currentThread() != writingThread) {
            throw new IllegalStateException("Tried to write with different thread to startWriting()");
        }
        // store key and value in transaction cache
        final int bucketIndex = computeBucketIndex(keyHashCode);
        return oneTransactionsData.getIfAbsentPut(
                bucketIndex, () -> new BucketMutation(keyBytes, keyHashCode, oldValue, value));
    }

    /**
     * Put a key/value during the current writing session. The value will not be retrievable until
     * it is committed in the {@link #endWriting()} call.
     *
     * <p>This method may be called multiple times for the same key in a single writing
     * session. The value from the last call will be stored in this map after the session is
     * ended.
     *
     * @param keyBytes the key to store the value for
     * @param keyHashCode the key hash code
     * @param value the value to store for given key
     */
    public void put(final Bytes keyBytes, final int keyHashCode, final long value) {
        final BucketMutation bucketMap = findBucketForUpdate(keyBytes, keyHashCode, INVALID_VALUE, value);
        bucketMap.put(keyBytes, keyHashCode, value);
    }

    /**
     * Put a key/value during the current writing session. This method is similar to {@link
     * #put(Bytes, int, long)}, but the new value is set only if the current value is equal to
     * the given {@code oldValue}.
     *
     * <p>This method may be called multiple times for the same key in a single writing
     * session. If the new value from the first call is equal to the old value in the second
     * call, the new value from the second call will be stored in this map after the session
     * is ended, otherwise the value from the second call will be ignored.
     *
     * <p>If the value for {@code oldValue} is {@link #INVALID_VALUE}, it's ignored, and this
     * method is identical to {@link #put(Bytes, int, long)}.
     *
     * @param keyBytes the key to store the value for
     * @param keyHashCode the key hash code
     * @param oldValue the value to check the current value against, or {@link #INVALID_VALUE}
     *                 if no current value check is needed
     * @param value the value to store for the given key
     */
    public void putIfEqual(final Bytes keyBytes, final int keyHashCode, final long oldValue, final long value) {
        final BucketMutation bucketMap = findBucketForUpdate(keyBytes, keyHashCode, oldValue, value);
        bucketMap.putIfEqual(keyBytes, keyHashCode, oldValue, value);
    }

    /**
     * Delete a key entry from the map.
     *
     * @param keyBytes The key to delete entry for
     */
    public void delete(final Bytes keyBytes, final int keyHashCode) {
        put(keyBytes, keyHashCode, INVALID_VALUE);
    }

    /**
     * Delete a key entry from the map, if the current value is equal to the given {@code oldValue}.
     * If {@code oldValue} is {@link #INVALID_VALUE}, no current value check is performed, and this
     * method is identical to {@link #delete(Bytes, int)}.
     *
     * @param keyBytes the key to delete the entry for
     * @param oldValue the value to check the current value against, or {@link #INVALID_VALUE}
     *                 if no current value check is needed
     */
    public void deleteIfEqual(final Bytes keyBytes, final int keyHashCode, final long oldValue) {
        putIfEqual(keyBytes, keyHashCode, oldValue, INVALID_VALUE);
    }

    /**
     * Resets all the fields used in endWriting()
     */
    private void resetEndWriting(final ForkJoinPool pool, final int size) {
        exceptionOccurred.set(null);
        numBuckets.set(size);
        bucketPermits.set(MAX_IN_FLIGHT);
        lastStoreTask.set(null);
        storeBucketTasksCreated.set(0);
        notifyTaskRef.set(new NotifyTask(pool));
    }

    /**
     * End current writing session, committing all puts to data store.
     *
     * @return Data file reader for the file written
     * @throws IOException If there was a problem committing data to store
     */
    @Nullable
    public DataFileReader endWriting() throws IOException {
        if (Thread.currentThread() != writingThread) {
            throw new IllegalStateException("Tried calling endWriting with different thread to startWriting()");
        }
        final int size = oneTransactionsData.size();
        logger.info(
                MERKLE_DB.getMarker(),
                "Finishing writing to {}, num of changed bins = {}, num of changed keys = {}",
                storeName,
                size,
                oneTransactionsData.stream().mapToLong(BucketMutation::size).sum());
        final DataFileReader dataFileReader;
        try {
            if (size > 0) {
                final Iterator<IntObjectPair<BucketMutation>> it =
                        oneTransactionsData.keyValuesView().iterator();
                fileCollection.startWriting();
                final ForkJoinPool pool = getFlushingPool(merkleDbConfig);
                resetEndWriting(pool, size);
                // Create a task to submit bucket processing tasks. This initial submit task
                // is scheduled to run right away. Subsequent submit tasks will be run only
                // after some buckets are completely processed to make sure no more than
                // MAX_IN_FLIGHT buckets are handled in parallel (to limit resource usage)
                final SubmitTask submitTask = new SubmitTask(pool, it, 1);
                currentSubmitTask.set(submitTask);
                submitTask.send();
                // Wait until all tasks are completed by waiting for the notify task to join. This
                // task depends on the last "store bucket" task
                notifyTaskRef.get().join();
                if (exceptionOccurred.get() != null) {
                    throw new IOException(exceptionOccurred.get());
                }
                // close files session
                dataFileReader = fileCollection.endWriting(0, numOfBuckets);
                // we have updated all indexes so the data file can now be included in merges
                dataFileReader.setFileCompleted();
            } else {
                dataFileReader = null;
            }
        } catch (final Exception z) {
            throw new RuntimeException("Exception in HDHM.endWriting()", z);
        } finally {
            writingThread = null;
            oneTransactionsData = null;
        }
        return dataFileReader;
    }

    /**
     * A task to submit "read bucket" tasks. Tasks are submitted till the number of buckets
     * in progress exceeds MAX_IN_FLIGHT. After that, if there are still unprocessed buckets,
     * a new "submit task" is scheduled. This new task is run after at least one bucket is
     * fully processed, i.e. its "store" task is complete.
     */
    private class SubmitTask extends AbstractTask {

        private final Iterator<IntObjectPair<BucketMutation>> it;

        SubmitTask(final ForkJoinPool pool, final Iterator<IntObjectPair<BucketMutation>> it, final int depCount) {
            super(pool, depCount);
            this.it = it;
        }

        // Notifies that some bucket is fully processed. It sets one of this task's dependencies.
        // The other one is set in the end of the previous submit task
        void notifyBucketProcessed() {
            send();
        }

        @Override
        protected boolean onExecute() {
            // The next submit task to run after the current one. It will only be run, if
            // this task doesn't schedule tasks for all remaining buckets, and at least one
            // bucket is completely processed while this method is running
            final SubmitTask nextSubmitTask = new SubmitTask(getPool(), it, 2);
            final boolean newSubmitTaskSet = currentSubmitTask.compareAndSet(this, nextSubmitTask);
            assert newSubmitTaskSet;
            int maxToSubmit = bucketPermits.getAndSet(0);
            assert maxToSubmit > 0;
            while (it.hasNext() && (maxToSubmit-- > 0)) {
                final IntObjectPair<BucketMutation> keyValue = it.next();
                final int bucketIndex = keyValue.getOne();
                final BucketMutation bucketMap = keyValue.getTwo();
                // Create a "read bucket" task
                final ReadUpdateBucketTask readBucketTask = new ReadUpdateBucketTask(getPool(), bucketIndex, bucketMap);
                // Execute it right away
                readBucketTask.send();
            }
            if (it.hasNext()) {
                // There are more buckets to process. Let the next submit task run. One of the next task's
                // dependencies is set here, the other one is set in the end of StoreBucketTask
                nextSubmitTask.send();
            }
            return true;
        }
    }

    /**
     * A task to read a bucket identified by the given idex from disk and apply a list of
     * updates to the keys to it. The task has no dependencies, it's executed right after
     * creation.
     */
    private class ReadUpdateBucketTask extends AbstractTask {

        // Bucket index
        private final int bucketIndex;

        // List of updates to apply to the bucket
        private final BucketMutation keyUpdates;

        ReadUpdateBucketTask(final ForkJoinPool pool, final int bucketIndex, final BucketMutation keyUpdates) {
            super(pool, 0);
            this.bucketIndex = bucketIndex;
            this.keyUpdates = keyUpdates;
        }

        private void createAndScheduleStoreTask(final Bucket bucket) {
            // Create a subsequent "store bucket" task for the bucket
            final StoreBucketTask storeTask = new StoreBucketTask(getPool(), bucket);
            // The last created "store bucket" task. storeTask above will be set as an
            // output dependency for that task to make sure tasks are running only one at
            // a time. See StoreBucketTask for details
            final StoreBucketTask prevTask = lastStoreTask.getAndSet(storeTask);
            if (prevTask != null) {
                // This will trigger prevTask execution as soon as its prev task is complete
                prevTask.setNext(storeTask);
            } else {
                // The first task: no dependency on the prev task, can be executed rightaway
                storeTask.send();
            }
            if (storeBucketTasksCreated.incrementAndGet() == numBuckets.get()) {
                // The last task: no dependency on the next task, can be executed as soon as
                // its prev task is complete, no need to wait until the next task dependency
                // is set
                lastStoreTask.get().setNext(notifyTaskRef.get());
            }
        }

        @Override
        protected boolean onExecute() throws IOException {
            BufferedData bucketData = fileCollection.readDataItemUsingIndex(bucketIndexToBucketLocation, bucketIndex);
            // The bucket will be closed by StoreBucketTask
            final Bucket bucket = bucketPool.getBucket();
            if (bucketData == null) {
                // An empty bucket
                bucket.setBucketIndex(bucketIndex);
            } else {
                // Read from bytes
                bucket.readFrom(bucketData);
                if (bucketIndex != bucket.getBucketIndex()) {
                    logger.error(
                            MERKLE_DB.getMarker(),
                            "Bucket index integrity check " + bucketIndex + " != " + bucket.getBucketIndex());
                    /*
                       This is a workaround for the issue https://github.com/hiero-ledger/hiero-consensus-node/pull/18250,
                       which caused possible corruption in snapshots.
                       If the snapshot is corrupted, the code may read a bucket from the file, and the bucket index
                       may be different from the expected one. In this case, we clear the bucket (as it contains garbage
                       anyway) and set the correct index.
                    */
                    bucket.clear();
                    bucket.setBucketIndex(bucketIndex);
                }
            }
            // Apply all updates
            keyUpdates.forEachKeyValue(bucket::putValue);
            // Schedule a "store bucket" task for this bucket
            createAndScheduleStoreTask(bucket);
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            logger.error(MERKLE_DB.getMarker(), "Failed to read / update bucket " + bucketIndex, t);
            exceptionOccurred.set(t);
            // Make sure the writing thread is resumed
            notifyTaskRef.get().completeExceptionally(t);
        }
    }

    /**
     * A task to write an updated bucket (or remove it, if empty) to disk. Writing to disk
     * may not be done in parallel on multiple threads, this is why all these "store" tasks
     * are made sure to run one at a time. This is implemented by having two dependencies
     * for each task: one is set the next task, the other one is when the previous task is
     * complete. The very first created "store" task doesn't need to wait until its previous
     * task is complete, see storeTask.send() call above with the corresponding comment. The
     * very last task has its next task set to notifyTask, which is a special no-op task used
     * only to wait till all buckets are processed.
     */
    public class StoreBucketTask extends AbstractTask {

        private final Bucket bucket;

        private AbstractTask next;

        StoreBucketTask(final ForkJoinPool pool, final Bucket bucket) {
            super(pool, 2);
            this.bucket = bucket;
        }

        void setNext(final AbstractTask next) {
            this.next = next;
            send();
        }

        @Override
        protected boolean onExecute() throws IOException {
            try (bucket) {
                final int bucketIndex = bucket.getBucketIndex();
                if (bucket.isEmpty()) {
                    // bucket is missing or empty, remove it from the index
                    bucketIndexToBucketLocation.remove(bucketIndex);
                } else {
                    // save bucket
                    final long bucketLocation = fileCollection.storeDataItem(bucket::writeTo, bucket.sizeInBytes());
                    // update bucketIndexToBucketLocation
                    bucketIndexToBucketLocation.put(bucketIndex, bucketLocation);
                }
                next.send();
                return true;
            } finally {
                // Let the current submit task know that a bucket is fully processed, and
                // the task can be run
                if (bucketPermits.getAndIncrement() == 0) {
                    // If a submit task is currently running in parallel, it must have already created
                    // a new "current" submit task and permits have been set to 0, otherwise the
                    // getAndIncrement() above couldn't return 0. It means, notifyBucketProcessed()
                    // will be called on a different submit task than the one currently running
                    currentSubmitTask.get().notifyBucketProcessed();
                }
            }
        }

        @Override
        protected void onException(final Throwable t) {
            logger.error(MERKLE_DB.getMarker(), "Failed to write bucket " + bucket.getBucketIndex(), t);
            exceptionOccurred.set(t);
            // Make sure the writing thread is resumed
            notifyTaskRef.get().completeExceptionally(t);
        }
    }

    /**
     * A special no-op task used as the very last task in the sequence of "store" tasks.
     * This task is used in {@link #endWriting()} to wait till all buckets are fully
     * processed by calling join() on it.
     */
    private static class NotifyTask extends AbstractTask {

        NotifyTask(final ForkJoinPool pool) {
            super(pool, 1);
        }

        @Override
        protected boolean onExecute() {
            // Task body is empty: the task is only needed to wait until its dependency
            // tasks are complete
            return true;
        }
    }

    // =================================================================================================================
    // Reading API - Multi thead safe

    /**
     * Get a value from this map
     *
     * @param keyBytes the key to get value for
     * @param keyHashCode the key hash code
     * @param notFoundValue the value to return if the key was not found
     * @return the value retrieved from the map or {notFoundValue} if no value was stored for the
     *     given key
     * @throws IOException If there was a problem reading from the map
     */
    public long get(final Bytes keyBytes, final int keyHashCode, final long notFoundValue) throws IOException {
        if (keyBytes == null) {
            throw new IllegalArgumentException("Can not get a null key");
        }
        final int bucketIndex = computeBucketIndex(keyHashCode);
        try (final Bucket bucket = readBucket(bucketIndex)) {
            if (bucket != null) {
                return bucket.findValue(keyHashCode, keyBytes, notFoundValue);
            }
        }
        return notFoundValue;
    }

    private Bucket readBucket(final int bucketIndex) throws IOException {
        final BufferedData bucketData = fileCollection.readDataItemUsingIndex(bucketIndexToBucketLocation, bucketIndex);
        if (bucketData == null) {
            return null;
        }
        final Bucket bucket = bucketPool.getBucket();
        bucket.readFrom(bucketData);
        return bucket;
    }

    // =================================================================================================================
    // Debugging Print API

    /** Debug dump stats for this map */
    public void printStats() {
        logger.info(
                MERKLE_DB.getMarker(),
                """
                        HalfDiskHashMap Stats {
                        	mapSize = {}
                        	numOfBuckets = {}
                        	GOOD_AVERAGE_BUCKET_ENTRY_COUNT = {}
                        }""",
                mapSize,
                numOfBuckets,
                GOOD_AVERAGE_BUCKET_ENTRY_COUNT);
    }

    public DataFileCollection getFileCollection() {
        return fileCollection;
    }

    public CASableLongIndex getBucketIndexToBucketLocation() {
        return bucketIndexToBucketLocation;
    }

    // =================================================================================================================
    // Private API

    /**
     * Computes which bucket a key with the given hash falls. Depends on the fact the numOfBuckets
     * is a power of two. Based on same calculation that is used in java HashMap.
     *
     * @param keyHash the int hash for key
     * @return the index of the bucket that key falls in
     */
    private int computeBucketIndex(final int keyHash) {
        return (numOfBuckets - 1) & keyHash;
    }

    private record ReadBucketResult(Bucket bucket, Throwable error) {
        public ReadBucketResult {
            assert (bucket != null) ^ (error != null);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.merkledb.KeyRange.INVALID_KEY_RANGE;
import static com.swirlds.merkledb.MerkleDb.MERKLEDB_COMPONENT;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.base.units.UnitConstants;
import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.collections.HashListByteBuffer;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListDisk;
import com.swirlds.merkledb.collections.LongListOffHeap;
import com.swirlds.merkledb.collections.OffHeapUser;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCollection.LoadedDataCallback;
import com.swirlds.merkledb.files.DataFileCompactor;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.merkledb.files.MemoryIndexDiskKeyValueStore;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataOutputStream;

public final class MerkleDbDataSource implements VirtualDataSource {

    private static final Logger logger = LogManager.getLogger(MerkleDbDataSource.class);

    /** Count of open database instances */
    private static final LongAdder COUNT_OF_OPEN_DATABASES = new LongAdder();

    /** Data source metadata fields */
    private static final FieldDefinition FIELD_DSMETADATA_MINVALIDKEY =
            new FieldDefinition("minValidKey", FieldType.UINT64, false, true, false, 1);

    private static final FieldDefinition FIELD_DSMETADATA_MAXVALIDKEY =
            new FieldDefinition("maxValidKey", FieldType.UINT64, false, true, false, 2);

    /** Virtual database instance that hosts this data source. */
    private final MerkleDb database;

    /** Table name. Used as a subdir name in the database directory */
    private final String tableName;

    /** Table ID used by this data source in its database instance. */
    private final int tableId;

    /**
     * Table config, includes key and value serializers as well as a few other non-global params.
     */
    private final MerkleDbTableConfig tableConfig;

    /**
     * Indicates whether disk based indices are used for this data source.
     */
    private final boolean preferDiskBasedIndices;

    /**
     * In memory off-heap store for path to disk location, this is used for internal hashes store.
     */
    private final LongList pathToDiskLocationInternalNodes;

    /** In memory off-heap store for path to disk location, this is used by leave store. */
    private final LongList pathToDiskLocationLeafNodes;

    /**
     * In memory off-heap store for node hashes. This data is never stored on disk so on load from disk, this
     * will be empty. That should cause all internal node hashes to have to be computed on the first round
     * which will be expensive. Stores {@link Hash} objects as bytes.
     */
    private final HashListByteBuffer hashStoreRam;

    /**
     * On disk store for node hashes. Can be null if all hashes are being stored in ram by setting
     * tableConfig.hashesRamToDiskThreshold to Long.MAX_VALUE. Stores {@link VirtualHashRecord}
     * objects as bytes.
     */
    private final MemoryIndexDiskKeyValueStore hashStoreDisk;

    /** True when hashesRamToDiskThreshold is less than Long.MAX_VALUE */
    private final boolean hasDiskStoreForHashes;

    /** Mixed disk and off-heap memory store for key to path map */
    private final HalfDiskHashMap keyToPath;

    /**
     * Mixed disk and off-heap memory store for path to leaf key and value. Stores {@link
     * VirtualLeafBytes} objects as bytes.
     */
    private final MemoryIndexDiskKeyValueStore pathToKeyValue;

    /**
     * Cache size for reading virtual leaf records. Initialized in data source creation time from
     * MerkleDb settings. If the value is zero, leaf records cache isn't used.
     */
    private final int leafRecordCacheSize;

    /**
     * Virtual leaf records cache. It's a simple array indexed by leaf keys % cache size. Cache
     * eviction is not needed, as array size is fixed and can be configured in MerkleDb settings.
     * Index conflicts are resolved in a very straightforward way: whatever entry is read last, it's
     * put to the cache.
     */
    private final VirtualLeafBytes[] leafRecordCache;

    /** Thread pool storing internal records */
    private final ExecutorService storeHashesExecutor;

    /** Thread pool storing key-to-path mappings */
    private final ExecutorService storeLeavesExecutor;

    /** Thread pool creating snapshots, it is unbounded in threads, but we use at most 7 */
    private final ExecutorService snapshotExecutor;

    /** Flag for if a snapshot is in progress */
    private final AtomicBoolean snapshotInProgress = new AtomicBoolean(false);

    /** The range of valid leaf paths for data currently stored by this data source. */
    private volatile KeyRange validLeafPathRange = INVALID_KEY_RANGE;

    /** Paths to all database files and directories */
    private final MerkleDbPaths dbPaths;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Runs compactions for the storages of this data source */
    final MerkleDbCompactionCoordinator compactionCoordinator;

    private MerkleDbStatisticsUpdater statisticsUpdater;

    public MerkleDbDataSource(
            final MerkleDb database,
            final Configuration config,
            final String tableName,
            final int tableId,
            final MerkleDbTableConfig tableConfig,
            final boolean compactionEnabled,
            final boolean offlineUse)
            throws IOException {
        this.database = database;
        this.tableName = tableName;
        this.tableId = tableId;
        this.tableConfig = tableConfig;
        this.preferDiskBasedIndices = offlineUse;

        final MerkleDbConfig merkleDbConfig = config.getConfigData(MerkleDbConfig.class);

        // create thread group with label
        final ThreadGroup threadGroup = new ThreadGroup("MerkleDb-" + tableName);
        // create thread pool storing virtual node hashes
        storeHashesExecutor = Executors.newSingleThreadExecutor(new ThreadConfiguration(getStaticThreadManager())
                .setComponent(MERKLEDB_COMPONENT)
                .setThreadGroup(threadGroup)
                .setThreadName("Store hashes")
                .setExceptionHandler((t, ex) -> logger.error(
                        EXCEPTION.getMarker(), "[{}] Uncaught exception during storing hashes", tableName, ex))
                .buildFactory());
        // create thread pool storing virtual leaf nodes
        storeLeavesExecutor = Executors.newSingleThreadExecutor(new ThreadConfiguration(getStaticThreadManager())
                .setComponent(MERKLEDB_COMPONENT)
                .setThreadGroup(threadGroup)
                .setThreadName("Store leaves")
                .setExceptionHandler((t, ex) -> logger.error(
                        EXCEPTION.getMarker(), "[{}] Uncaught exception during storing leaves", tableName, ex))
                .buildFactory());
        // thread pool creating snapshots, it is unbounded in threads, but we use at most 7
        snapshotExecutor = Executors.newCachedThreadPool(new ThreadConfiguration(getStaticThreadManager())
                .setComponent(MERKLEDB_COMPONENT)
                .setThreadGroup(threadGroup)
                .setThreadName("Snapshot")
                .setExceptionHandler(
                        (t, ex) -> logger.error(EXCEPTION.getMarker(), "Uncaught exception during snapshots", ex))
                .buildFactory());

        final Path storageDir = database.getTableDir(tableName, tableId);
        dbPaths = new MerkleDbPaths(storageDir);

        // check if we are loading an existing database or creating a new one
        if (Files.exists(storageDir)) {
            // read metadata
            if (!loadMetadata(dbPaths)) {
                logger.info(
                        MERKLE_DB.getMarker(),
                        "[{}] Loading existing set of data files but no metadata file was found in" + " [{}]",
                        tableName,
                        storageDir.toAbsolutePath());
                throw new IOException("Can not load an existing MerkleDbDataSource from ["
                        + storageDir.toAbsolutePath()
                        + "] because metadata file is missing");
            }
        } else {
            Files.createDirectories(storageDir);
        }
        saveMetadata(dbPaths);

        // Max number of entities that can be stored
        final long virtualSize = tableConfig.getMaxNumberOfKeys();
        // Path to hash and path to KV index capacity. Last leaf path is 2*virtualSize - 2,
        // so capacity should be 2*virtualSize - 1, but only if virtualSize is greater than 1.
        // To support virtual sizes 0 and 1, let's set capacity to 2*virtualSize
        final long pathIndexCapacity = virtualSize * 2;

        final boolean forceIndexRebuilding = merkleDbConfig.indexRebuildingEnforced();
        // Path to disk location index, hashes
        final Path pathToHashLocationFile = dbPaths.pathToDiskLocationInternalNodesFile;
        if (Files.exists(pathToHashLocationFile) && !forceIndexRebuilding) {
            pathToDiskLocationInternalNodes = preferDiskBasedIndices
                    ? new LongListDisk(pathToHashLocationFile, pathIndexCapacity, config)
                    : new LongListOffHeap(pathToHashLocationFile, pathIndexCapacity, config);
        } else {
            pathToDiskLocationInternalNodes = preferDiskBasedIndices
                    ? new LongListDisk(pathIndexCapacity, config)
                    : new LongListOffHeap(pathIndexCapacity, config);
        }
        // Path to disk location index, leaf nodes
        final Path pathToLeafLocationFile = dbPaths.pathToDiskLocationLeafNodesFile;
        if (Files.exists(pathToLeafLocationFile) && !forceIndexRebuilding) {
            pathToDiskLocationLeafNodes = preferDiskBasedIndices
                    ? new LongListDisk(pathToLeafLocationFile, pathIndexCapacity, config)
                    : new LongListOffHeap(pathToLeafLocationFile, pathIndexCapacity, config);
        } else {
            pathToDiskLocationLeafNodes = preferDiskBasedIndices
                    ? new LongListDisk(pathIndexCapacity, config)
                    : new LongListOffHeap(pathIndexCapacity, config);
        }

        // Hashes store, RAM
        final long hashesRamToDiskThreshold = tableConfig.getHashesRamToDiskThreshold();
        if (hashesRamToDiskThreshold > 0) {
            if (Files.exists(dbPaths.hashStoreRamFile)) {
                hashStoreRam = new HashListByteBuffer(dbPaths.hashStoreRamFile, hashesRamToDiskThreshold, config);
            } else {
                hashStoreRam = new HashListByteBuffer(hashesRamToDiskThreshold, config);
            }
        } else {
            hashStoreRam = null;
        }

        // Hashes store, on disk (paths to hashes)
        final String hashStoreDiskStoreName = tableName + "_internalhashes";
        hasDiskStoreForHashes = tableConfig.getHashesRamToDiskThreshold() < Long.MAX_VALUE;
        if (hasDiskStoreForHashes) {
            final boolean needRestorePathToDiskLocationInternalNodes = pathToDiskLocationInternalNodes.size() == 0;
            final LoadedDataCallback hashRecordLoadedCallback;
            if (needRestorePathToDiskLocationInternalNodes) {
                if (validLeafPathRange.getMaxValidKey() >= 0) {
                    pathToDiskLocationInternalNodes.updateValidRange(0, validLeafPathRange.getMaxValidKey());
                }
                hashRecordLoadedCallback = (dataLocation, hashData) -> {
                    final VirtualHashRecord hashRecord = VirtualHashRecord.parseFrom(hashData);
                    final long path = hashRecord.path();
                    // Old data files may contain entries with paths outside the current virtual node range
                    if (path <= validLeafPathRange.getMaxValidKey()) {
                        pathToDiskLocationInternalNodes.put(path, dataLocation);
                    }
                };
            } else {
                hashRecordLoadedCallback = null;
            }
            hashStoreDisk = new MemoryIndexDiskKeyValueStore(
                    merkleDbConfig,
                    dbPaths.hashStoreDiskDirectory,
                    hashStoreDiskStoreName,
                    tableName + ":internalHashes",
                    hashRecordLoadedCallback,
                    pathToDiskLocationInternalNodes);
        } else {
            hashStoreDisk = null;
        }

        // Leaves store (path to KV)
        final LoadedDataCallback leafRecordLoadedCallback;
        final boolean needRestorePathToDiskLocationLeafNodes =
                (pathToDiskLocationLeafNodes.size() == 0) && (validLeafPathRange.getMinValidKey() > 0);
        if (needRestorePathToDiskLocationLeafNodes) {
            if (validLeafPathRange.getMaxValidKey() >= 0) {
                pathToDiskLocationLeafNodes.updateValidRange(
                        validLeafPathRange.getMinValidKey(), validLeafPathRange.getMaxValidKey());
            }
            leafRecordLoadedCallback = (dataLocation, leafData) -> {
                final VirtualLeafBytes leafBytes = VirtualLeafBytes.parseFrom(leafData);
                final long path = leafBytes.path();
                // Old data files may contain entries with paths outside the current leaf range
                if (validLeafPathRange.withinRange(path)) {
                    pathToDiskLocationLeafNodes.put(path, dataLocation);
                }
            };
        } else {
            leafRecordLoadedCallback = null;
        }
        final String pathToKeyValueStoreName = tableName + "_pathtohashkeyvalue";
        pathToKeyValue = new MemoryIndexDiskKeyValueStore(
                merkleDbConfig,
                dbPaths.pathToKeyValueDirectory,
                pathToKeyValueStoreName,
                tableName + ":pathToHashKeyValue",
                leafRecordLoadedCallback,
                pathToDiskLocationLeafNodes);

        // Keys (keys to paths)
        String keyToPathStoreName = tableName + "_objectkeytopath";
        keyToPath = new HalfDiskHashMap(
                config,
                tableConfig.getMaxNumberOfKeys(),
                dbPaths.keyToPathDirectory,
                keyToPathStoreName,
                tableName + ":objectKeyToPath",
                preferDiskBasedIndices);
        keyToPath.printStats();
        // Repair keyToPath based on pathToKeyValue data, if requested and not offlineUse
        if (!offlineUse) {
            final String tablesToRepairHdhmConfig = merkleDbConfig.tablesToRepairHdhm();
            if (tablesToRepairHdhmConfig != null) {
                final String[] tableNames = tablesToRepairHdhmConfig.split(",");
                if (Arrays.stream(tableNames).filter(s -> !s.isBlank()).anyMatch(tableName::equals)) {
                    keyToPath.repair(getFirstLeafPath(), getLastLeafPath(), pathToKeyValue);
                }
            }
        }

        // Leaf records cache
        leafRecordCacheSize = merkleDbConfig.leafRecordCacheSize();
        leafRecordCache = (leafRecordCacheSize > 0) ? new VirtualLeafBytes[leafRecordCacheSize] : null;

        // Stats
        statisticsUpdater = new MerkleDbStatisticsUpdater(merkleDbConfig, tableName);

        // File compactions
        compactionCoordinator = new MerkleDbCompactionCoordinator(tableName, merkleDbConfig);
        if (compactionEnabled) {
            enableBackgroundCompaction();
        }

        // Update count of open databases
        COUNT_OF_OPEN_DATABASES.increment();

        logger.info(
                MERKLE_DB.getMarker(),
                "Created MerkleDB [{}] with store path '{}', maxNumKeys = {}, hash RAM/disk cutoff" + " = {}",
                tableName,
                storageDir,
                tableConfig.getMaxNumberOfKeys(),
                tableConfig.getHashesRamToDiskThreshold());
    }

    /**
     * Enables background compaction process.
     */
    @Override
    public void enableBackgroundCompaction() {
        compactionCoordinator.enableBackgroundCompaction();
    }

    /** Stop background compaction process, interrupting the current compaction if one is happening.
     * This will not corrupt the database but will leave files around.*/
    @Override
    public void stopAndDisableBackgroundCompaction() {
        compactionCoordinator.stopAndDisableBackgroundCompaction();
    }

    /**
     * Get the count of open database instances. This is databases that have been opened but not yet
     * closed.
     *
     * @return Count of open databases.
     */
    public static long getCountOfOpenDatabases() {
        return COUNT_OF_OPEN_DATABASES.sum();
    }

    /** Get the most recent first leaf path */
    public long getFirstLeafPath() {
        return validLeafPathRange.getMinValidKey();
    }

    /** Get the most recent last leaf path */
    public long getLastLeafPath() {
        return validLeafPathRange.getMaxValidKey();
    }

    /**
     * Pauses compaction of all data file collections used by this data source. It may not stop compaction
     * immediately, but as soon as compaction process needs to update data source state, which is
     * critical for snapshots (e.g. update an index), it will be stopped until {@link
     * #resumeCompaction()}} is called.
     */
    void pauseCompaction() throws IOException {
        compactionCoordinator.pauseCompaction();
    }

    /** Resumes previously stopped data file collection merging. */
    void resumeCompaction() throws IOException {
        compactionCoordinator.resumeCompaction();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Deprecated
    @SuppressWarnings("rawtypes")
    public KeySerializer getKeySerializer() {
        return tableConfig.getKeySerializer();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Deprecated
    @SuppressWarnings("rawtypes")
    public ValueSerializer getValueSerializer() {
        return tableConfig.getValueSerializer();
    }

    /**
     * Save a batch of data to data store.
     * <p>
     * If you call this method where not all data is provided to cover the change in
     * firstLeafPath and lastLeafPath, then any reads after this call may return rubbish or throw
     * obscure exceptions for any internals or leaves that have not been written. For example, if
     * you were to grow the tree by more than 2x, and then called this method in batches, be aware
     * that if you were to query for some record between batches that hadn't yet been saved, you
     * will encounter problems.
     *
     * @param firstLeafPath the tree path for first leaf
     * @param lastLeafPath the tree path for last leaf
     * @param hashRecordsToUpdate stream of records with hashes to update, it is assumed this is sorted by
     *     path and each path only appears once.
     * @param leafRecordsToAddOrUpdate stream of new leaf nodes and updated leaf nodes
     * @param leafRecordsToDelete stream of new leaf nodes to delete, The leaf record's key and path
     *     have to be populated, all other data can be null.
     * @param isReconnectContext if true, the method called in the context of reconnect
     * @throws IOException If there was a problem saving changes to data source
     */
    @Override
    public void saveRecords(
            final long firstLeafPath,
            final long lastLeafPath,
            @NonNull final Stream<VirtualHashRecord> hashRecordsToUpdate,
            @NonNull final Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
            @NonNull final Stream<VirtualLeafBytes> leafRecordsToDelete,
            final boolean isReconnectContext)
            throws IOException {
        try {
            validLeafPathRange = new KeyRange(firstLeafPath, lastLeafPath);
            final CountDownLatch countDownLatch = new CountDownLatch(lastLeafPath > 0 ? 2 : 1);

            if (lastLeafPath > 0) {
                // Use an executor to make sure the data source is not closed in parallel. See
                // the comment in close() for details
                storeHashesExecutor.execute(() -> {
                    try {
                        writeHashes(lastLeafPath, hashRecordsToUpdate);
                    } catch (final IOException e) {
                        logger.error(EXCEPTION.getMarker(), "[{}] Failed to store hashes", tableName, e);
                        throw new UncheckedIOException(e);
                    } finally {
                        countDownLatch.countDown();
                    }
                });
            }

            // Use an executor to make sure the data source is not closed in parallel. See
            // the comment in close() for details
            storeLeavesExecutor.execute(() -> {
                try {
                    // we might as well do this in the archive thread rather than leaving it waiting
                    writeLeavesToPathToKeyValue(
                            firstLeafPath,
                            lastLeafPath,
                            leafRecordsToAddOrUpdate,
                            leafRecordsToDelete,
                            isReconnectContext);
                } catch (final IOException e) {
                    logger.error(EXCEPTION.getMarker(), "[{}] Failed to store leaves", tableName, e);
                    throw new UncheckedIOException(e);
                } finally {
                    countDownLatch.countDown();
                }
            });

            // wait for the other threads in the rare case they are not finished yet. We need to
            // have all writing
            // done before we return as when we return the state version we are writing is deleted
            // from the cache and
            // the flood gates are opened for reads through to the data we have written here.
            try {
                countDownLatch.await();
            } catch (final InterruptedException e) {
                logger.warn(
                        EXCEPTION.getMarker(),
                        "[{}] Interrupted while waiting on internal record storage",
                        tableName,
                        e);
                Thread.currentThread().interrupt();
            }
        } finally {
            // Report total size on disk as sum of all store files. All metadata and other helper files
            // are considered small enough to be ignored. If/when we decide to use on-disk long lists
            // for indices, they should be added here
            statisticsUpdater.updateStoreFileStats(this);
            // update off-heap stats
            statisticsUpdater.updateOffHeapStats(this);
        }
    }

    /**
     * Load a leaf record by key.
     *
     * @param keyBytes they to the leaf to load record for
     * @return loaded record or null if not found
     * @throws IOException If there was a problem reading record from db
     */
    @Nullable
    @Override
    public VirtualLeafBytes loadLeafRecord(final Bytes keyBytes, final int keyHashCode) throws IOException {
        requireNonNull(keyBytes);

        final long path;
        VirtualLeafBytes cached = null;
        int cacheIndex = -1;
        if (leafRecordCache != null) {
            cacheIndex = Math.abs(keyHashCode % leafRecordCacheSize);
            // No synchronization is needed here. Java guarantees (JLS 17.7) that reference writes
            // are atomic, so we will never get corrupted objects from the array. The object may
            // be overwritten in the cache in a different thread in parallel, but it isn't a
            // problem as cached entry key is checked below anyway
            cached = leafRecordCache[cacheIndex];
        }
        // If an entry is found in the cache, and entry key is the one requested
        if ((cached != null) && keyBytes.equals(cached.keyBytes())) {
            // Some cache entries contain just key and path, but no value. If the value is there,
            // just return the cached entry. If not, at least make use of the path
            if (cached.valueBytes() != null) {
                return cached;
            }
            // Note that the path may be INVALID_PATH here, this is perfectly legal
            path = cached.path();
        } else {
            // Cache miss
            cached = null;
            statisticsUpdater.countLeafKeyReads();
            path = keyToPath.get(keyBytes, keyHashCode, INVALID_PATH);
        }

        // If the key didn't map to anything, we just return null
        if (path == INVALID_PATH) {
            // Cache the result if not already cached
            if (leafRecordCache != null && cached == null) {
                leafRecordCache[cacheIndex] = new VirtualLeafBytes(path, keyBytes, 0, null);
            }
            return null;
        }

        // If the key returns a value from the map, but it lies outside the first/last
        // leaf path, then return null. This can happen if the map contains old keys
        // that haven't been removed.
        if (!validLeafPathRange.withinRange(path)) {
            return null;
        }

        statisticsUpdater.countLeafReads();
        // Go ahead and lookup the value.
        VirtualLeafBytes leafBytes = VirtualLeafBytes.parseFrom(pathToKeyValue.get(path));
        assert leafBytes != null && leafBytes.keyBytes().equals(keyBytes);

        if (leafRecordCache != null) {
            // No synchronization is needed here, see the comment above
            leafRecordCache[cacheIndex] = leafBytes;
        }

        return leafBytes;
    }

    /**
     * Load a leaf record by path. This method returns {@code null}, if the path is outside the
     * valid path range.
     *
     * @param path the path for the leaf we are loading
     * @return loaded record or null if not found
     * @throws IOException If there was a problem reading record from db
     */
    @Nullable
    @Override
    public VirtualLeafBytes loadLeafRecord(final long path) throws IOException {
        if (path < 0) {
            throw new IllegalArgumentException("Path (" + path + ") is not valid");
        }
        final KeyRange leafPathRange = validLeafPathRange;
        if (!leafPathRange.withinRange(path)) {
            return null;
        }
        statisticsUpdater.countLeafReads();
        return VirtualLeafBytes.parseFrom(pathToKeyValue.get(path));
    }

    /**
     * Find the path of the given key.
     *
     * @param keyBytes the key for a path
     * @return the path or INVALID_PATH if not stored
     * @throws IOException If there was a problem locating the key
     */
    @Override
    public long findKey(final Bytes keyBytes, final int keyHashCode) throws IOException {
        requireNonNull(keyBytes);

        // Check the cache first
        int cacheIndex = -1;
        if (leafRecordCache != null) {
            cacheIndex = Math.abs(keyHashCode % leafRecordCacheSize);
            // No synchronization is needed here. See the comment in loadLeafRecord(key) above
            final VirtualLeafBytes cached = leafRecordCache[cacheIndex];
            if (cached != null && keyBytes.equals(cached.keyBytes())) {
                // Cached path may be a valid path or INVALID_PATH, both are legal here
                return cached.path();
            }
        }

        statisticsUpdater.countLeafKeyReads();
        final long path = keyToPath.get(keyBytes, keyHashCode, INVALID_PATH);

        if (leafRecordCache != null) {
            // Path may be INVALID_PATH here. Still needs to be cached (negative result)
            leafRecordCache[cacheIndex] = new VirtualLeafBytes(path, keyBytes, keyHashCode, null);
        }

        return path;
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Hash loadHash(final long path) throws IOException {
        if (path < 0) {
            throw new IllegalArgumentException("Path (" + path + ") is not valid");
        }

        // It is possible that the caller will ask for an internal node that the database doesn't
        // know about. This can happen if some leaves have been added to the tree, but we haven't
        // hashed yet, so the cache doesn't have any internal records for it, and somebody
        // tries to iterate over the nodes in the tree.
        long lastLeaf = validLeafPathRange.getMaxValidKey();
        if (path > lastLeaf) {
            return null;
        }

        final Hash hash;
        if (path < tableConfig.getHashesRamToDiskThreshold()) {
            hash = hashStoreRam.get(path);
            // Should count hash reads here, too?
        } else {
            final VirtualHashRecord rec = VirtualHashRecord.parseFrom(hashStoreDisk.get(path));
            hash = (rec != null) ? rec.hash() : null;
            statisticsUpdater.countHashReads();
        }

        return hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean loadAndWriteHash(final long path, final SerializableDataOutputStream out) throws IOException {
        if (path < 0) {
            throw new IllegalArgumentException("path is less than 0");
        }
        long lastLeaf = validLeafPathRange.getMaxValidKey();
        if (path > lastLeaf) {
            return false;
        }
        // This method must write hashes in the same binary format as Hash.(de)serialize(). If a
        // hash comes from hashStoreRam, it's enough to just serialize it to the output stream.
        // However, if a hash is stored in the files as a VirtualHashRecord, its bytes are
        // slightly different, so additional processing is required
        if (path < tableConfig.getHashesRamToDiskThreshold()) {
            final Hash hash = hashStoreRam.get(path);
            if (hash == null) {
                return false;
            }
            hash.serialize(out);
        } else {
            final BufferedData hashBytes = hashStoreDisk.get(path);
            if (hashBytes == null) {
                return false;
            }
            // Hash.serialize() format is: digest ID (4 bytes) + size (4 bytes) + hash (48 bytes)
            VirtualHashRecord.extractAndWriteHashBytes(hashBytes, out);
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close(final boolean keepData) throws IOException {
        if (!closed.getAndSet(true)) {
            try {
                // Stop merging and shutdown the datasource compactor
                compactionCoordinator.stopAndDisableBackgroundCompaction();
                // Shut down all executors. If a flush is currently in progress, it will be interrupted.
                // It's critical to make sure there are no disk read/write operations before all indiced
                // and file collections are closed below
                shutdownThreadsAndWait(storeHashesExecutor, storeLeavesExecutor, snapshotExecutor);
            } finally {
                try {
                    // close all closable data stores
                    logger.info(MERKLE_DB.getMarker(), "Closing Data Source [{}]", tableName);
                    // Hashes store
                    if (hashStoreRam != null) {
                        hashStoreRam.close();
                    }
                    if (hashStoreDisk != null) {
                        hashStoreDisk.close();
                    }
                    // Then hashes index
                    pathToDiskLocationInternalNodes.close();
                    // Key to paths, both store and index
                    keyToPath.close();
                    // Leaves store
                    pathToKeyValue.close();
                    // Then leaves index
                    pathToDiskLocationLeafNodes.close();
                } catch (final Exception e) {
                    logger.warn(EXCEPTION.getMarker(), "Exception while closing Data Source [{}]", tableName);
                } catch (final Error t) {
                    logger.error(EXCEPTION.getMarker(), "Error while closing Data Source [{}]", tableName);
                    throw t;
                } finally {
                    // updated count of open databases
                    COUNT_OF_OPEN_DATABASES.decrement();
                    // Notify the database
                    database.closeDataSource(this, !keepData);
                }
            }
        }
    }

    /**
     * Write a snapshot of the current state of the database at this moment in time. This will block
     * till the snapshot is completely created.
     * <p>
     *
     * <b> Only one snapshot can happen at a time, this will throw an IllegalStateException if
     * another snapshot is currently happening. </b>
     * <p>
     * <b> IMPORTANT, after this is completed the caller owns the directory. It is responsible
     * for deleting it when it is no longer needed. </b>
     *
     * @param snapshotDirectory Directory to put snapshot into, it will be created if it doesn't
     *     exist.
     * @throws IOException If there was a problem writing the current database out to the given
     *     directory
     * @throws IllegalStateException If there is already a snapshot happening
     */
    @SuppressWarnings("ConstantConditions")
    @Override
    public void snapshot(final Path snapshotDirectory) throws IOException, IllegalStateException {
        // check if another snapshot was running
        final boolean aSnapshotWasInProgress = snapshotInProgress.getAndSet(true);
        if (aSnapshotWasInProgress) {
            throw new IllegalStateException("Tried to start a snapshot when one was already in progress");
        }
        try {
            // start timing snapshot
            final long START = System.currentTimeMillis();
            // create snapshot dir if it doesn't exist
            Files.createDirectories(snapshotDirectory);
            final MerkleDbPaths snapshotDbPaths = new MerkleDbPaths(snapshotDirectory);
            // main snapshotting process in multiple-threads
            try {
                final CountDownLatch countDownLatch = new CountDownLatch(7);
                // write all data stores
                runWithSnapshotExecutor(true, countDownLatch, "pathToDiskLocationInternalNodes", () -> {
                    pathToDiskLocationInternalNodes.writeToFile(snapshotDbPaths.pathToDiskLocationInternalNodesFile);
                    return true;
                });
                runWithSnapshotExecutor(true, countDownLatch, "pathToDiskLocationLeafNodes", () -> {
                    pathToDiskLocationLeafNodes.writeToFile(snapshotDbPaths.pathToDiskLocationLeafNodesFile);
                    return true;
                });
                runWithSnapshotExecutor(hashStoreRam != null, countDownLatch, "internalHashStoreRam", () -> {
                    hashStoreRam.writeToFile(snapshotDbPaths.hashStoreRamFile);
                    return true;
                });
                runWithSnapshotExecutor(hashStoreDisk != null, countDownLatch, "internalHashStoreDisk", () -> {
                    hashStoreDisk.snapshot(snapshotDbPaths.hashStoreDiskDirectory);
                    return true;
                });
                runWithSnapshotExecutor(keyToPath != null, countDownLatch, "keyToPath", () -> {
                    keyToPath.snapshot(snapshotDbPaths.keyToPathDirectory);
                    return true;
                });
                runWithSnapshotExecutor(true, countDownLatch, "pathToKeyValue", () -> {
                    pathToKeyValue.snapshot(snapshotDbPaths.pathToKeyValueDirectory);
                    return true;
                });
                runWithSnapshotExecutor(true, countDownLatch, "metadata", () -> {
                    saveMetadata(snapshotDbPaths);
                    return true;
                });
                // wait for the others to finish
                countDownLatch.await();
            } catch (final InterruptedException e) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "[{}] InterruptedException from waiting for countDownLatch in snapshot",
                        tableName,
                        e);
                Thread.currentThread().interrupt();
            }
            logger.info(
                    MERKLE_DB.getMarker(),
                    "[{}] Snapshot all finished in {} seconds",
                    tableName,
                    (System.currentTimeMillis() - START) * UnitConstants.MILLISECONDS_TO_SECONDS);
        } finally {
            snapshotInProgress.set(false);
        }
    }

    /** toString for debugging */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("maxNumberOfKeys", tableConfig.getMaxNumberOfKeys())
                .append("preferDiskBasedIndexes", preferDiskBasedIndices)
                .append("pathToDiskLocationInternalNodes.size", pathToDiskLocationInternalNodes.size())
                .append("pathToDiskLocationLeafNodes.size", pathToDiskLocationLeafNodes.size())
                .append("hashesRamToDiskThreshold", tableConfig.getHashesRamToDiskThreshold())
                .append("hashStoreRam.size", hashStoreRam == null ? null : hashStoreRam.size())
                .append("hashStoreDisk", hashStoreDisk)
                .append("hasDiskStoreForHashes", hasDiskStoreForHashes)
                .append("keyToPath", keyToPath)
                .append("pathToKeyValue", pathToKeyValue)
                .append("snapshotInProgress", snapshotInProgress.get())
                .toString();
    }

    /**
     * Database instance that hosts this data source.
     *
     * @return Virtual database instance
     */
    public MerkleDb getDatabase() {
        return database;
    }

    /**
     * Table ID for this data source in its virtual database instance.
     *
     * @return Table ID
     */
    public int getTableId() {
        return tableId;
    }

    /**
     * Table name for this data source in its virtual database instance.
     *
     * @return Table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Table config for this data source. Includes key and value serializers, internal nodes
     * RAM/disk threshold, etc.
     *
     * @return Table config
     */
    public MerkleDbTableConfig getTableConfig() {
        return tableConfig;
    }

    // For testing purpose
    Path getStorageDir() {
        return dbPaths.storageDir;
    }

    // For testing purpose
    long getMaxNumberOfKeys() {
        return tableConfig.getMaxNumberOfKeys();
    }

    // For testing purpose
    long getHashesRamToDiskThreshold() {
        return tableConfig.getHashesRamToDiskThreshold();
    }

    // For testing purpose
    boolean isPreferDiskBasedIndexes() {
        return preferDiskBasedIndices;
    }

    // For testing purpose
    boolean isCompactionEnabled() {
        return compactionCoordinator.isCompactionEnabled();
    }

    private void saveMetadata(final MerkleDbPaths targetDir) throws IOException {
        final KeyRange leafRange = validLeafPathRange;
        final Path targetFile = targetDir.metadataFile;
        try (final OutputStream fileOut =
                Files.newOutputStream(targetFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            final WritableSequentialData out = new WritableStreamingData(fileOut);
            if (leafRange.getMinValidKey() != 0) {
                ProtoWriterTools.writeTag(out, FIELD_DSMETADATA_MINVALIDKEY);
                out.writeVarLong(leafRange.getMinValidKey(), false);
            }
            if (leafRange.getMaxValidKey() != 0) {
                ProtoWriterTools.writeTag(out, FIELD_DSMETADATA_MAXVALIDKEY);
                out.writeVarLong(leafRange.getMaxValidKey(), false);
            }
            fileOut.flush();
        }
    }

    private boolean loadMetadata(final MerkleDbPaths sourceDir) throws IOException {
        if (Files.exists(sourceDir.metadataFile)) {
            final Path sourceFile = sourceDir.metadataFile;
            long minValidKey = 0;
            long maxValidKey = 0;
            try (final ReadableStreamingData in = new ReadableStreamingData(sourceFile)) {
                while (in.hasRemaining()) {
                    final int tag = in.readVarInt(false);
                    final int fieldNum = tag >> TAG_FIELD_OFFSET;
                    if (fieldNum == FIELD_DSMETADATA_MINVALIDKEY.number()) {
                        minValidKey = in.readVarLong(false);
                    } else if (fieldNum == FIELD_DSMETADATA_MAXVALIDKEY.number()) {
                        maxValidKey = in.readVarLong(false);
                    } else {
                        throw new IllegalArgumentException("Unknown data source metadata field: " + fieldNum);
                    }
                }
                validLeafPathRange = new KeyRange(minValidKey, maxValidKey);
            }
            Files.delete(sourceFile);
            return true;
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public void registerMetrics(final Metrics metrics) {
        statisticsUpdater.registerMetrics(metrics);
    }

    /** {@inheritDoc} */
    @Override
    public void copyStatisticsFrom(final VirtualDataSource that) {
        if (!(that instanceof MerkleDbDataSource thatDataSource)) {
            logger.warn(MERKLE_DB.getMarker(), "Can only copy statistics from MerkleDbDataSource");
            return;
        }
        statisticsUpdater = thatDataSource.statisticsUpdater;
    }

    // ==================================================================================================================
    // private methods

    /**
     * Shutdown threads if they are running and wait for them to finish
     *
     * @param executors array of threads to shut down.
     * @throws IOException if there was a problem or timeout shutting down threads.
     */
    private void shutdownThreadsAndWait(final ExecutorService... executors) throws IOException {
        try {
            // shutdown threads
            for (final ExecutorService executor : executors) {
                if (!executor.isShutdown()) {
                    executor.shutdown();
                    final boolean finishedWithoutTimeout = executor.awaitTermination(5, TimeUnit.MINUTES);
                    if (!finishedWithoutTimeout) {
                        throw new IOException("Timeout while waiting for executor service to finish.");
                    }
                }
            }
        } catch (final InterruptedException e) {
            logger.warn(EXCEPTION.getMarker(), "[{}] Interrupted while waiting on executors to shutdown", tableName, e);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for shutdown to finish.", e);
        }
    }

    /**
     * Run a runnable on background thread using snapshot ExecutorService, counting down latch when
     * done.
     *
     * @param shouldRun when true, run runnable otherwise just countdown latch
     * @param countDownLatch latch to count down when done
     * @param taskName the name of the task for logging
     * @param runnable the code to run
     */
    private void runWithSnapshotExecutor(
            final boolean shouldRun,
            final CountDownLatch countDownLatch,
            final String taskName,
            final Callable<Object> runnable) {
        if (shouldRun) {
            snapshotExecutor.submit(() -> {
                final long START = System.currentTimeMillis();
                try {
                    runnable.call();
                    logger.trace(
                            MERKLE_DB.getMarker(),
                            "[{}] Snapshot {} complete in {} seconds",
                            tableName,
                            taskName,
                            (System.currentTimeMillis() - START) * UnitConstants.MILLISECONDS_TO_SECONDS);
                    return true; // turns this into a callable, so it can throw checked
                    // exceptions
                } catch (final Throwable t) {
                    // log and rethrow
                    logger.error(EXCEPTION.getMarker(), "[{}] Snapshot {} failed", tableName, taskName, t);
                    throw t;
                } finally {
                    countDownLatch.countDown();
                }
            });
        } else {
            countDownLatch.countDown();
        }
    }

    /**
     * Write all hashes to hashStore
     */
    private void writeHashes(final long maxValidPath, final Stream<VirtualHashRecord> dirtyHashes) throws IOException {
        if (hasDiskStoreForHashes) {
            if (maxValidPath < 0) {
                // Empty store
                hashStoreDisk.updateValidKeyRange(-1, -1);
            } else {
                hashStoreDisk.updateValidKeyRange(0, maxValidPath);
            }
        }

        if ((dirtyHashes == null) || (maxValidPath < 0)) {
            // nothing to do
            return;
        }

        if (hasDiskStoreForHashes) {
            hashStoreDisk.startWriting();
        }

        dirtyHashes.forEach(rec -> {
            statisticsUpdater.countFlushHashesWritten();
            if (rec.path() < tableConfig.getHashesRamToDiskThreshold()) {
                hashStoreRam.put(rec.path(), rec.hash());
            } else {
                try {
                    hashStoreDisk.put(rec.path(), rec::writeTo, rec.getSizeInBytes());
                } catch (final IOException e) {
                    logger.error(EXCEPTION.getMarker(), "[{}] IOException writing internal records", tableName, e);
                    throw new UncheckedIOException(e);
                }
            }
        });

        if (hasDiskStoreForHashes) {
            final DataFileReader newHashesFile = hashStoreDisk.endWriting();
            statisticsUpdater.setFlushHashesStoreFileSize(newHashesFile);
            compactionCoordinator.compactIfNotRunningYet(
                    DataFileCompactor.HASH_STORE_DISK, newHashStoreDiskCompactor());
        }
    }

    /** Write all the given leaf records to pathToKeyValue */
    private void writeLeavesToPathToKeyValue(
            final long firstLeafPath,
            final long lastLeafPath,
            @NonNull final Stream<VirtualLeafBytes> dirtyLeaves,
            @NonNull final Stream<VirtualLeafBytes> deletedLeaves,
            boolean isReconnect)
            throws IOException {
        // If both streams are empty, no new data files should be created. One simple way to
        // check emptiness is to use iterators. The iterators are consumed on a single thread
        // (the current thread), but it still makes sense to use parallel streams as supplying
        // elements to the stream includes expensive operations like serialization to bytes
        final Iterator<VirtualLeafBytes> dirtyIterator = dirtyLeaves
                .parallel()
                .sorted(Comparator.comparingLong(VirtualLeafBytes::path))
                .iterator();
        final Iterator<VirtualLeafBytes> deletedIterator = deletedLeaves.iterator();

        if (lastLeafPath < 0) {
            // Empty store
            pathToKeyValue.updateValidKeyRange(-1, -1);
        } else {
            pathToKeyValue.updateValidKeyRange(firstLeafPath, lastLeafPath);
        }

        if (!dirtyIterator.hasNext() && !deletedIterator.hasNext()) {
            // Nothing to do
            return;
        }

        pathToKeyValue.startWriting();
        keyToPath.startWriting();

        // Iterate over leaf records
        while (dirtyIterator.hasNext()) {
            final VirtualLeafBytes leafBytes = dirtyIterator.next();
            final long path = leafBytes.path();
            // Update key to path index
            keyToPath.put(leafBytes.keyBytes(), leafBytes.keyHashCode(), path);
            statisticsUpdater.countFlushLeafKeysWritten();

            // Update path to K/V store
            try {
                pathToKeyValue.put(leafBytes.path(), leafBytes::writeTo, leafBytes.getSizeInBytes());
            } catch (final IOException e) {
                logger.error(EXCEPTION.getMarker(), "[{}] IOException writing to pathToKeyValue", tableName, e);
                throw new UncheckedIOException(e);
            }
            statisticsUpdater.countFlushLeavesWritten();

            // cache the record
            invalidateReadCache(leafBytes.keyBytes(), leafBytes.keyHashCode());
        }

        // Iterate over leaf records to delete
        while (deletedIterator.hasNext()) {
            final VirtualLeafBytes leafBytes = deletedIterator.next();
            final long path = leafBytes.path();
            // Update key to path index. In some cases (e.g. during reconnect), some leaves in the
            // deletedLeaves stream have been moved to different paths in the tree. This is good
            // indication that these leaves should not be deleted. This is why putIfEqual() and
            // deleteIfEqual() are used below rather than unconditional put() and delete() as for
            // dirtyLeaves stream above
            if (isReconnect) {
                keyToPath.deleteIfEqual(leafBytes.keyBytes(), leafBytes.keyHashCode(), path);
            } else {
                keyToPath.delete(leafBytes.keyBytes(), leafBytes.keyHashCode());
            }
            statisticsUpdater.countFlushLeavesDeleted();

            // delete from pathToKeyValue, we don't need to explicitly delete leaves as
            // they will be deleted on
            // next merge based on range of valid leaf paths. If a leaf at path X is deleted
            // then a new leaf is
            // inserted at path X then the record is just updated to new leaf's data.

            // delete the record from the cache
            invalidateReadCache(leafBytes.keyBytes(), leafBytes.keyHashCode());
        }

        // end writing
        final DataFileReader pathToKeyValueReader = pathToKeyValue.endWriting();
        statisticsUpdater.setFlushLeavesStoreFileSize(pathToKeyValueReader);
        compactionCoordinator.compactIfNotRunningYet(DataFileCompactor.PATH_TO_KEY_VALUE, newPathToKeyValueCompactor());
        final DataFileReader keyToPathReader = keyToPath.endWriting();
        statisticsUpdater.setFlushLeafKeysStoreFileSize(keyToPathReader);
        compactionCoordinator.compactIfNotRunningYet(DataFileCompactor.OBJECT_KEY_TO_PATH, newKeyToPathCompactor());
    }

    /**
     * Creates a new data file compactor for hashStoreDisk file collection.
     */
    DataFileCompactor newHashStoreDiskCompactor() {
        return new DataFileCompactor(
                database.getConfiguration().getConfigData(MerkleDbConfig.class),
                tableName + "_" + DataFileCompactor.HASH_STORE_DISK,
                hashStoreDisk.getFileCollection(),
                pathToDiskLocationInternalNodes,
                statisticsUpdater::setHashesStoreCompactionTimeMs,
                statisticsUpdater::setHashesStoreCompactionSavedSpaceMb,
                statisticsUpdater::setHashesStoreFileSizeByLevelMb,
                () -> {
                    statisticsUpdater.updateStoreFileStats(this);
                    statisticsUpdater.updateOffHeapStats(this);
                });
    }

    /**
     * Creates a new data file compactor for pathToKeyValue file collection.
     */
    DataFileCompactor newPathToKeyValueCompactor() {
        return new DataFileCompactor(
                database.getConfiguration().getConfigData(MerkleDbConfig.class),
                tableName + "_" + DataFileCompactor.PATH_TO_KEY_VALUE,
                pathToKeyValue.getFileCollection(),
                pathToDiskLocationLeafNodes,
                statisticsUpdater::setLeavesStoreCompactionTimeMs,
                statisticsUpdater::setLeavesStoreCompactionSavedSpaceMb,
                statisticsUpdater::setLeavesStoreFileSizeByLevelMb,
                () -> {
                    statisticsUpdater.updateStoreFileStats(this);
                    statisticsUpdater.updateOffHeapStats(this);
                });
    }

    /**
     * Creates a new data file compactor for keyToPath file collection.
     */
    DataFileCompactor newKeyToPathCompactor() {
        return new DataFileCompactor(
                database.getConfiguration().getConfigData(MerkleDbConfig.class),
                tableName + "_" + DataFileCompactor.OBJECT_KEY_TO_PATH,
                keyToPath.getFileCollection(),
                keyToPath.getBucketIndexToBucketLocation(),
                statisticsUpdater::setLeafKeysStoreCompactionTimeMs,
                statisticsUpdater::setLeafKeysStoreCompactionSavedSpaceMb,
                statisticsUpdater::setLeafKeysStoreFileSizeByLevelMb,
                () -> {
                    statisticsUpdater.updateStoreFileStats(this);
                    statisticsUpdater.updateOffHeapStats(this);
                });
    }

    /**
     * Invalidates the given key in virtual leaf record cache, if the cache is enabled.
     * <p>
     * If the key is deleted, it's still updated in the cache. It means no record with the given
     * key exists in the data source, so further lookups for the key are skipped.
     * <p>
     * Cache index is calculated as the key's hash code % cache size. The cache is only updated,
     * if the current record at this index has the given key. If the key is different, no update is
     * performed.
     *
     * @param keyBytes virtual key
     * @param keyHashCode virtual key hash code
     */
    private void invalidateReadCache(final Bytes keyBytes, final int keyHashCode) {
        if (leafRecordCache == null) {
            return;
        }
        final int cacheIndex = Math.abs(keyHashCode % leafRecordCacheSize);
        final VirtualLeafBytes cached = leafRecordCache[cacheIndex];
        if ((cached != null) && keyBytes.equals(cached.keyBytes())) {
            leafRecordCache[cacheIndex] = null;
        }
    }

    FileStatisticAware getHashStoreDisk() {
        return hashStoreDisk;
    }

    FileStatisticAware getKeyToPath() {
        return keyToPath;
    }

    FileStatisticAware getPathToKeyValue() {
        return pathToKeyValue;
    }

    MerkleDbCompactionCoordinator getCompactionCoordinator() {
        return compactionCoordinator;
    }

    OffHeapUser getHashStoreRam() {
        return hashStoreRam;
    }

    LongList getPathToDiskLocationInternalNodes() {
        return pathToDiskLocationInternalNodes;
    }

    LongList getPathToDiskLocationLeafNodes() {
        return pathToDiskLocationLeafNodes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(database, tableId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MerkleDbDataSource other)) {
            return false;
        }
        return Objects.equals(database, other.database) && Objects.equals(tableId, other.tableId);
    }
}

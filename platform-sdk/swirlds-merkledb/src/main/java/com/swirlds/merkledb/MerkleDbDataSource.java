// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.merkledb.KeyRange.INVALID_KEY_RANGE;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.base.units.UnitConstants;
import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.collections.HashListByteBuffer;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListDisk;
import com.swirlds.merkledb.collections.LongListOffHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCollection.LoadedDataCallback;
import com.swirlds.merkledb.files.DataFileCommon;
import com.swirlds.merkledb.files.DataFileCompactor;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.merkledb.files.MemoryIndexDiskKeyValueStore;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
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
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;

@SuppressWarnings("rawtypes")
public final class MerkleDbDataSource implements VirtualDataSource {

    private static final Logger logger = LogManager.getLogger(MerkleDbDataSource.class);

    /** Label for database component used in logging, stats, etc. */
    static final String MERKLEDB_COMPONENT = "merkledb";

    /** Count of open database instances */
    private static final LongAdder COUNT_OF_OPEN_DATABASES = new LongAdder();

    /** Data source metadata fields */
    private static final FieldDefinition FIELD_DSMETADATA_MINVALIDKEY =
            new FieldDefinition("minValidKey", FieldType.UINT64, false, true, false, 1);

    private static final FieldDefinition FIELD_DSMETADATA_MAXVALIDKEY =
            new FieldDefinition("maxValidKey", FieldType.UINT64, false, true, false, 2);

    private static final FieldDefinition FIELD_DSMETADATA_INITIALCAPACITY =
            new FieldDefinition("initialCapacity", FieldType.UINT64, false, true, false, 3);

    @Deprecated
    private static final FieldDefinition FIELD_DSMETADATA_HASHESRAMTODISKTHRESHOLD =
            new FieldDefinition("hashesRamToDiskThreshold", FieldType.UINT64, false, true, false, 4);

    private static final FieldDefinition FIELD_DSMETADATA_HASHCHUNKHEIGHT =
            new FieldDefinition("hashChunkHeight", FieldType.UINT32, false, true, false, 7);

    /*
     * MerkleDb configuration.
     */
    private final MerkleDbConfig merkleDbConfig;

    /** Table name. Used as a subdir name in the database directory */
    private final String tableName;

    private volatile long initialCapacity;

    /**
     * This field is only used, when a data source is created from an old snapshot, where
     * hashes are stored individually rather than in chunks.
     */
    @Deprecated
    volatile long hashesRamToDiskThreshold = 0;

    /**
     * Indicates whether disk based indices are used for this data source.
     */
    private final boolean preferDiskBasedIndices;

    /**
     * In memory off-heap index for hash chunks. Maps chunk IDs to disk locations.
     * A part of the hash chunk store.
     */
    private final LongList idToDiskLocationHashChunks;

    /**
     * In memory off-heap index for leaves. Maps paths to disk locations. A part of
     * the leaves store.
     */
    private final LongList pathToDiskLocationLeafNodes;

    /**
     * Hash chunk height. When an empty MerkleDb data source is created, the height is
     * read from {@link VirtualMapConfig#hashChunkHeight()}. When an existing
     * data source is loaded from disk, the value from the config is ignored, and the
     * height is loaded from the data source metadata file.
     */
    private final int hashChunkHeight;

    /**
     * Mixed disk (data) and off-heap memory (index) store for hash chunks. Stores
     * {@link VirtualHashChunk} objects.
     */
    private final MemoryIndexDiskKeyValueStore hashChunkStore;

    private final int hashChunkCacheThreshold;

    /**
     * In memory cache for hash chunks with IDs less than {@link #hashChunkCacheThreshold}.
     * When a data source snapshot is written to disk, all hash chunks from this cache are
     * written to disk first.
     */
    private final Map<Long, VirtualHashChunk> hashChunkCache;

    /**
     * Mixed disk (data) and off-heap (index) memory store for key to path mappings.
     */
    private final HalfDiskHashMap keyToPath;

    /**
     * Mixed disk (data) and off-heap memory (index) store for leaves. Stores {@link
     * VirtualLeafBytes} objects.
     */
    private final MemoryIndexDiskKeyValueStore keyValueStore;

    /**
     * Cache size for reading virtual leaf records. Initialized in data source creation time from
     * MerkleDb settings. If the value is zero, the leaf records cache isn't used.
     */
    private final int leafRecordCacheSize;

    /**
     * Virtual leaf records cache. It's a simple array indexed by leaf keys % cache size. Cache
     * eviction is not needed, as array size is fixed and can be configured in MerkleDb settings.
     * Index conflicts are resolved in a very straightforward way: whatever entry is read last, it's
     * put to the cache.
     */
    private final VirtualLeafBytes[] leafRecordCache;

    /** Thread pool storing path-to-hash mappings */
    private final ExecutorService storeHashesExecutor;

    /** Thread pool storing path-to-leaf mappings */
    private final ExecutorService storeLeavesExecutor;

    /** Thread pool storing key-to-path mappings */
    private final ExecutorService storeLeafKeysExecutor;

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

    /**
     * Creates a new MerkleDb data source. The specified storage dir must exist and contain valid
     * data source files. Initial capacity and hashes RAM/disk threshold are read from data source
     * metadata file.
     */
    public MerkleDbDataSource(
            final Path storageDir,
            final Configuration config,
            final String tableName,
            final boolean compactionEnabled,
            final boolean offlineUse)
            throws IOException {
        this(storageDir, config, tableName, 0, compactionEnabled, offlineUse);
    }

    /**
     * Creates a new MerkleDb data source. If the specified storage dir exists, it's considered a
     * data source snapshot, and the data source is loaded from the existing files. If no data or
     * metadata files are found, an exception is thrown. If the specified storage dir doesn't exist,
     * a new empty data source is created with initial capacity as specified.
     *
     * @param storageDir Directory to store data files
     * @param config Platform configuration
     * @param tableName Data source label, used in logs, metrics, etc.
     * @param initialCapacity Initial database capacity. Only used if a new database is created. If
     *                        an existing database is loaded from the storage dir, initial capacity
     *                        is read from MerkleDb metadata file
     * @param compactionEnabled Indicates whether background compaction should be running for this data
     *                          source
     * @param diskBasedIndices Indicates that the data source should use disk based indices
     * @throws IOException If an I/O error occurs
     */
    public MerkleDbDataSource(
            final Path storageDir,
            final Configuration config,
            final String tableName,
            final long initialCapacity,
            final boolean compactionEnabled,
            final boolean diskBasedIndices)
            throws IOException {
        this.tableName = tableName;
        this.preferDiskBasedIndices = diskBasedIndices;

        final VirtualMapConfig virtualMapConfig = config.getConfigData(VirtualMapConfig.class);
        this.hashChunkHeight = virtualMapConfig.hashChunkHeight();
        this.merkleDbConfig = config.getConfigData(MerkleDbConfig.class);

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
        // create thread pool storing virtual leaf keys
        storeLeafKeysExecutor = Executors.newSingleThreadExecutor(new ThreadConfiguration(getStaticThreadManager())
                .setComponent(MERKLEDB_COMPONENT)
                .setThreadGroup(threadGroup)
                .setThreadName("Store leaf keys")
                .setExceptionHandler((t, ex) -> logger.error(
                        EXCEPTION.getMarker(), "[{}] Uncaught exception during storing leaf keys", tableName, ex))
                .buildFactory());
        // thread pool creating snapshots, it is unbounded in threads, but we use at most 7
        snapshotExecutor = Executors.newCachedThreadPool(new ThreadConfiguration(getStaticThreadManager())
                .setComponent(MERKLEDB_COMPONENT)
                .setThreadGroup(threadGroup)
                .setThreadName("Snapshot")
                .setExceptionHandler(
                        (t, ex) -> logger.error(EXCEPTION.getMarker(), "Uncaught exception during snapshots", ex))
                .buildFactory());

        dbPaths = new MerkleDbPaths(storageDir);

        // check if we are loading an existing database or creating a new one
        if (Files.exists(storageDir)) {
            // Read metadata, inits initialCapacity, hashesRamToDiskThreshold, and validLeafPathRange
            if (!loadMetadata(dbPaths)) {
                logger.error(
                        MERKLE_DB.getMarker(),
                        "[{}] Loading existing set of data files but no metadata file was found in" + " [{}]",
                        tableName,
                        storageDir.toAbsolutePath());
                throw new IOException("Can not load an existing MerkleDbDataSource from ["
                        + storageDir.toAbsolutePath()
                        + "] because metadata file is missing");
            }
        } else {
            this.initialCapacity = initialCapacity;
            Files.createDirectories(storageDir);
        }

        if (this.initialCapacity <= 0) {
            throw new IllegalStateException("Initial capacity must be greater than 0, but was " + this.initialCapacity);
        }

        saveMetadata(dbPaths);

        final boolean forceIndexRebuilding = merkleDbConfig.indexRebuildingEnforced();

        // Get the max number of keys is set in the MerkleDb config, then multiply it by
        // two, since virtual path range is 2 times number of keys stored in a virtual map.
        // Path to hash and path to KV index capacity will be based on this max virtual
        // path. Index capacity limits the max size of the index, but it doesn't have anything
        // to do with index initial size. If a new MerkleDb instance is created, both path
        // indices will have size 0
        final long maxPath = merkleDbConfig.maxNumOfKeys() * 2;
        // Path to KV index capacity is the same as max virtual path
        final long kvIndexCapacity = maxPath;
        // ID to hash index capacity is the min chunk ID to cover all paths from 0 to maxPath
        final long hashIndexCapacity = VirtualHashChunk.lastChunkIdForPaths(maxPath, hashChunkHeight) + 1;

        // Hash chunk disk location index (chunk ID to disk location)
        final Path idToHashChunksFile = dbPaths.idToDiskLocationHashChunksFile;
        if (Files.exists(idToHashChunksFile) && !forceIndexRebuilding) {
            idToDiskLocationHashChunks = preferDiskBasedIndices
                    ? new LongListDisk(idToHashChunksFile, hashIndexCapacity, config)
                    : new LongListOffHeap(idToHashChunksFile, hashIndexCapacity, config);
        } else {
            idToDiskLocationHashChunks = preferDiskBasedIndices
                    ? new LongListDisk(hashIndexCapacity, config)
                    : new LongListOffHeap(hashIndexCapacity, config);
        }

        // Hash chunk store (hash chunks)
        if (Files.exists(dbPaths.hashStoreRamFile) || Files.isDirectory(dbPaths.hashStoreDiskDirectory)) {
            if (idToDiskLocationHashChunks.size() != 0) {
                throw new IllegalStateException("Hash chunk index is not empty, but legacy hash stores exist");
            }
            hashChunkStore = new MemoryIndexDiskKeyValueStore(
                    merkleDbConfig,
                    dbPaths.hashChunkDirectory,
                    tableName + "_idtohashchunk",
                    null,
                    null,
                    idToDiskLocationHashChunks);
            // Try to rebuild hash chunks from legacy hash store RAM / disk. If hash store / disk
            // is used, but the legacy path to hash disk location index file is missing, the method
            // below will throw an exception (even if index rebuilding is forced)
            rebuildHashChunks(config, maxPath + 1, hashesRamToDiskThreshold);
        } else {
            final LoadedDataCallback hashChunkLoadedCallback;
            // Check if hash chunk index is to be restored: either the index file is missing, or
            // index rebuilding is explicitly forced in MerkleDbConfig
            final boolean needRestorePathToDiskLocationHashChunks = idToDiskLocationHashChunks.size() == 0;
            if (needRestorePathToDiskLocationHashChunks) {
                if (validLeafPathRange.getMaxValidKey() >= 0) {
                    idToDiskLocationHashChunks.updateValidRange(0, validLeafPathRange.getMaxValidKey());
                }
                hashChunkLoadedCallback = (dataLocation, hashData) -> {
                    final VirtualHashChunk hashChunk = VirtualHashChunk.parseFrom(hashData, hashChunkHeight);
                    final long path = hashChunk.path();
                    // Old data files may contain entries with paths outside the current virtual node range
                    final long firstHashPath = com.swirlds.virtualmap.internal.Path.getRightChildPath(path);
                    if (firstHashPath <= validLeafPathRange.getMaxValidKey()) {
                        final long chunkId = VirtualHashChunk.pathToChunkId(firstHashPath, hashChunkHeight);
                        idToDiskLocationHashChunks.put(chunkId, dataLocation);
                    }
                };
            } else {
                hashChunkLoadedCallback = null;
            }
            hashChunkStore = new MemoryIndexDiskKeyValueStore(
                    merkleDbConfig,
                    dbPaths.hashChunkDirectory,
                    tableName + "_idtohashchunk",
                    null,
                    hashChunkLoadedCallback,
                    idToDiskLocationHashChunks);
        }

        hashChunkCacheThreshold = merkleDbConfig.hashChunkCacheThreshold();
        hashChunkCache = new ConcurrentHashMap<>(hashChunkCacheThreshold);

        // KV disk location index (path to disk location)
        final Path pathToLeafLocationFile = dbPaths.pathToDiskLocationLeafNodesFile;
        if (Files.exists(pathToLeafLocationFile) && !forceIndexRebuilding) {
            pathToDiskLocationLeafNodes = preferDiskBasedIndices
                    ? new LongListDisk(pathToLeafLocationFile, kvIndexCapacity, config)
                    : new LongListOffHeap(pathToLeafLocationFile, kvIndexCapacity, config);
        } else {
            pathToDiskLocationLeafNodes = preferDiskBasedIndices
                    ? new LongListDisk(kvIndexCapacity, config)
                    : new LongListOffHeap(kvIndexCapacity, config);
        }

        // Leaves store (leaf nodes)
        final LoadedDataCallback leafRecordLoadedCallback;
        // Check if leaf node index is to be restored: either the index file is missing, or
        // index rebuilding is explicitly forced in MerkleDbConfig
        final boolean needRestorePathToDiskLocationLeafNodes =
                (pathToDiskLocationLeafNodes.size() == 0) && (validLeafPathRange.getMinValidKey() > 0);
        if (needRestorePathToDiskLocationLeafNodes) {
            if (validLeafPathRange.getMaxValidKey() >= 0) {
                pathToDiskLocationLeafNodes.updateValidRange(
                        validLeafPathRange.getMinValidKey(), validLeafPathRange.getMaxValidKey());
            }
            leafRecordLoadedCallback = (dataLocation, leafData) -> {
                final VirtualLeafBytes<?> leafBytes = VirtualLeafBytes.parseFrom(leafData);
                final long path = leafBytes.path();
                // Old data files may contain entries with paths outside the current leaf range
                if (validLeafPathRange.withinRange(path)) {
                    pathToDiskLocationLeafNodes.put(path, dataLocation);
                }
            };
        } else {
            leafRecordLoadedCallback = null;
        }
        keyValueStore = new MemoryIndexDiskKeyValueStore(
                merkleDbConfig,
                dbPaths.pathToKeyValueDirectory,
                tableName + "_pathtohashkeyvalue",
                null,
                leafRecordLoadedCallback,
                pathToDiskLocationLeafNodes);

        // Keys (keys to paths)
        keyToPath = new HalfDiskHashMap(
                config,
                this.initialCapacity,
                dbPaths.keyToPathDirectory,
                tableName + "_objectkeytopath",
                null,
                preferDiskBasedIndices);
        keyToPath.printStats();
        // Repair keyToPath based on pathToKeyValue data, if requested and not disk based indices
        if (!preferDiskBasedIndices) {
            final String tablesToRepairHdhmConfig = merkleDbConfig.tablesToRepairHdhm();
            if (tablesToRepairHdhmConfig != null) {
                final String[] tableNames = tablesToRepairHdhmConfig.split(",");
                if (Arrays.stream(tableNames).filter(s -> !s.isBlank()).anyMatch(tableName::equals)) {
                    keyToPath.repair(getFirstLeafPath(), getLastLeafPath(), keyValueStore);
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
                "Created MerkleDB [{}] with store path '{}', initial capacity = {}, hash chunk height = {}",
                tableName,
                storageDir,
                this.initialCapacity,
                this.hashChunkHeight);
    }

    private void rebuildHashChunks(
            final Configuration config, final long hashIndexCapacity, final long hashesRamToDiskThreshold)
            throws IOException {
        assert hashChunkStore != null;
        assert idToDiskLocationHashChunks.size() == 0;
        assert hashesRamToDiskThreshold >= 0;

        final long startTime = System.currentTimeMillis();
        logger.info(MERKLE_DB.getMarker(), "Migrating hashes to hash chunks");

        // Legacy hash store / RAM
        HashListByteBuffer hashStoreRam = null;
        // Legacy hash store / disk
        MemoryIndexDiskKeyValueStore hashStoreDisk = null;
        try {
            if (hashesRamToDiskThreshold > 0) {
                if (Files.exists(dbPaths.hashStoreRamFile)) {
                    hashStoreRam = new HashListByteBuffer(dbPaths.hashStoreRamFile, hashesRamToDiskThreshold, config);
                } else {
                    throw new IOException("Rebuild hash chunks failed: hashStoreRam is missing");
                }
            }
            if ((hashesRamToDiskThreshold < Long.MAX_VALUE) && (hashesRamToDiskThreshold <= getLastLeafPath())) {
                // Index
                assert hashIndexCapacity > hashesRamToDiskThreshold;
                final LongList pathToDiskLocationInternalNodes;
                if (Files.exists(dbPaths.pathToDiskLocationInternalNodesFile)) {
                    pathToDiskLocationInternalNodes = preferDiskBasedIndices
                            ? new LongListDisk(dbPaths.pathToDiskLocationInternalNodesFile, hashIndexCapacity, config)
                            : new LongListOffHeap(
                                    dbPaths.pathToDiskLocationInternalNodesFile, hashIndexCapacity, config);
                } else {
                    throw new IOException("Rebuild hash chunks failed: pathToDiskLocationInternalNodes is missing");
                }
                // Store
                hashStoreDisk = new MemoryIndexDiskKeyValueStore(
                        merkleDbConfig,
                        dbPaths.hashStoreDiskDirectory,
                        tableName + "_internalhashes",
                        null,
                        null,
                        pathToDiskLocationInternalNodes);
            }

            hashChunkStore.startWriting();

            final long lastChunkId = VirtualHashChunk.lastChunkIdForPaths(getLastLeafPath(), hashChunkHeight);
            hashChunkStore.updateValidKeyRange(0, lastChunkId);
            final int chunkSize = VirtualHashChunk.getChunkSize(hashChunkHeight);
            for (long chunkId = 0; chunkId <= lastChunkId; chunkId++) {
                final long chunkPath = VirtualHashChunk.chunkIdToChunkPath(chunkId, hashChunkHeight);
                final VirtualHashChunk chunk = new VirtualHashChunk(chunkPath, hashChunkHeight);
                for (int i = 0; i < chunkSize; i++) {
                    long path = VirtualHashChunk.getPathInChunk(i, chunkPath, hashChunkHeight);
                    while (path > getLastLeafPath()) {
                        // get parent path
                        path = (path - 1) / 2;
                    }
                    final Hash hash;
                    if (path < hashesRamToDiskThreshold) {
                        assert hashStoreRam != null;
                        hash = hashStoreRam.get(path);
                    } else {
                        assert hashStoreDisk != null;
                        final VirtualHashRecord rec = VirtualHashRecord.parseFrom(hashStoreDisk.get(path));
                        hash = rec == null ? null : rec.hash();
                    }
                    if (hash == null) {
                        throw new IOException("Rebuild hash chunks failed: hash not found, path=" + path);
                    }
                    chunk.setHashAtPath(path, hash);
                }
                hashChunkStore.put(chunkId, chunk::writeTo, chunk.getSerializedSizeInBytes());
            }

            hashChunkStore.endWriting();
        } finally {
            if (hashStoreRam != null) {
                hashStoreRam.close();
                Files.delete(dbPaths.hashStoreRamFile);
            }
            if (hashStoreDisk != null) {
                hashStoreDisk.close();
                FileUtils.deleteDirectory(dbPaths.hashStoreDiskDirectory);
            }
            logger.info(
                    MERKLE_DB.getMarker(),
                    "Migrated {} hashes in {} ms",
                    getLastLeafPath(),
                    System.currentTimeMillis() - startTime);
        }
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
    @Override
    public long getFirstLeafPath() {
        return validLeafPathRange.getMinValidKey();
    }

    /** Get the most recent last leaf path */
    @Override
    public long getLastLeafPath() {
        return validLeafPathRange.getMaxValidKey();
    }

    @Override
    public int getHashChunkHeight() {
        return hashChunkHeight;
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
     * @param hashChunksToUpdate stream of hash chunks to update, it is assumed this is sorted by
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
            @NonNull final Stream<VirtualHashChunk> hashChunksToUpdate,
            @NonNull final Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
            @NonNull final Stream<VirtualLeafBytes> leafRecordsToDelete,
            final boolean isReconnectContext)
            throws IOException {
        try {
            validLeafPathRange = new KeyRange(firstLeafPath, lastLeafPath);
            final CountDownLatch countDownLatch = new CountDownLatch(lastLeafPath > 0 ? 3 : 2);

            if (lastLeafPath > 0) {
                // Use an executor to make sure the data source is not closed in parallel. See
                // the comment in close() for details
                storeHashesExecutor.execute(() -> {
                    try {
                        writeHashes(lastLeafPath, hashChunksToUpdate, true);
                        runHashChunkStoreCompaction();
                    } catch (final IOException e) {
                        logger.error(EXCEPTION.getMarker(), "[{}] Failed to store hashes", tableName, e);
                        throw new UncheckedIOException(e);
                    } finally {
                        countDownLatch.countDown();
                    }
                });
            }

            final VirtualLeafBytes<?>[] dirtyLeaves = leafRecordsToAddOrUpdate.toArray(VirtualLeafBytes[]::new);
            final VirtualLeafBytes<?>[] deletedLeaves = leafRecordsToDelete.toArray(VirtualLeafBytes[]::new);

            // Use an executor to make sure the data source is not closed in parallel. See
            // the comment in close() for details
            storeLeavesExecutor.execute(() -> {
                try {
                    writeLeavesToPathToKeyValue(firstLeafPath, lastLeafPath, dirtyLeaves);
                    runPathToKeyValueStoreCompaction();
                } catch (final IOException e) {
                    logger.error(EXCEPTION.getMarker(), "[{}] Failed to store leaves", tableName, e);
                    throw new UncheckedIOException(e);
                } finally {
                    countDownLatch.countDown();
                }
            });

            // Use an executor to make sure the data source is not closed in parallel. See
            // the comment in close() for details
            storeLeafKeysExecutor.execute(() -> {
                try {
                    writeLeavesToKeyToPath(firstLeafPath, lastLeafPath, dirtyLeaves, deletedLeaves, isReconnectContext);
                    runKeyToPathStoreCompaction();
                } catch (final IOException e) {
                    logger.error(EXCEPTION.getMarker(), "[{}] Failed to store leaf keys", tableName, e);
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
    public VirtualLeafBytes<?> loadLeafRecord(final Bytes keyBytes) throws IOException {
        requireNonNull(keyBytes);
        final int keyHashCode = keyBytes.hashCode();

        final long path;
        VirtualLeafBytes<?> cached = null;
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
            path = keyToPath.get(keyBytes, INVALID_PATH);
        }

        // If the key didn't map to anything, we just return null
        if (path == INVALID_PATH) {
            // Cache the result if not already cached
            if (leafRecordCache != null && cached == null) {
                leafRecordCache[cacheIndex] = new VirtualLeafBytes(path, keyBytes, null);
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
        VirtualLeafBytes<?> leafBytes = VirtualLeafBytes.parseFrom(keyValueStore.get(path));
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
    public VirtualLeafBytes<?> loadLeafRecord(final long path) throws IOException {
        if (path < 0) {
            throw new IllegalArgumentException("Path (" + path + ") is not valid");
        }
        final KeyRange leafPathRange = validLeafPathRange;
        if (!leafPathRange.withinRange(path)) {
            return null;
        }
        statisticsUpdater.countLeafReads();
        return VirtualLeafBytes.parseFrom(keyValueStore.get(path));
    }

    /**
     * Find the path of the given key.
     *
     * @param keyBytes the key for a path
     * @return the path or INVALID_PATH if not stored
     * @throws IOException If there was a problem locating the key
     */
    @Override
    public long findKey(final Bytes keyBytes) throws IOException {
        requireNonNull(keyBytes);
        final int keyHashCode = keyBytes.hashCode();

        // Check the cache first
        int cacheIndex = -1;
        if (leafRecordCache != null) {
            cacheIndex = Math.abs(keyHashCode % leafRecordCacheSize);
            // No synchronization is needed here. See the comment in loadLeafRecord(key) above
            final VirtualLeafBytes<?> cached = leafRecordCache[cacheIndex];
            if (cached != null && keyBytes.equals(cached.keyBytes())) {
                // Cached path may be a valid path or INVALID_PATH, both are legal here
                return cached.path();
            }
        }

        statisticsUpdater.countLeafKeyReads();
        final long path = keyToPath.get(keyBytes, INVALID_PATH);

        if (leafRecordCache != null) {
            // Path may be INVALID_PATH here. Still needs to be cached (negative result)
            leafRecordCache[cacheIndex] = new VirtualLeafBytes(path, keyBytes, null);
        }

        return path;
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public VirtualHashChunk loadHashChunk(final long chunkId) throws IOException {
        if (chunkId < 0) {
            throw new IllegalArgumentException("Hash chunk ID (" + chunkId + ") is not valid");
        }

        final long chunkPath = VirtualHashChunk.chunkIdToChunkPath(chunkId, hashChunkHeight);
        if (com.swirlds.virtualmap.internal.Path.getLeftChildPath(chunkPath) > getLastLeafPath()) {
            return null;
        }

        if (chunkId < hashChunkCacheThreshold) {
            final VirtualHashChunk chunk = hashChunkCache.get(chunkId);
            if (chunk != null) {
                // Should count hash reads here, too?
                return chunk.copy();
            }
        }

        final VirtualHashChunk chunk = VirtualHashChunk.parseFrom(hashChunkStore.get(chunkId), hashChunkHeight);
        assert chunk != null;
        if (chunkId < hashChunkCacheThreshold) {
            assert hashChunkCache.get(chunkId) == null;
            hashChunkCache.put(chunkId, chunk.copy());
        }
        statisticsUpdater.countHashReads();

        return chunk;
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
                shutdownThreadsAndWait(
                        storeHashesExecutor, storeLeavesExecutor, storeLeafKeysExecutor, snapshotExecutor);
            } finally {
                try {
                    // close all closable data stores
                    logger.info(MERKLE_DB.getMarker(), "Closing Data Source [{}]", tableName);
                    // Hash chunk store
                    hashChunkStore.close();
                    // Hash chunk cache
                    hashChunkCache.clear();
                    // Then hash chunk index
                    idToDiskLocationHashChunks.close();
                    // Key to paths, both store and index
                    keyToPath.close();
                    // Leaves store
                    keyValueStore.close();
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
                    // Delete the data dir
                    if (!keepData) {
                        DataFileCommon.deleteDirectoryAndContents(dbPaths.storageDir);
                    }
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
        logger.info(MERKLE_DB.getMarker(), "[{}] Starting snapshot to {}", tableName, snapshotDirectory);
        try {
            // start timing snapshot
            final long START = System.currentTimeMillis();
            // create snapshot dir if it doesn't exist
            Files.createDirectories(snapshotDirectory);
            final MerkleDbPaths snapshotDbPaths = new MerkleDbPaths(snapshotDirectory);
            // main snapshotting process in multiple-threads
            try {
                // Flush cached hash chunks to the hash chunk store
                if (getLastLeafPath() > 0) {
                    final long maxValidChunkId =
                            VirtualHashChunk.lastChunkIdForPaths(getLastLeafPath(), hashChunkHeight);
                    final Stream<VirtualHashChunk> cacheChunksToFlush =
                            hashChunkCache.values().stream().filter(c -> c.getChunkId() <= maxValidChunkId);
                    writeHashes(getLastLeafPath(), cacheChunksToFlush, false);
                }
                final CountDownLatch countDownLatch = new CountDownLatch(6);
                // write all data stores
                runWithSnapshotExecutor(countDownLatch, "idToDiskLocationHashChunks", () -> {
                    idToDiskLocationHashChunks.writeToFile(snapshotDbPaths.idToDiskLocationHashChunksFile);
                    return true;
                });
                runWithSnapshotExecutor(countDownLatch, "pathToDiskLocationLeafNodes", () -> {
                    pathToDiskLocationLeafNodes.writeToFile(snapshotDbPaths.pathToDiskLocationLeafNodesFile);
                    return true;
                });
                runWithSnapshotExecutor(countDownLatch, "hashChunkStore", () -> {
                    hashChunkStore.snapshot(snapshotDbPaths.hashChunkDirectory);
                    return true;
                });
                runWithSnapshotExecutor(countDownLatch, "keyToPath", () -> {
                    keyToPath.snapshot(snapshotDbPaths.keyToPathDirectory);
                    return true;
                });
                runWithSnapshotExecutor(countDownLatch, "keyValueStore", () -> {
                    keyValueStore.snapshot(snapshotDbPaths.pathToKeyValueDirectory);
                    return true;
                });
                runWithSnapshotExecutor(countDownLatch, "metadata", () -> {
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
                .append("initialCapacity", initialCapacity)
                .append("preferDiskBasedIndexes", preferDiskBasedIndices)
                .append("idToDiskLocationHashChunks.size", idToDiskLocationHashChunks.size())
                .append("pathToDiskLocationLeafNodes.size", pathToDiskLocationLeafNodes.size())
                .append("hashChunkStore", hashChunkStore)
                .append("keyToPath", keyToPath)
                .append("keyValueStore", keyValueStore)
                .append("snapshotInProgress", snapshotInProgress.get())
                .toString();
    }

    /**
     * Table name for this data source in its virtual database instance.
     *
     * @return Table name
     */
    public String getTableName() {
        return tableName;
    }

    public long getInitialCapacity() {
        return initialCapacity;
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
            // Initial capacity is always greater than 0
            ProtoWriterTools.writeTag(out, FIELD_DSMETADATA_INITIALCAPACITY);
            out.writeVarLong(initialCapacity, false);
            // Hash RAM/disk threshold is used in tests
            if (hashesRamToDiskThreshold != 0) {
                ProtoWriterTools.writeTag(out, FIELD_DSMETADATA_HASHESRAMTODISKTHRESHOLD);
                out.writeVarLong(hashesRamToDiskThreshold, false);
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
                    } else if (fieldNum == FIELD_DSMETADATA_INITIALCAPACITY.number()) {
                        initialCapacity = in.readVarLong(false);
                    } else if (fieldNum == FIELD_DSMETADATA_HASHESRAMTODISKTHRESHOLD.number()) {
                        hashesRamToDiskThreshold = in.readVarLong(false);
                    } else if (fieldNum == FIELD_DSMETADATA_HASHCHUNKHEIGHT.number()) {
                        final int hashChunkHeight = in.readVarInt(false);
                        if (this.hashChunkHeight != hashChunkHeight) {
                            throw new IllegalStateException("Hash chunk height mismatch, config=" + this.hashChunkHeight
                                    + " disk=" + hashChunkHeight);
                        }
                    } else {
                        throw new IOException("Unknown data source metadata field: " + fieldNum);
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
     * @param countDownLatch latch to count down when done
     * @param taskName the name of the task for logging
     * @param runnable the code to run
     */
    private void runWithSnapshotExecutor(
            final CountDownLatch countDownLatch, final String taskName, final Callable<Object> runnable) {
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
    }

    /**
     * Write all hashes to hashStore
     */
    private void writeHashes(
            final long maxValidPath, @NonNull final Stream<VirtualHashChunk> dirtyHashes, final boolean useCache)
            throws IOException {
        if (maxValidPath < 0) {
            // Empty store
            hashChunkStore.updateValidKeyRange(-1, -1);
        } else {
            hashChunkStore.updateValidKeyRange(0, VirtualHashChunk.lastChunkIdForPaths(maxValidPath, hashChunkHeight));
        }

        if (maxValidPath < 0) {
            // nothing to do
            return;
        }

        hashChunkStore.startWriting();

        dirtyHashes.forEach(chunk -> {
            statisticsUpdater.countFlushHashesWritten();
            final long chunkId = chunk.getChunkId();
            if (useCache && (chunkId < hashChunkCacheThreshold)) {
                hashChunkCache.put(chunkId, chunk);
            } else {
                try {
                    hashChunkStore.put(chunkId, chunk::writeTo, chunk.getSerializedSizeInBytes());
                } catch (final IOException e) {
                    logger.error(EXCEPTION.getMarker(), "[{}] IOException writing hash chunks", tableName, e);
                    throw new UncheckedIOException(e);
                }
            }
        });

        final DataFileReader newHashesFile = hashChunkStore.endWriting();
        statisticsUpdater.setFlushHashesStoreFileSize(newHashesFile);
    }

    /** Write all the given leaf records to pathToKeyValue */
    private void writeLeavesToPathToKeyValue(
            final long firstLeafPath, final long lastLeafPath, @NonNull final VirtualLeafBytes<?>[] dirtyLeaves)
            throws IOException {
        if (lastLeafPath < 0) {
            // Empty store
            keyValueStore.updateValidKeyRange(-1, -1);
        } else {
            keyValueStore.updateValidKeyRange(firstLeafPath, lastLeafPath);
        }

        if (dirtyLeaves.length == 0) {
            // Nothing to do
            return;
        }

        // Functionally, leaves don't have to be sorted. However, performance wise, sorting
        // is beneficial, as adjacent leaves are written together, which reduces the number
        // of random reads later. Treat dirtyLeaves as immutable.
        VirtualLeafBytes<?>[] sortedDirtyLeaves = dirtyLeaves.clone();
        Arrays.parallelSort(sortedDirtyLeaves, Comparator.comparingLong(VirtualLeafBytes::path));

        keyValueStore.startWriting();

        // Iterate over leaf records
        for (VirtualLeafBytes<?> leafBytes : sortedDirtyLeaves) {
            // Update path to K/V store
            try {
                keyValueStore.put(leafBytes.path(), leafBytes::writeTo, leafBytes.getSizeInBytes());
            } catch (final IOException e) {
                logger.error(EXCEPTION.getMarker(), "[{}] IOException writing to pathToKeyValue", tableName, e);
                throw new UncheckedIOException(e);
            }
            statisticsUpdater.countFlushLeavesWritten();
        }

        // end writing
        final DataFileReader pathToKeyValueReader = keyValueStore.endWriting();
        statisticsUpdater.setFlushLeavesStoreFileSize(pathToKeyValueReader);
    }

    /** Write all the given leaf records to keyToPath */
    private void writeLeavesToKeyToPath(
            final long firstLeafPath,
            final long lastLeafPath,
            @NonNull final VirtualLeafBytes<?>[] dirtyLeaves,
            @NonNull final VirtualLeafBytes<?>[] deletedLeaves,
            boolean isReconnect)
            throws IOException {
        if (dirtyLeaves.length == 0 && deletedLeaves.length == 0) {
            // Nothing to do
            return;
        }

        keyToPath.startWriting();

        // Iterate over leaf records
        for (final VirtualLeafBytes<?> leafBytes : dirtyLeaves) {
            final long path = leafBytes.path();
            // Update key to path index
            keyToPath.put(leafBytes.keyBytes(), path);
            statisticsUpdater.countFlushLeafKeysWritten();

            // cache the record
            invalidateReadCache(leafBytes.keyBytes());
        }

        // Iterate over leaf records to delete
        for (VirtualLeafBytes<?> leafBytes : deletedLeaves) {
            // Update key to path index. In some cases (e.g. during reconnect), some leaves in the
            // deletedLeaves stream have been moved to different paths in the tree. This is good
            // indication that these leaves should not be deleted. This is why putIfEqual() and
            // deleteIfEqual() are used below rather than unconditional put() and delete() as for
            // dirtyLeaves stream above
            if (isReconnect) {
                keyToPath.deleteIfEqual(leafBytes.keyBytes(), leafBytes.path());
            } else {
                keyToPath.delete(leafBytes.keyBytes());
            }
            statisticsUpdater.countFlushLeavesDeleted();

            // delete from pathToKeyValue, we don't need to explicitly delete leaves as
            // they will be deleted on
            // next merge based on range of valid leaf paths. If a leaf at path X is deleted
            // then a new leaf is
            // inserted at path X then the record is just updated to new leaf's data.

            // delete the record from the cache
            invalidateReadCache(leafBytes.keyBytes());
        }

        // end writing
        final DataFileReader keyToPathReader = keyToPath.endWriting();
        statisticsUpdater.setFlushLeafKeysStoreFileSize(keyToPathReader);

        if (!compactionCoordinator.isCompactionRunning(DataFileCompactor.OBJECT_KEY_TO_PATH)) {
            keyToPath.resizeIfNeeded(firstLeafPath, lastLeafPath);
        }
    }

    /**
     * Creates a new data file compactor for hashChunkStore file collection.
     */
    DataFileCompactor newHashChunkStoreCompactor() {
        return new DataFileCompactor(
                merkleDbConfig,
                tableName + "_" + DataFileCompactor.ID_TO_HASH_CHUNK,
                hashChunkStore.getFileCollection(),
                idToDiskLocationHashChunks,
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
    DataFileCompactor newKeyValueStoreCompactor() {
        return new DataFileCompactor(
                merkleDbConfig,
                tableName + "_" + DataFileCompactor.PATH_TO_KEY_VALUE,
                keyValueStore.getFileCollection(),
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
                merkleDbConfig,
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
     */
    private void invalidateReadCache(final Bytes keyBytes) {
        if (leafRecordCache == null) {
            return;
        }
        final int keyHashCode = keyBytes.hashCode();
        final int cacheIndex = Math.abs(keyHashCode % leafRecordCacheSize);
        final VirtualLeafBytes<?> cached = leafRecordCache[cacheIndex];
        if ((cached != null) && keyBytes.equals(cached.keyBytes())) {
            leafRecordCache[cacheIndex] = null;
        }
    }

    public void runHashChunkStoreCompaction() {
        compactionCoordinator.compactIfNotRunningYet(DataFileCompactor.ID_TO_HASH_CHUNK, newHashChunkStoreCompactor());
    }

    public void runPathToKeyValueStoreCompaction() {
        compactionCoordinator.compactIfNotRunningYet(DataFileCompactor.PATH_TO_KEY_VALUE, newKeyValueStoreCompactor());
    }

    public void runKeyToPathStoreCompaction() {
        compactionCoordinator.compactIfNotRunningYet(DataFileCompactor.OBJECT_KEY_TO_PATH, newKeyToPathCompactor());
    }

    public void awaitForCurrentCompactionsToComplete(final long timeoutMillis) {
        compactionCoordinator.awaitForCurrentCompactionsToComplete(timeoutMillis);
    }

    public MemoryIndexDiskKeyValueStore getHashChunkStore() {
        return hashChunkStore;
    }

    public HalfDiskHashMap getKeyToPath() {
        return keyToPath;
    }

    public MemoryIndexDiskKeyValueStore getKeyValueStore() {
        return keyValueStore;
    }

    MerkleDbCompactionCoordinator getCompactionCoordinator() {
        return compactionCoordinator;
    }

    public LongList getIdToDiskLocationHashChunks() {
        return idToDiskLocationHashChunks;
    }

    public LongList getPathToDiskLocationLeafNodes() {
        return pathToDiskLocationLeafNodes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(dbPaths.storageDir);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MerkleDbDataSource other)) {
            return false;
        }
        return Objects.equals(dbPaths.storageDir, other.dbPaths.storageDir);
    }
}

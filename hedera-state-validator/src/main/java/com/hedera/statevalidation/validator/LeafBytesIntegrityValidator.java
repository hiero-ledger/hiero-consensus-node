// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import static com.hedera.statevalidation.util.LogUtils.printFileDataLocationError;

import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.validator.util.ValidationException;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.MemoryIndexDiskKeyValueStore;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;

/**
 * @see LeafBytesValidator
 */
public class LeafBytesIntegrityValidator implements LeafBytesValidator {

    private static final Logger log = LogManager.getLogger(LeafBytesIntegrityValidator.class);

    public static final String LEAF_GROUP = "leaf";

    private VirtualMap virtualMap;
    private MerkleDbDataSource vds;
    private HalfDiskHashMap keyToPath;
    private LongList pathToDiskLocationLeafNodes;
    private MemoryIndexDiskKeyValueStore keyValueStore;
    private long firstLeafPath;
    private long lastLeafPath;
    private int hashChunkHeight;

    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong exceptionCount = new AtomicLong(0);
    private final AtomicLong pathMismatchCount = new AtomicLong(0);
    private final AtomicLong valueErrorCount = new AtomicLong(0);
    private final AtomicLong hashMismatchCount = new AtomicLong(0);
    private final AtomicLong indexMismatchCount = new AtomicLong(0);
    private final AtomicLong storeMismatchCount = new AtomicLong(0);

    // A minor optimization to avoid multiple chunk loads from disk
    private final ThreadLocal<VirtualHashChunk> lastChunk = new ThreadLocal<>();

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getGroup() {
        return LEAF_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getName() {
        // Intentionally same as group, as currently it is the only one
        return LEAF_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final VirtualMapState state) {
        this.virtualMap = state.getRoot();
        this.vds = (MerkleDbDataSource) virtualMap.getDataSource();
        this.keyToPath = vds.getKeyToPath();

        this.pathToDiskLocationLeafNodes = vds.getPathToDiskLocationLeafNodes();
        this.keyValueStore = vds.getKeyValueStore();

        this.firstLeafPath = vds.getFirstLeafPath();
        this.lastLeafPath = vds.getLastLeafPath();
        this.hashChunkHeight = vds.getHashChunkHeight();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void processLeafBytes(long dataLocation, @NonNull final VirtualLeafBytes<?> leafBytes) {
        Objects.requireNonNull(virtualMap);
        Objects.requireNonNull(keyToPath);

        try {
            final Bytes keyBytes = leafBytes.keyBytes();
            final Bytes valueBytes = leafBytes.valueBytes();
            final long p2KvPath = leafBytes.path();
            final long k2pPath = keyToPath.get(keyBytes, -1);

            // Check path: P2KV path vs K2P path
            if (p2KvPath != k2pPath) {
                pathMismatchCount.incrementAndGet();
                log.error("Path mismatch. p2KvPath={} vs k2pPath={}", p2KvPath, k2pPath);
                return;
            }

            // Check value: stored value vs VirtualMap value
            if (!valueBytes.equals(virtualMap.getBytes(keyBytes))) {
                valueErrorCount.incrementAndGet();
                log.error("Value mismatch for path={}, value={}", p2KvPath, parseValue(valueBytes));
                return;
            }

            // Check leaf hash against the hash stored in the hash chunk
            final Hash leafHash = VirtualHasher.hashLeafRecord(leafBytes);
            final long hashChunkPath = VirtualHashChunk.pathToChunkPath(p2KvPath, hashChunkHeight);
            final VirtualHashChunk hashChunk;
            final VirtualHashChunk lastLoadedChunk = lastChunk.get();
            if ((lastLoadedChunk != null) && (lastLoadedChunk.path() == hashChunkPath)) {
                hashChunk = lastLoadedChunk;
            } else {
                final long hashChunkId = VirtualHashChunk.chunkPathToChunkId(hashChunkPath, hashChunkHeight);
                hashChunk = vds.loadHashChunk(hashChunkId);
                lastChunk.set(hashChunk);
            }
            final Hash storedHash = hashChunk.calcHash(p2KvPath, firstLeafPath, lastLeafPath);
            if (!leafHash.equals(storedHash)) {
                hashMismatchCount.incrementAndGet();
                log.error("Leaf hash mismatch at path={}. calculated={} vs stored={}", p2KvPath, leafHash, storedHash);
                return;
            }

            final long dataLocationFromIndex = pathToDiskLocationLeafNodes.get(p2KvPath);
            if (dataLocationFromIndex != dataLocation) {
                indexMismatchCount.incrementAndGet();
                log.error(
                        "Index data location mismatch at path={}. diskLocationFromIterator={} vs indexLocation={}",
                        p2KvPath,
                        dataLocation,
                        dataLocationFromIndex);
                return;
            }

            final VirtualLeafBytes<?> leafBytesFromStore;
            try {
                final BufferedData rawStoreBytes = keyValueStore.get(p2KvPath);
                if (rawStoreBytes == null) {
                    storeMismatchCount.incrementAndGet(); // New counter for data retrieval issues
                    log.error("Store cross-check failed: keyValueStore returned null for path={}", p2KvPath);
                    return;
                }
                // read canonical bytes directly from the store and parse
                leafBytesFromStore = VirtualLeafBytes.parseFrom((ReadableSequentialData) rawStoreBytes);
            } catch (RuntimeException ex) {
                storeMismatchCount.incrementAndGet();
                log.error(
                        "Index cross-check failed: unable to parse bytes from store for path={}. error={}",
                        p2KvPath,
                        ex.getMessage());
                return;
            }

            if (!leafBytesFromStore.equals(leafBytes)) {
                storeMismatchCount.incrementAndGet();
                log.error(
                        "Index cross-check mismatch at path={}. iteratorBytes vs storeBytes differ (diskValueLen={} storeValueLen={})",
                        p2KvPath,
                        valueBytes == null ? -1 : valueBytes.length(),
                        leafBytesFromStore.valueBytes() == null
                                ? -1
                                : leafBytesFromStore.valueBytes().length());
                return;
            }

            successCount.incrementAndGet();
        } catch (IOException e) {
            exceptionCount.incrementAndGet();
            printFileDataLocationError(log, e.getMessage(), dataLocation);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        } finally {
            processedCount.incrementAndGet();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        log.info("Checked {} VirtualLeafBytes entries", processedCount.get());

        final long leafCount = lastLeafPath - firstLeafPath + 1;

        final boolean ok = successCount.get() == leafCount
                && pathMismatchCount.get() == 0
                && valueErrorCount.get() == 0
                && hashMismatchCount.get() == 0
                && indexMismatchCount.get() == 0
                && storeMismatchCount.get() == 0
                && exceptionCount.get() == 0;

        if (!ok) {
            throw new ValidationException(
                    getName(),
                    ("%s validation failed. "
                                    + "successCount=%d vs expectedCount=%d, "
                                    + "pathMismatchCount=%d, valueErrorCount=%d, hashMismatchCount=%d, "
                                    + "indexMismatchCount=%d, exceptionCount=%d")
                            .formatted(
                                    getName(),
                                    successCount.get(),
                                    leafCount,
                                    pathMismatchCount.get(),
                                    valueErrorCount.get(),
                                    hashMismatchCount.get(),
                                    indexMismatchCount.get(),
                                    storeMismatchCount.get(),
                                    exceptionCount.get()));
        }
    }

    private static StateValue parseValue(Bytes valueBytes) throws ParseException {
        return StateValue.PROTOBUF.parse(valueBytes);
    }
}

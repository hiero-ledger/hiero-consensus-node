// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import com.hedera.statevalidation.util.ConfigUtils;
import com.hedera.statevalidation.util.StateUtils;
import com.hedera.statevalidation.validator.util.ValidationException;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.internal.MerkleDbDataSource;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.MerkleHasher;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;

/**
 * @see HashChunkValidator
 */
public class HashChunkIntegrityValidator implements HashChunkValidator {

    private static final Logger log = LogManager.getLogger(HashChunkIntegrityValidator.class);

    public static final String INTERNAL_GROUP = "internal";

    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong exceptionCount = new AtomicLong(0);
    private final AtomicLong idMismatchCount = new AtomicLong(0);
    private final AtomicLong pathMismatchCount = new AtomicLong(0);
    private final AtomicLong hashMismatchCount = new AtomicLong(0);
    private final AtomicLong chunkHeightMismatchCount = new AtomicLong(0);

    private VirtualMap virtualMap;
    private MerkleDbDataSource vds;
    private long firstLeafPath;
    private long lastLeafPath;
    private int hashChunkHeight;

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getGroup() {
        return INTERNAL_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getName() {
        // Intentionally same as group, as currently it is the only one
        return INTERNAL_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final VirtualMapState state) {
        this.virtualMap = state.getRoot();
        this.vds = (MerkleDbDataSource) virtualMap.getDataSource();
        this.firstLeafPath = vds.getFirstLeafPath();
        this.lastLeafPath = vds.getLastLeafPath();
        this.hashChunkHeight = vds.getHashChunkHeight();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void processHashChunk(@NonNull final VirtualHashChunk hashChunk) {
        try {
            final long chunkId = hashChunk.getChunkId();
            final long hashChunkPath = hashChunk.path();

            final long expectedChunkPath = VirtualHashChunk.chunkIdToChunkPath(chunkId, hashChunkHeight);
            if (expectedChunkPath != hashChunkPath) {
                pathMismatchCount.incrementAndGet();
                log.error(
                        "Path mismatch for chunk ID={}. expected={} vs hash chunk={}",
                        chunkId,
                        expectedChunkPath,
                        hashChunkPath);
                return;
            }

            if (hashChunkHeight != hashChunk.height()) {
                chunkHeightMismatchCount.incrementAndGet();
                log.error(
                        "Height mismatch for chunk ID={}. From data source={} vs from hash chunk={}",
                        chunkId,
                        hashChunkHeight,
                        hashChunk.height());
                return;
            }

            final Hash calculatedChunkHash =
                    hashChunk.chunkRootHash(MerkleHasher.threadSafeDefault(), firstLeafPath, lastLeafPath);

            if (chunkId == 0) {
                // The root chunk. Compare the hash with the hash of the snapshot as loaded. The state root
                // hash can't be used, as it may be changed by service migrations applied after loading
                final Hash originalHash = StateUtils.getOriginalStateHash();
                if (!calculatedChunkHash.equals(originalHash)) {
                    hashMismatchCount.incrementAndGet();
                    log.error(
                            "Hash mismatch for root chunk. calculated={} vs original state root={}",
                            calculatedChunkHash,
                            originalHash);
                }
            } else {
                // Find the parent chunk that contains the hash for hashChunkPath
                final long parentChunkPath = VirtualHashChunk.pathToChunkPath(hashChunkPath, hashChunkHeight);
                final long parentChunkId = VirtualHashChunk.chunkPathToChunkId(parentChunkPath, hashChunkHeight);

                // Load the parent chunk
                final VirtualHashChunk parentChunk = vds.loadHashChunk(parentChunkId);

                // The hashChunkPath is at the parent chunk's last rank, so we can use getHashAtPath
                final Hash storedHash = parentChunk.getHashAtPath(hashChunkPath);

                // Compare the calculated hash with the stored hash
                if (!calculatedChunkHash.equals(storedHash)) {
                    hashMismatchCount.incrementAndGet();
                    log.error(
                            "Hash mismatch for chunk ID {}. calculated={} vs stored={}",
                            chunkId,
                            calculatedChunkHash,
                            storedHash);
                    return;
                }
            }
            successCount.incrementAndGet();
        } catch (final Exception e) {
            exceptionCount.incrementAndGet();
            log.error("Error processing chunk ID={}", hashChunk.getChunkId(), e);
        } finally {
            processedCount.incrementAndGet();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        processCachedHashChunks();

        log.info("Checked {} VirtualHashChunk entries", processedCount.get());

        final long expectedCount = vds.getIdToDiskLocationHashChunks().size();
        final boolean ok = successCount.get() == expectedCount
                && exceptionCount.get() == 0
                && idMismatchCount.get() == 0
                && pathMismatchCount.get() == 0
                && hashMismatchCount.get() == 0
                && chunkHeightMismatchCount.get() == 0;

        if (!ok) {
            throw new ValidationException(
                    getName(),
                    ("%s validation failed. "
                                    + "successCount=%d vs expectedCount=%d, "
                                    + "idMismatchCount=%d, pathMismatchCount=%d, hashMismatchCount=%d, "
                                    + "chunkHeightMismatchCount=%d, exceptionCount=%d")
                            .formatted(
                                    getName(),
                                    successCount.get(),
                                    expectedCount,
                                    idMismatchCount.get(),
                                    pathMismatchCount.get(),
                                    hashMismatchCount.get(),
                                    chunkHeightMismatchCount.get(),
                                    exceptionCount.get()));
        }
    }

    /**
     * Processes hash chunks that are stored in the data source in-memory cache only, but not in
     * data files. Such chunks aren't seen by the data pipeline. Chunks with IDs below {@link
     * MerkleDbConfig#hashChunkCacheThreshold()} are written to the cache rather than to disk on
     * flushes, and they are only written to disk when a snapshot is taken. It happens, for example,
     * when hashes are recalculated at load time, because the snapshot hash digest type differs
     * from the default one. Such chunks have no disk location in the index.
     */
    private void processCachedHashChunks() {
        final LongList index = vds.getIdToDiskLocationHashChunks();
        final int cacheThreshold =
                ConfigUtils.getConfiguration().getConfigData(MerkleDbConfig.class).hashChunkCacheThreshold();
        final long maxCachedChunkId = Math.min(index.size(), cacheThreshold);
        long cachedCount = 0;
        for (long chunkId = 0; chunkId < maxCachedChunkId; chunkId++) {
            if (index.get(chunkId, LongList.IMPERMISSIBLE_VALUE) != LongList.IMPERMISSIBLE_VALUE) {
                // The chunk is on disk, it's already processed by the pipeline
                continue;
            }
            try {
                final VirtualHashChunk hashChunk = vds.loadHashChunk(chunkId);
                if (hashChunk == null) {
                    throw new IllegalStateException("Hash chunk not found");
                }
                processHashChunk(hashChunk);
                cachedCount++;
            } catch (final Exception e) {
                // Neither on disk nor in cache
                exceptionCount.incrementAndGet();
                processedCount.incrementAndGet();
                log.error("Error processing cached chunk ID={}", chunkId, e);
            }
        }
        if (cachedCount > 0) {
            log.info("Checked {} VirtualHashChunk entries from the data source cache", cachedCount);
        }
    }
}

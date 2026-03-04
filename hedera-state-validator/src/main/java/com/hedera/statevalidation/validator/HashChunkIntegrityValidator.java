// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import com.hedera.statevalidation.validator.util.ValidationAssertions;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
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
    private final AtomicLong nullHashChunkCount = new AtomicLong(0);

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
            //noinspection ConstantValue
            if (hashChunk == null) {
                nullHashChunkCount.incrementAndGet();
                return;
            }
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

            final Hash calculatedChunkHash = hashChunk.chunkRootHash(firstLeafPath, lastLeafPath);

            if (chunkId == 0) {
                // The root chunk. Compare the hash with VM root hash
                if (!calculatedChunkHash.equals(virtualMap.getHash())) {
                    hashMismatchCount.incrementAndGet();
                    log.error(
                            "Hash mismatch for root chunk. calculated={} vs VM root={}",
                            calculatedChunkHash,
                            virtualMap.getHash());
                }
            } else {
                // Find the parent chunk that contains the hash for hashChunkPath
                final long parentChunkPath = VirtualHashChunk.pathToChunkPath(hashChunkPath, hashChunkHeight);
                final long parentChunkId = VirtualHashChunk.chunkPathToChunkId(parentChunkPath, hashChunkHeight);

                // Load the parent chunk
                final VirtualHashChunk parentChunk = vds.loadHashChunk(parentChunkId);
                if (parentChunk == null) {
                    nullHashChunkCount.incrementAndGet();
                    log.error("Chunk with ID {} is not found", parentChunkId);
                    return;
                }

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
        } catch (IOException e) {
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
        log.info("Checked {} VirtualHashChunk entries", processedCount.get());

        final long expectedCount = vds.getIdToDiskLocationHashChunks().size();
        final boolean ok = successCount.get() == expectedCount
                && exceptionCount.get() == 0
                && idMismatchCount.get() == 0
                && pathMismatchCount.get() == 0
                && hashMismatchCount.get() == 0
                && chunkHeightMismatchCount.get() == 0
                && nullHashChunkCount.get() == 0;

        ValidationAssertions.requireTrue(
                ok,
                getName(),
                ("%s validation failed. "
                                + "successCount=%d vs expectedCount=%d, "
                                + "idMismatchCount=%d, pathMismatchCount=%d, hashMismatchCount=%d, "
                                + "chunkHeightMismatchCount=%d, exceptionCount=%d, nullHashChunkCount=%d")
                        .formatted(
                                getName(),
                                successCount.get(),
                                expectedCount,
                                idMismatchCount.get(),
                                pathMismatchCount.get(),
                                hashMismatchCount.get(),
                                chunkHeightMismatchCount.get(),
                                exceptionCount.get(),
                                nullHashChunkCount.get()));
    }
}

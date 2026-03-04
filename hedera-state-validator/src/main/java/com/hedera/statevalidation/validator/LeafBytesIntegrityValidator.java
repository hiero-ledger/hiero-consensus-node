// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import static com.hedera.statevalidation.util.LogUtils.printFileDataLocationError;

import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.validator.util.ValidationAssertions;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;

/**
 * @see LeafBytesValidator
 */
public class LeafBytesIntegrityValidator implements LeafBytesValidator {

    private static final Logger log = LogManager.getLogger(LeafBytesIntegrityValidator.class);

    private static final ThreadLocal<WritableMessageDigest> MESSAGE_DIGEST =
            ThreadLocal.withInitial(() -> new WritableMessageDigest(Cryptography.DEFAULT_DIGEST_TYPE.buildDigest()));

    public static final String LEAF_GROUP = "leaf";

    private VirtualMap virtualMap;
    private MerkleDbDataSource vds;
    private HalfDiskHashMap keyToPath;
    private long firstLeafPath;
    private long lastLeafPath;
    private int hashChunkHeight;

    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong exceptionCount = new AtomicLong(0);
    private final AtomicLong pathMismatchCount = new AtomicLong(0);
    private final AtomicLong valueErrorCount = new AtomicLong(0);
    private final AtomicLong hashMismatchCount = new AtomicLong(0);
    private final AtomicLong nullHashChunkCount = new AtomicLong(0);

    // A minor optimization to avoid multiple chunk loads from disk
    final AtomicReference<VirtualHashChunk> lastChunk = new AtomicReference<>();

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
            final Hash leafHash = hashLeafRecord(leafBytes);
            final long hashChunkPath = VirtualHashChunk.pathToChunkPath(p2KvPath, hashChunkHeight);
            final VirtualHashChunk hashChunk;
            final VirtualHashChunk lastLoadedChunk = lastChunk.get();
            if ((lastLoadedChunk != null) && (lastLoadedChunk.path() == hashChunkPath)) {
                hashChunk = lastLoadedChunk;
            } else {
                final long hashChunkId = VirtualHashChunk.chunkPathToChunkId(hashChunkPath, hashChunkHeight);
                hashChunk = vds.loadHashChunk(hashChunkId);
                if (hashChunk == null) {
                    nullHashChunkCount.incrementAndGet();
                    log.error("Hash chunk with ID {} is not found for leaf path={}", hashChunkId, p2KvPath);
                    return;
                }
                lastChunk.compareAndSet(lastLoadedChunk, hashChunk);
            }
            final Hash storedHash = hashChunk.calcHash(p2KvPath, firstLeafPath, lastLeafPath);
            if (!leafHash.equals(storedHash)) {
                hashMismatchCount.incrementAndGet();
                log.error("Leaf hash mismatch at path={}. calculated={} vs stored={}", p2KvPath, leafHash, storedHash);
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
                && nullHashChunkCount.get() == 0
                && exceptionCount.get() == 0;
        ValidationAssertions.requireTrue(
                ok,
                getName(),
                ("%s validation failed. "
                                + "successCount=%d vs expectedCount=%d, "
                                + "pathMismatchCount=%d, valueErrorCount=%d, hashMismatchCount=%d, "
                                + "nullHashChunkCount=%d, exceptionCount=%d, successCount=%d")
                        .formatted(
                                getName(),
                                successCount.get(),
                                leafCount,
                                pathMismatchCount.get(),
                                valueErrorCount.get(),
                                hashMismatchCount.get(),
                                nullHashChunkCount.get(),
                                exceptionCount.get(),
                                successCount.get()));
    }

    /**
     * Computes the hash of a leaf record. May be called from multiple threads in parallel.
     *
     * @param leaf the leaf bytes to hash
     * @return the computed hash
     */
    private static Hash hashLeafRecord(final VirtualLeafBytes<?> leaf) {
        final WritableMessageDigest wmd = MESSAGE_DIGEST.get();
        leaf.writeToForHashing(wmd);
        // Calling digest() resets the digest
        return new Hash(wmd.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
    }

    private static StateValue parseValue(Bytes valueBytes) throws ParseException {
        return StateValue.PROTOBUF.parse(valueBytes);
    }
}

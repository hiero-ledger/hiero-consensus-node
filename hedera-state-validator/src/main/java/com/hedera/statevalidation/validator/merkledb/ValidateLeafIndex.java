// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.merkledb;

import static com.hedera.statevalidation.util.LogUtils.printFileDataLocationError;
import static com.hedera.statevalidation.util.ParallelProcessingUtils.processRange;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.report.SlackReportGenerator;
import com.hedera.statevalidation.util.junit.MerkleNodeStateResolver;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.IOException;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@SuppressWarnings("NewClassNamingConvention")
@ExtendWith({MerkleNodeStateResolver.class, SlackReportGenerator.class})
@Tag("leaf")
public class ValidateLeafIndex {

    private static final Logger log = LogManager.getLogger(ValidateLeafIndex.class);

    private static final ThreadLocal<WritableMessageDigest> MESSAGE_DIGEST =
            ThreadLocal.withInitial(() -> new WritableMessageDigest(Cryptography.DEFAULT_DIGEST_TYPE.buildDigest()));

    @Test
    public void validateIndex(final VirtualMapState virtualMapState) {
        final VirtualMap virtualMap = virtualMapState.getRoot();
        assertNotNull(virtualMap);
        MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();

        if (vds.getFirstLeafPath() == -1) {
            log.info("Skipping the validation for {} as the map is empty", virtualMap.getLabel());
            return;
        }

        log.debug(vds.getHashChunkStore().getFilesSizeStatistics());

        long firstLeafPath = vds.getFirstLeafPath();
        long lastLeafPath = vds.getLastLeafPath();
        int hashChunkHeight = vds.getHashChunkHeight();

        var leafNodeIndex = vds.getPathToDiskLocationLeafNodes();
        var objectKeyToPath = vds.getKeyToPath();
        var leafDfc = vds.getKeyValueStore().getFileCollection();

        assertEquals(lastLeafPath, leafNodeIndex.size() - 1);

        // iterate over internalNodeIndex and validate it
        ForkJoinTask<?> emptyIndexTask =
                processRange(0, firstLeafPath, path -> assertEquals(0, leafNodeIndex.get(path)));

        var nullErrorCount = new AtomicInteger(0);
        var exceptionCount = new AtomicInteger(0);
        var successCount = new AtomicInteger(0);

        // A minor optimization to avoid multiple chunk loads from disk
        final AtomicReference<VirtualHashChunk> lastChunk = new AtomicReference<>();

        LongConsumer indexProcessor = path -> {
            long dataLocation = leafNodeIndex.get(path, -1);
            assertNotEquals(-1, dataLocation);
            // read from dataLocation using datasource
            try {
                var data = leafDfc.readDataItem(dataLocation);
                if (data != null) {
                    final VirtualLeafBytes<?> leafRecord = VirtualLeafBytes.parseFrom(data);
                    // Check the path
                    assertEquals(leafRecord.path(), path);
                    // Check the key in HDHM
                    Bytes keyBytes = leafRecord.keyBytes();
                    long actual = objectKeyToPath.get(leafRecord.keyBytes(), -1);
                    assertEquals(path, actual);
                    // Check the value
                    assertEquals(leafRecord.valueBytes(), virtualMap.getBytes(keyBytes));
                    // Check the hash
                    final Hash leafHash = hashLeafRecord(leafRecord);
                    final long hashChunkPath = VirtualHashChunk.pathToChunkPath(path, hashChunkHeight);
                    final VirtualHashChunk hashChunk;
                    final VirtualHashChunk lastLoadedChunk = lastChunk.get();
                    if ((lastLoadedChunk != null) && (lastLoadedChunk.path() == hashChunkPath)) {
                        hashChunk = lastLoadedChunk;
                    } else {
                        final long hashChunkId = VirtualHashChunk.chunkPathToChunkId(hashChunkPath, hashChunkHeight);
                        hashChunk = vds.loadHashChunk(hashChunkId);
                        assertNotNull(hashChunk, "Hash chunk with ID " + hashChunkId + " is not found");
                        lastChunk.compareAndSet(lastLoadedChunk, hashChunk);
                    }
                    assertEquals(
                            leafHash,
                            hashChunk.calcHash(path, firstLeafPath, lastLeafPath),
                            "Leaf hash mismatch at path " + path);
                    // If all assertions passed, increment the number of successfully processed leaves
                    successCount.incrementAndGet();
                } else {
                    nullErrorCount.incrementAndGet();
                    printFileDataLocationError(log, "Missing entry on disk!", leafDfc, dataLocation);
                }
            } catch (IOException e) {
                exceptionCount.incrementAndGet();
                printFileDataLocationError(log, e.getMessage(), leafDfc, dataLocation);
            }
        };

        ForkJoinTask<?> nonEmptyIndexTask = processRange(firstLeafPath, lastLeafPath + 1, indexProcessor);
        emptyIndexTask.join();
        nonEmptyIndexTask.join();

        log.debug("size of index: {}", leafNodeIndex.size());
        final long leafCount = lastLeafPath - firstLeafPath + 1;
        assertEquals(
                leafCount,
                successCount.get(),
                "Not all leaves were validated successfully, exp: " + leafCount + ", act: " + successCount.get());
        assertEquals(
                0,
                nullErrorCount.get(),
                "Some entries on disk are missing even though pointers are present in the index");
        assertEquals(0, exceptionCount.get(), "Some read operations failed");
        log.info("Successfully checked {} entries", successCount.get());
    }

    // May be called on multiple threads in parallel
    private static Hash hashLeafRecord(final VirtualLeafBytes<?> leaf) {
        final WritableMessageDigest wmd = ValidateLeafIndex.MESSAGE_DIGEST.get();
        leaf.writeToForHashing(wmd);
        // Calling digest() resets the digest
        return new Hash(wmd.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.merkledb;

import static com.hedera.statevalidation.util.LogUtils.printFileDataLocationError;
import static com.hedera.statevalidation.util.ParallelProcessingUtils.processRange;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.statevalidation.report.SlackReportGenerator;
import com.hedera.statevalidation.util.junit.MerkleNodeStateResolver;
import com.hedera.statevalidation.util.reflect.MemoryIndexDiskKeyValueStoreAccessor;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * This tests validates the index for internal nodes of a virtual map.
 * It verifies that all the index pointers are pointing to valid data entries containing hashes.
 */
@SuppressWarnings("NewClassNamingConvention")
@ExtendWith({MerkleNodeStateResolver.class, SlackReportGenerator.class})
@Tag("internal")
public class ValidateInternalIndex {

    private static final Logger log = LogManager.getLogger(ValidateInternalIndex.class);

    @Test
    public void validateIndex(final MerkleNodeState merkleNodeState) {
        final VirtualMap virtualMap = (VirtualMap) merkleNodeState.getRoot();
        assertNotNull(virtualMap);
        MerkleDbDataSource dataSource = (MerkleDbDataSource) virtualMap.getDataSource();

        if (dataSource.getFirstLeafPath() == -1) {
            log.info("Skipping the validation for {} as the map is empty", virtualMap.getLabel());
            return;
        }

        final long firstLeafPath = dataSource.getFirstLeafPath();
        final long lastLeafPath = dataSource.getLastLeafPath();
        final int hashChunkHeight = dataSource.getHashChunkHeight();
        final long lastChunkId = VirtualHashChunk.lastChunkIdForPaths(lastLeafPath, hashChunkHeight);

        final LongList hashChunkIndex = dataSource.getIdToDiskLocationHashChunks();
        final var hashChunkStore = new MemoryIndexDiskKeyValueStoreAccessor(dataSource.getHashChunkStore());
        final DataFileCollection dfc = hashChunkStore.getFileCollection();
        log.debug("Size of hash chunk index: {}", hashChunkIndex.size());

        final var dataReadErrorCount = new AtomicInteger(0);
        final var idMismatchCount = new AtomicInteger(0);
        final var pathMismatchCount = new AtomicInteger(0);
        final var hashMismatchCount = new AtomicInteger(0);
        final var exceptionCount = new AtomicInteger(0);
        final var successCount = new AtomicLong(0);

        LongConsumer indexProcessor = chunkId -> {
            final long dataLocation = hashChunkIndex.get(chunkId, -1);
            if (dataLocation == -1) {
                log.error("Missing index entry for chunk ID {}", chunkId);
                dataReadErrorCount.incrementAndGet();
                return;
            }
            try {
                final BufferedData data = dfc.readDataItem(dataLocation);
                if (data == null) {
                    printFileDataLocationError(log, "Missing entry on disk!", dfc, dataLocation);
                    dataReadErrorCount.incrementAndGet();
                    return;
                }

                final VirtualHashChunk hashChunk = VirtualHashChunk.parseFrom(data);
                if (hashChunk == null) {
                    log.error("Failed to parse hash chunk at data location {}", dataLocation);
                    dataReadErrorCount.incrementAndGet();
                    return;
                }
                if (hashChunk.getChunkId() != chunkId) {
                    log.error("Chunk ID mismatch: expected {}, got {}", chunkId, hashChunk.getChunkId());
                    idMismatchCount.incrementAndGet();
                    return;
                }

                final long hashChunkPath = hashChunk.path();
                final Hash calculatedChunkHash = hashChunk.chunkRootHash(firstLeafPath, lastLeafPath);

                // Find the parent chunk that contains the hash for hashChunkPath
                final long parentChunkPath = VirtualHashChunk.pathToChunkPath(hashChunkPath, hashChunkHeight);
                final long parentChunkId = VirtualHashChunk.chunkPathToChunkId(parentChunkPath, hashChunkHeight);

                // Load the parent chunk
                final VirtualHashChunk parentChunk = dataSource.loadHashChunk(parentChunkId);
                if (parentChunk == null) {
                    log.error("Failed to load parent chunk ID {} for child chunk ID {}", parentChunkId, chunkId);
                    dataReadErrorCount.incrementAndGet();
                    return;
                }
                if (parentChunk.path() != parentChunkPath) {
                    log.error("Parent chunk path mismatch: expected {}, got {}", parentChunkPath, parentChunk.path());
                    pathMismatchCount.incrementAndGet();
                    return;
                }
                if (parentChunk.getChunkId() != parentChunkId) {
                    log.error("Parent chunk ID mismatch: expected {}, got {}", parentChunkId, parentChunk.getChunkId());
                    idMismatchCount.incrementAndGet();
                    return;
                }

                // The hashChunkPath is at the parent chunk's last rank, so we can use getHashAtPath
                final Hash storedHash = parentChunk.getHashAtPath(hashChunkPath);

                // Compare the calculated hash with the stored hash
                if (!calculatedChunkHash.equals(storedHash)) {
                    log.error(
                            "Hash mismatch for chunk ID {}! Calculated: {}, Stored in parent (chunk ID {}): {}",
                            chunkId,
                            calculatedChunkHash,
                            parentChunkId,
                            storedHash);
                    hashMismatchCount.incrementAndGet();
                    return;
                }

                successCount.incrementAndGet();
            } catch (Exception e) {
                printFileDataLocationError(log, e.getMessage(), dfc, dataLocation);
                exceptionCount.incrementAndGet();
            }
        };

        // Process chunks 1 to lastChunkId in parallel (skip root chunk 0 as it has no parent)
        ForkJoinTask<?> chunkValidationTask = processRange(1, lastChunkId + 1, indexProcessor);
        chunkValidationTask.join();

        // Also validate root chunk (ID 0)
        try {
            final VirtualHashChunk rootChunk = dataSource.loadHashChunk(0);
            if (rootChunk == null) {
                log.error("Failed to load root chunk (ID 0)");
                dataReadErrorCount.incrementAndGet();
            } else {
                final Hash rootHash = rootChunk.chunkRootHash(firstLeafPath, lastLeafPath);
                if (rootHash == null) {
                    log.error("Failed to calculate root hash for root chunk");
                    dataReadErrorCount.incrementAndGet();
                } else if (!rootHash.equals(virtualMap.getHash())) {
                    log.error("Root hash mismatch! Calculated: {}, VirtualMap hash: {}", rootHash, virtualMap.getHash());
                    hashMismatchCount.incrementAndGet();
                } else {
                    successCount.incrementAndGet();
                }
            }
        } catch (Exception e) {
            log.error("Exception while validating root chunk: {}", e.getMessage(), e);
            exceptionCount.incrementAndGet();
        }

        assertEquals(
                0,
                dataReadErrorCount.get(),
                "Some entries on disk are missing even though pointers are present in the index");
        assertEquals(0, idMismatchCount.get(), "Some chunks have mismatched IDs");
        assertEquals(0, pathMismatchCount.get(), "Some chunks have mismatched paths");
        assertEquals(0, hashMismatchCount.get(), "Some chunk hashes do not match their parent's stored hash");
        assertEquals(0, exceptionCount.get(), "Some operations failed with exceptions");

        final long expectedChunkCount = lastChunkId + 1;
        assertEquals(
                expectedChunkCount,
                successCount.get(),
                "Not all chunks were validated successfully. Expected: " + expectedChunkCount
                        + ", Actual: " + successCount.get());

        log.info("Successfully validated {} chunks (including root)", successCount.get());
    }
}

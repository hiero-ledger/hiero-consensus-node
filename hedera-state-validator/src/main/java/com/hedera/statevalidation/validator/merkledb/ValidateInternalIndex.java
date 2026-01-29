// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.merkledb;

import static com.hedera.statevalidation.util.LogUtils.printFileDataLocationError;
import static com.hedera.statevalidation.util.ParallelProcessingUtils.processRange;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.statevalidation.report.SlackReportGenerator;
import com.hedera.statevalidation.util.junit.MerkleNodeStateResolver;
import com.hedera.statevalidation.util.reflect.MemoryIndexDiskKeyValueStoreAccessor;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;
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

        var hashChunkIndex = dataSource.getIdToDiskLocationHashChunks();
        var hashChunkStore = new MemoryIndexDiskKeyValueStoreAccessor(dataSource.getHashChunkStore());
        var dfc = hashChunkStore.getFileCollection();
        log.debug("Size of hash chunk index: {}", hashChunkIndex.size());

        var successCount = new AtomicInteger(0);
        var nullErrorCount = new AtomicInteger(0);
        var exceptionCount = new AtomicInteger(0);

        LongConsumer indexProcessor = chunkId -> {
            final long dataLocation = hashChunkIndex.get(chunkId, -1);
            // read from dataLocation using datasource
            assertNotEquals(-1, dataLocation);
            try {
                var data = dfc.readDataItem(dataLocation);
                if (data == null) {
                    printFileDataLocationError(log, "Missing entry on disk!", dfc, dataLocation);
                    nullErrorCount.incrementAndGet();
                } else {
                    var hashChunk = VirtualHashChunk.parseFrom(data);
                    final long hashChunkPath = hashChunk.path();
                    assertNotNull(hashChunk);
                    assertEquals(hashChunk.getChunkId(), chunkId);
                    assertEquals(hashChunk.path(), hashChunkPath);

                    final Hash calculatedChunkHash = hashChunk.chunkRootHash(firstLeafPath, lastLeafPath);
                    assertNotNull(calculatedChunkHash);

                    // Find the parent chunk that contains the hash for hashChunkPath
                    final long parentChunkPath = VirtualHashChunk.pathToChunkPath(hashChunkPath, hashChunkHeight);
                    final long parentChunkId = VirtualHashChunk.chunkPathToChunkId(parentChunkPath, hashChunkHeight);

                    // Load the parent chunk
                    final VirtualHashChunk parentChunk = dataSource.loadHashChunk(parentChunkId);
                    assertNotNull(parentChunk);
                    assertEquals(parentChunk.getChunkId(), parentChunkId);
                    assertEquals(parentChunk.path(), parentChunkPath);

                    // The hashChunkPath is at the parent chunk's last rank, so we can use getHashAtPath
                    final Hash storedHash = parentChunk.getHashAtPath(hashChunkPath);
                    assertNotNull(storedHash);

                    // Compare the calculated hash with the stored hash
                    assertEquals(calculatedChunkHash, storedHash, "Hash mismatch for chunk ID " + chunkId);

                    successCount.incrementAndGet();
                }
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
            assertNotNull(rootChunk);
            final Hash rootHash = rootChunk.chunkRootHash(firstLeafPath, lastLeafPath);
            assertNotNull(rootHash);
            assertEquals(rootHash, virtualMap.getHash());
            successCount.incrementAndGet();
        } catch (Exception e) {
            log.error("Exception while validating root chunk: {}", e.getMessage(), e);
            exceptionCount.incrementAndGet();
        }

        assertEquals(
                0,
                nullErrorCount.get(),
                "Some entries on disk are missing even though pointers are present in the index");
        assertEquals(0, exceptionCount.get(), "Some read from disk operations failed");
        log.info("Successfully validated {} chunks (including root)", successCount.get());
    }
}

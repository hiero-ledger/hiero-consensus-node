// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.merkledb;

import static com.hedera.statevalidation.util.LogUtils.printFileDataLocationError;
import static com.hedera.statevalidation.util.ParallelProcessingUtils.processRange;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.statevalidation.report.SlackReportGenerator;
import com.hedera.statevalidation.util.junit.MerkleNodeStateResolver;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import java.io.IOException;
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
    public void validateIndex(final VirtualMapState virtualMapState) {
        final VirtualMap virtualMap = virtualMapState.getRoot();
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
        final DataFileCollection dfc = dataSource.getHashChunkStore().getFileCollection();
        log.debug("Size of hash chunk index: {}", hashChunkIndex.size());

        final var onDiskExceptionCount = new AtomicInteger(0);
        final var nullErrorCount = new AtomicInteger(0);
        final var successCount = new AtomicLong(0);

        LongConsumer indexProcessor = chunkId -> {
            final long dataLocation = hashChunkIndex.get(chunkId, -1);
            assertNotEquals(-1, dataLocation);
            try {
                final BufferedData data = dfc.readDataItem(dataLocation);
                if (data == null) {
                    nullErrorCount.incrementAndGet();
                    printFileDataLocationError(log, "Missing entry on disk!", dfc, dataLocation);
                    return;
                }
                final VirtualHashChunk hashChunk = VirtualHashChunk.parseFrom(data, hashChunkHeight);
                assertNotNull(hashChunk);
                final long expectedChunkPath = VirtualHashChunk.chunkIdToChunkPath(chunkId, hashChunkHeight);
                assertEquals(expectedChunkPath, hashChunk.path(), "Wrong chunk path");
                assertEquals(hashChunk.getChunkId(), chunkId, "Wrong chunk ID");
                assertEquals(hashChunkHeight, hashChunk.height(), "Wrong chunk height");

                final long hashChunkPath = hashChunk.path();
                final Hash calculatedChunkHash = hashChunk.chunkRootHash(firstLeafPath, lastLeafPath);
                if (chunkId == 0) {
                    // The root chunk. Compare the hash with VM root hash
                    assertEquals(calculatedChunkHash, virtualMap.getHash(), "Hash mismatch for root chunk");
                } else {
                    // Find the parent chunk that contains the hash for hashChunkPath
                    final long parentChunkPath = VirtualHashChunk.pathToChunkPath(hashChunkPath, hashChunkHeight);
                    final long parentChunkId = VirtualHashChunk.chunkPathToChunkId(parentChunkPath, hashChunkHeight);

                    // Load the parent chunk
                    final VirtualHashChunk parentChunk = dataSource.loadHashChunk(parentChunkId);
                    assertNotNull(parentChunk, "Chunk with ID " + parentChunkId + " is not found");
                    assertEquals(parentChunk.path(), parentChunkPath);
                    assertEquals(parentChunk.getChunkId(), parentChunkId);

                    // The hashChunkPath is at the parent chunk's last rank, so we can use getHashAtPath
                    final Hash storedHash = parentChunk.getHashAtPath(hashChunkPath);

                    // Compare the calculated hash with the stored hash
                    assertEquals(calculatedChunkHash, storedHash, "Hash mismatch for chunk ID " + chunkId);
                }
                successCount.incrementAndGet();
            } catch (IOException e) {
                printFileDataLocationError(log, e.getMessage(), dfc, dataLocation);
                onDiskExceptionCount.incrementAndGet();
            }
        };

        final ForkJoinTask<?> chunkValidationTask = processRange(0, lastChunkId + 1, indexProcessor);
        chunkValidationTask.join();

        assertEquals(0, nullErrorCount.get(), "Some chunks are null");
        assertEquals(0, onDiskExceptionCount.get(), "Some operations failed with exceptions");

        final long chunkCount = lastChunkId + 1;
        assertEquals(
                chunkCount,
                successCount.get(),
                "Not all chunks were validated successfully, exp: " + chunkCount + ", act: " + successCount.get());

        log.info("Successfully validated {} chunks (including root)", successCount.get());
    }
}

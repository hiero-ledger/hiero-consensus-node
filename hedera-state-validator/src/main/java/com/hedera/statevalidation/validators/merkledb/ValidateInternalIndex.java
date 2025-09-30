// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.merkledb;

import static com.hedera.statevalidation.validators.ParallelProcessingUtil.doNothing;
import static com.hedera.statevalidation.validators.ParallelProcessingUtil.processRange;
import static com.hedera.statevalidation.validators.Utils.printFileDataLocationError;
import static com.swirlds.merkledb.collections.LongList.IMPERMISSIBLE_VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.statevalidation.merkledb.reflect.MemoryIndexDiskKeyValueStoreW;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import java.io.IOException;
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
@ExtendWith({StateResolver.class})
@Tag("internal")
public class ValidateInternalIndex {

    private static final Logger log = LogManager.getLogger(ValidateInternalIndex.class);

    @Test
    public void validateIndex(DeserializedSignedState deserializedState) {
        final MerkleNodeState merkleNodeState =
                deserializedState.reservedSignedState().get().getState();
        final VirtualMap virtualMap = (VirtualMap) merkleNodeState.getRoot();
        assertNotNull(virtualMap);
        MerkleDbDataSource dataSource = (MerkleDbDataSource) virtualMap.getDataSource();

        if (dataSource.getFirstLeafPath() == -1) {
            log.info("Skipping the validation for {} as the map is empty", virtualMap.getLabel());
            return;
        }

        var lastLeafPath = dataSource.getLastLeafPath();
        var hashChunkIndex = dataSource.getIdToDiskLocationHashChunks();
        var hashChunkStore = new MemoryIndexDiskKeyValueStoreW<>(dataSource.getHashChunkStore());
        var dfc = hashChunkStore.getFileCollection();

        var nullErrorCount = new AtomicInteger(0);
        var exceptionCount = new AtomicInteger(0);
        var successCount = new AtomicInteger(0);

        // iterate over internalNodeIndex and validate it
        LongConsumer indexProcessor = path -> {
            long dataLocation = hashChunkIndex.get(path, -1);
            // read from dataLocation using datasource
            assertNotEquals(-1, dataLocation);
            try {
                var data = dfc.readDataItem(dataLocation);
                if (data == null) {
                    printFileDataLocationError(log, "Missing entry on disk!", dfc, dataLocation);
                    nullErrorCount.incrementAndGet();
                } else {
                    var hashRecord = VirtualHashRecord.parseFrom(data);
                    assertEquals(hashRecord.path(), path);
                    assertNotNull(hashRecord.hash());
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                printFileDataLocationError(log, e.getMessage(), dfc, dataLocation);
                exceptionCount.incrementAndGet();
            }
        };

        log.debug("Size of index: " + hashChunkIndex.size());
        final int hashChunkHeight = dataSource.getHashChunkHeight();
        assertEquals(
                VirtualHashChunk.minChunkIdForPaths(lastLeafPath, hashChunkHeight),
                hashChunkIndex.size(),
                "Unexpected hash chunk index size");

        ForkJoinTask<?> onDiskTask = processRange(1, lastLeafPath, indexProcessor);
        onDiskTask.join();

        assertEquals(
                0,
                nullErrorCount.get(),
                "Some entries on disk are missing even though pointers are present in the index");
        assertEquals(0, exceptionCount.get(), "Some read from disk operations failed");
        log.debug("Successfully checked {} entries", successCount.get());
        // FUTURE WORK: record these in the reporting data structure
        // https://github.com/hashgraph/hedera-services/issues/7229
    }
}

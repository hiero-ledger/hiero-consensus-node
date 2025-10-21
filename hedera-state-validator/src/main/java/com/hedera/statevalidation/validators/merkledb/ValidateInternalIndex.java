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
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
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

        final long inMemoryHashThreshold;
        var lastLeafPath = dataSource.getLastLeafPath();
        var internalNodesIndex = dataSource.getPathToDiskLocationInternalNodes();
        var internalStore = new MemoryIndexDiskKeyValueStoreW<>(dataSource.getHashStoreDisk());
        var dfc = internalStore.getFileCollection();
        var pathToHashRam = dataSource.getHashStoreRam();
        var inMemoryExceptionCount = new AtomicInteger();

        final ForkJoinTask<?> inMemoryTask;
        if (pathToHashRam != null) {
            inMemoryHashThreshold = dataSource.getHashesRamToDiskThreshold();
            assertTrue(
                    pathToHashRam.size() <= inMemoryHashThreshold,
                    "The size of the pathToHashRam should be less than or equal to the in memory hash threshold");
            var rightBoundary = Math.min(inMemoryHashThreshold, lastLeafPath);
            if (inMemoryHashThreshold >= lastLeafPath) {
                assertEquals(0, internalNodesIndex.size(), "The size of the index should be 0");
                log.info(
                        "Skipping test for {} as the in memory hash threshold is greater than the last leaf path, so the index is not used",
                        virtualMap.getLabel());
                return;
            }
            LongConsumer inMemoryIndexProcessor = path -> {
                try {
                    Hash actual = pathToHashRam.get(path);
                    assertNotNull(actual, "The pathToHashRam should not be null");
                    assertNotEquals(actual, VirtualNodeCache.NULL_HASH, "The hash cannot be null hash");
                    assertEquals(IMPERMISSIBLE_VALUE, internalNodesIndex.get(path));
                } catch (IOException e) {
                    inMemoryExceptionCount.incrementAndGet();
                }
            };
            inMemoryTask = processRange(0, rightBoundary, inMemoryIndexProcessor);
            assertEquals(
                    lastLeafPath,
                    internalNodesIndex.size() - 1,
                    "The size of the index should be equal to the last leaf path");
        } else {
            inMemoryHashThreshold = 0;
            inMemoryTask = doNothing();
            assertEquals(
                    lastLeafPath + 1,
                    internalNodesIndex.size(),
                    "The size of the index should be equal to the difference between the last leaf path and the first leaf path in the index");
        }

        var nullErrorCount = new AtomicInteger(0);
        var onDiskExceptionCount = new AtomicInteger(0);
        var successCount = new AtomicInteger(0);

        // iterate over internalNodeIndex and validate it
        LongConsumer indexProcessor = path -> {
            long dataLocation = internalNodesIndex.get(path, -1);
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
                onDiskExceptionCount.incrementAndGet();
            }
        };

        log.debug("Size of index: " + internalNodesIndex.size());

        ForkJoinTask<?> onDiskTask = processRange(inMemoryHashThreshold, lastLeafPath, indexProcessor);
        inMemoryTask.join();
        onDiskTask.join();

        assertEquals(
                0,
                nullErrorCount.get(),
                "Some entries on disk are missing even though pointers are present in the index");
        assertEquals(0, inMemoryExceptionCount.get(), "Some read from memory operations failed");
        assertEquals(0, onDiskExceptionCount.get(), "Some read from disk operations failed");
        log.debug("Successfully checked {} entries", successCount.get());
        // FUTURE WORK: record these in the reporting data structure
        // https://github.com/hashgraph/hedera-services/issues/7229
    }
}

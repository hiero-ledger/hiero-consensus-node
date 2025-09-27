// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.merkledb;

import static com.hedera.statevalidation.validators.Utils.printFileDataLocationError;
import static com.hedera.statevalidation.validators.ValidationAssertions.requireEqual;
import static com.hedera.statevalidation.validators.ValidationAssertions.requireNotEqual;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.merkledb.reflect.MemoryIndexDiskKeyValueStoreW;
import com.hedera.statevalidation.validators.IndexValidator;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// WARNING: there is no iteration over internalNodeIndex now in poc!
@SuppressWarnings("NewClassNamingConvention")
public class ValidateLeafIndex implements IndexValidator {

    private static final Logger log = LogManager.getLogger(ValidateLeafIndex.class);

    private VirtualMap virtualMap;

    private LongList leafNodeIndex;

    private HalfDiskHashMap objectKeyToPath;

    private DataFileCollection leafDfc;

    public static final String LEAF = "leaf";

    private final AtomicInteger nullErrorCount = new AtomicInteger(0);

    private final AtomicInteger exceptionCount = new AtomicInteger(0);

    private final AtomicInteger successCount = new AtomicInteger(0);

    @Override
    public String getTag() {
        return LEAF;
    }

    @Override
    public void initialize(MerkleNodeState merkleNodeState) {
        virtualMap = (VirtualMap) merkleNodeState.getRoot();
        final MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();

        long lastLeafPath = vds.getLastLeafPath();
        leafNodeIndex = vds.getPathToDiskLocationLeafNodes();
        requireEqual(lastLeafPath, leafNodeIndex.size() - 1, LEAF);
        objectKeyToPath = vds.getKeyToPath();
        final var leafStore = new MemoryIndexDiskKeyValueStoreW<>(vds.getPathToKeyValue());
        leafDfc = leafStore.getFileCollection();
    }

    @Override
    public void processIndex(long path) {
        long dataLocation = leafNodeIndex.get(path, -1);
        requireNotEqual(-1, dataLocation, LEAF);
        // read from dataLocation using datasource
        try {
            var data = leafDfc.readDataItem(dataLocation);
            if (data != null) {
                final VirtualLeafBytes<?> leafRecord = VirtualLeafBytes.parseFrom(data);
                requireEqual(leafRecord.path(), path, LEAF);
                Bytes keyBytes = leafRecord.keyBytes();
                long actual = objectKeyToPath.get(leafRecord.keyBytes(), -1);
                requireEqual(path, actual, LEAF);

                requireEqual(leafRecord.valueBytes(), virtualMap.getBytes(keyBytes), LEAF);
                successCount.incrementAndGet();
            } else {
                nullErrorCount.incrementAndGet();
                printFileDataLocationError(log, "Missing entry on disk!", leafDfc, dataLocation);
            }
        } catch (IOException e) {
            exceptionCount.incrementAndGet();
            printFileDataLocationError(log, e.getMessage(), leafDfc, dataLocation);
        }
    }

    @Override
    public void validate() {
        log.debug("size of index: {}", leafNodeIndex.size());
        requireEqual(
                0,
                nullErrorCount.get(),
                "Some entries on disk are missing even though pointers are present in the index",
                LEAF);
        requireEqual(0, exceptionCount.get(), "Some read operations failed", LEAF);
        log.info("Successfully checked {} entries", successCount.get());
    }
}

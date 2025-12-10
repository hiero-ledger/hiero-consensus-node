// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator;

import static com.hedera.statevalidation.util.LogUtils.printFileDataLocationError;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.poc.util.ValidationAssertions;
import com.hedera.statevalidation.poc.validator.api.LeafBytesValidator;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @see LeafBytesValidator
 */
public class LeafBytesIntegrityValidator implements LeafBytesValidator {

    private static final Logger log = LogManager.getLogger(LeafBytesIntegrityValidator.class);

    public static final String LEAF_TAG = "leaf";

    private VirtualMap virtualMap;
    private DataFileCollection pathToKeyValueDfc;
    private HalfDiskHashMap keyToPath;

    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger exceptionCount = new AtomicInteger(0);

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull String getTag() {
        return LEAF_TAG;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final MerkleNodeState state) {
        this.virtualMap = (VirtualMap) state.getRoot();
        final MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();
        this.pathToKeyValueDfc = vds.getPathToKeyValue().getFileCollection();
        this.keyToPath = vds.getKeyToPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void processLeafBytes(long dataLocation, @NonNull final VirtualLeafBytes<?> leafBytes) {
        Objects.requireNonNull(virtualMap);
        Objects.requireNonNull(pathToKeyValueDfc);
        Objects.requireNonNull(keyToPath);

        try {
            final Bytes keyBytes = leafBytes.keyBytes();
            final Bytes valueBytes = leafBytes.valueBytes();
            final long p2KvPath = leafBytes.path();
            long k2pPath = keyToPath.get(keyBytes, -1);

            ValidationAssertions.requireEqual(p2KvPath, k2pPath, getTag());
            ValidationAssertions.requireEqual(valueBytes, virtualMap.getBytes(keyBytes), getTag());

            successCount.incrementAndGet();
        } catch (IOException e) {
            exceptionCount.incrementAndGet();
            printFileDataLocationError(log, e.getMessage(), pathToKeyValueDfc, dataLocation);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        log.debug("Successfully checked {} VirtualLeafBytes entries", successCount.get());
        ValidationAssertions.requireEqual(0, exceptionCount.get(), getTag(), "Some read operations failed");
    }
}

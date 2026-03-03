// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import com.hedera.statevalidation.validator.util.ValidationAssertions;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;

/**
 * @see HashRecordValidator
 */
public class HashRecordIntegrityValidator implements HashRecordValidator {

    private static final Logger log = LogManager.getLogger(HashRecordIntegrityValidator.class);

    public static final String INTERNAL_GROUP = "internal";

    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong nullHashCount = new AtomicLong(0);

    private long lastLeafPath;

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
        final VirtualMap virtualMap = state.getRoot();
        final MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();
        this.lastLeafPath = vds.getLastLeafPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    // TODO: process hash chunk
    public void processHashRecord(@NonNull final VirtualHashRecord hashRecord) {
        try {
            final Hash hash = hashRecord.hash();
            if (hash == null) {
                nullHashCount.incrementAndGet();
                log.error("Null hash in VirtualHashRecord entry with path: {}", hashRecord.path());
                return;
            }
        } finally {
            processedCount.incrementAndGet();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        log.info("Checked {} VirtualHashRecord entries", processedCount.get());

        // All virtual paths 0 to lastLeafPath, inclusive
        final long expectedCount = lastLeafPath + 1;
        final boolean ok = processedCount.get() == expectedCount && nullHashCount.get() == 0;

        ValidationAssertions.requireTrue(
                ok,
                getName(),
                ("%s validation failed. Hash record count exp=%d act=%d, nullHashCount=%d")
                        .formatted(getName(), expectedCount, processedCount.get(), nullHashCount.get()));
    }
}

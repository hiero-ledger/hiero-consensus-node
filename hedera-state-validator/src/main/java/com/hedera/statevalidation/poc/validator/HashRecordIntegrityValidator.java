// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator;

import com.hedera.statevalidation.poc.util.ValidationAssertions;
import com.hedera.statevalidation.poc.validator.api.HashRecordValidator;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HashRecordIntegrityValidator implements HashRecordValidator {

    private static final Logger log = LogManager.getLogger(HashRecordIntegrityValidator.class);

    public static final String INTERNAL_TAG = "internal";

    private final AtomicInteger totalEntriesProcessed = new AtomicInteger(0);

    @Override
    public String getTag() {
        return INTERNAL_TAG;
    }

    @Override
    public void initialize(@NonNull final MerkleNodeState state) {}

    @Override
    public void processHashRecord(@NonNull final VirtualHashRecord hashRecord) {
        ValidationAssertions.requireNonNull(hashRecord.hash(), INTERNAL_TAG);
        totalEntriesProcessed.incrementAndGet();
    }

    @Override
    public void validate() {
        log.debug("Successfully checked {} VirtualHashRecord entries", totalEntriesProcessed.get());
    }
}

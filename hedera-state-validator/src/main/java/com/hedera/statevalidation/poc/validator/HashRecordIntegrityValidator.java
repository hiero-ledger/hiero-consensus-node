// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator;

import com.hedera.statevalidation.poc.util.ValidationAssertions;
import com.hedera.statevalidation.poc.validator.api.HashRecordValidator;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @see HashRecordValidator
 */
public class HashRecordIntegrityValidator implements HashRecordValidator {

    private static final Logger log = LogManager.getLogger(HashRecordIntegrityValidator.class);

    public static final String INTERNAL_TAG = "internal";

    private final AtomicInteger totalEntriesProcessed = new AtomicInteger(0);

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull String getTag() {
        return INTERNAL_TAG;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final DeserializedSignedState deserializedSignedState) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void processHashRecord(@NonNull final VirtualHashRecord hashRecord) {
        ValidationAssertions.requireNonNull(hashRecord.hash(), getTag());
        totalEntriesProcessed.incrementAndGet();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        log.debug("Successfully checked {} VirtualHashRecord entries", totalEntriesProcessed.get());
    }
}

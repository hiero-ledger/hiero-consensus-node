// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator;

import com.hedera.statevalidation.poc.util.ValidationAssertions;
import com.hedera.statevalidation.poc.validator.api.HashRecordValidator;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
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

    public static final String INTERNAL_TAG = "internal";

    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong nullHashCount = new AtomicLong(0);
    private final AtomicLong nullHashSentinelCount = new AtomicLong(0);

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

    // Cross-check with index?
    /**
     * {@inheritDoc}
     */
    @Override
    public void processHashRecord(@NonNull final VirtualHashRecord hashRecord) {
        try {
            final Hash hash = hashRecord.hash();
            if (hash == null) {
                nullHashCount.incrementAndGet();
                log.error("Null hash in VirtualHashRecord entry with path: {}", hashRecord.path());
                return;
            }

            if (hash.equals(VirtualNodeCache.NULL_HASH)) {
                nullHashSentinelCount.incrementAndGet();
                log.error("NULL_HASH sentinel value in VirtualHashRecord entry with path: {}", hashRecord.path());
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

        final boolean ok = nullHashCount.get() == 0 && nullHashSentinelCount.get() == 0;

        ValidationAssertions.requireTrue(
                ok,
                getTag(),
                ("%s validation failed. " + "nullHashCount=%d, nullHashSentinelCount=%d")
                        .formatted(getTag(), nullHashCount.get(), nullHashSentinelCount.get()));
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.v2;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_MILLISECONDS;

import com.hedera.statevalidation.validator.v2.pipeline.RehashTaskExecutor;
import com.hedera.statevalidation.validator.v2.util.ValidationAssertions;
import com.hedera.statevalidation.validator.v2.util.ValidationException;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;

/**
 * Validator that performs full rehash of the state and compares against the original hash.
 *
 * <p>This validator runs independently (not through the data pipeline) because it uses
 * tasks for parallel tree traversal.
 * @see Validator
 */
public class RehashValidator implements Validator {

    private static final Logger logger = LogManager.getLogger(RehashValidator.class);

    public static final String REHASH_TAG = "rehash";

    private RecordAccessor records;
    private long firstLeafPath;
    private long lastLeafPath;
    private Hash originalHash;

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getTag() {
        return REHASH_TAG;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final DeserializedSignedState deserializedSignedState) {
        this.originalHash = deserializedSignedState.originalHash();

        //noinspection resource
        final VirtualMap vm = (VirtualMap)
                deserializedSignedState.reservedSignedState().get().getState().getRoot();
        this.records = vm.getRecords();

        final VirtualMapMetadata metadata = vm.getMetadata();
        this.firstLeafPath = metadata.getFirstLeafPath();
        this.lastLeafPath = metadata.getLastLeafPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        logger.debug("Doing full rehash for the path range: {} - {} in the VirtualMap", firstLeafPath, lastLeafPath);

        final long startTimeNanos = System.nanoTime();
        final RehashTaskExecutor executor = new RehashTaskExecutor(records, firstLeafPath, lastLeafPath);
        final Hash computedHash;

        try {
            computedHash = executor.execute();
        } catch (final Exception e) {
            throw new ValidationException(REHASH_TAG, "Unexpected exception: " + e.getMessage(), e);
        }

        ValidationAssertions.requireEqual(originalHash, computedHash, getTag());

        logger.debug(
                "It took {} ms to rehash the state",
                (System.nanoTime() - startTimeNanos) * NANOSECONDS_TO_MILLISECONDS);
    }
}

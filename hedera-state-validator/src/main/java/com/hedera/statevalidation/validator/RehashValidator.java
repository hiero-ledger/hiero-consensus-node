// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import com.hedera.statevalidation.util.StateUtils;
import com.hedera.statevalidation.validator.pipeline.RehashTaskExecutor;
import com.hedera.statevalidation.validator.util.ValidationAssertions;
import com.hedera.statevalidation.validator.util.ValidationException;
import com.swirlds.state.merkle.VirtualMapState;
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

    public static final String REHASH_GROUP = "rehash";

    private RecordAccessor records;
    private long firstLeafPath;
    private long lastLeafPath;
    private Hash originalHash;

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getGroup() {
        return REHASH_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getName() {
        // Intentionally same as group, as its the most appropriate name for this validator
        return REHASH_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final VirtualMapState state) {
        final VirtualMap vm = state.getRoot();
        this.originalHash = StateUtils.getOriginalStateHash();
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

        final long startTime = System.currentTimeMillis();
        final RehashTaskExecutor executor = new RehashTaskExecutor(records, firstLeafPath, lastLeafPath);
        final Hash computedHash;

        try {
            computedHash = executor.execute();
        } catch (final Exception e) {
            throw new ValidationException(REHASH_GROUP, "Unexpected exception: " + e.getMessage(), e);
        }

        ValidationAssertions.requireEqual(originalHash, computedHash, getName());

        logger.debug("It took {} ms to rehash the state", System.currentTimeMillis() - startTime);
    }
}

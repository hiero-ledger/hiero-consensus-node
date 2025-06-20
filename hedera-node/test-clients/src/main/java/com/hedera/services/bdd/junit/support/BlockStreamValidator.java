// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.stream.Stream;

/**
 * Defines API for validating a stream of {@link Block}s either independently or against a record stream.
 */
public interface BlockStreamValidator {
    interface Factory {
        /**
         * Returns true if this validator applies to the given {@link HapiSpec}.
         * @param spec the spec
         * @return true if this validator applies to the spec
         */
        default boolean appliesTo(@NonNull final HapiSpec spec) {
            return true;
        }

        /**
         * Creates a new {@link BlockStreamValidator} for the given {@link HapiSpec}.
         * @param spec the spec
         * @return the validator
         */
        @NonNull
        BlockStreamValidator create(@NonNull HapiSpec spec);
    }

    /**
     * Validate the given {@link Block}s in the context of the given {@link StreamFileAccess.RecordStreamData} and
     * returns a {@link Stream} of {@link Throwable}s representing any validation errors.
     * @param blocks the blocks to validate
     * @param data the record stream data
     * @return a stream of validation errors
     */
    default Stream<Throwable> validationErrorsIn(
            @NonNull final List<Block> blocks, @NonNull final StreamFileAccess.RecordStreamData data) {
        try {
            validateBlockVsRecords(blocks, data);
        } catch (final Throwable t) {
            return Stream.of(t);
        }
        return Stream.empty();
    }

    /**
     * Validate the given {@link Block}s in the context of the given {@link StreamFileAccess.RecordStreamData}.
     * @param blocks the blocks to validate
     * @param data the record stream data
     */
    default void validateBlockVsRecords(
            @NonNull final List<Block> blocks, @NonNull final StreamFileAccess.RecordStreamData data) {
        validateBlocks(blocks);
    }

    /**
     * Validate the given {@link Block}s independent of the record stream.
     * @param blocks the blocks to validate
     */
    default void validateBlocks(@NonNull final List<Block> blocks) {
        // No-op
    }
}

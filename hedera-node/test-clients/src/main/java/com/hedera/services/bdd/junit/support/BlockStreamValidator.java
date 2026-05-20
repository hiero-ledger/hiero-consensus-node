// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.stream.Stream;

/**
 * Defines API for validating a stream of {@link Block}s either independently or against a record stream.
 */
public interface BlockStreamValidator {
    /**
     * Returns {@code true} if the given block items represent a Wrapped Record Block (WRB).
     * Detection is based on the presence of a {@code RECORD_FILE} item.
     */
    static boolean isWrappedRecordBlock(@NonNull final List<BlockItem> items) {
        for (final var item : items) {
            if (item.hasRecordFile()) {
                return true;
            }
        }
        return false;
    }

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
         * Returns true if this validator wants pre-cutover preview blocks (archived by
         * {@link com.hedera.node.app.blocks.schemas.V0740BlockStreamSchema} into a
         * {@code *-preview-archive} sibling at cutover) included in its input list. Defaults
         * to {@code false} — only validators that consume the event chain across the cutover
         * boundary (e.g. {@link com.hedera.services.bdd.junit.support.validators.block.EventHashBlockStreamValidator})
         * need them. Other validators receive only the active (post-cutover) blocks; if they
         * need pre-cutover content for state replay, they read it from the test harness's
         * {@code preservedPreviewBlocks} snapshot instead.
         */
        default boolean wantsArchiveBlocks() {
            return false;
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

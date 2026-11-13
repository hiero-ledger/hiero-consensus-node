// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Implements an assertion about one or more {@link com.hedera.hapi.block.stream.Block}'s that should appear in the
 * block stream during---or shortly after---execution of a {@link HapiSpec}.
 *
 * <p>Typical implementations will be stateful, and will be constructed with their "parent" {@link HapiSpec}.
 */
@FunctionalInterface
public interface BlockStreamAssertion extends StreamAssertion {
    /**
     * Wraps an assertion that needs complete state values, such as one that translates blocks to records.
     */
    static BlockStreamAssertion withFullStateValues(@NonNull final BlockStreamAssertion assertion) {
        requireNonNull(assertion);
        return new BlockStreamAssertion() {
            @Override
            public boolean test(@NonNull final Block block) {
                return assertion.test(block);
            }

            @Override
            public boolean wantsFullStateValues() {
                return true;
            }
        };
    }

    /**
     * Updates the assertion's state based on a relevant {@link Block}, throwing an {@link AssertionError} if a
     * failure state is reached; or returning true if the assertion has reached a success state.
     *
     * @param block the block to test
     * @throws AssertionError if the assertion has failed
     * @return true if the assertion has succeeded
     */
    boolean test(@NonNull Block block) throws AssertionError;

    /**
     * Returns true if this assertion reads state change values, and so needs any partial updates in a block
     * expanded to full values; such a block's bytes no longer match its proof.
     */
    default boolean wantsFullStateValues() {
        return false;
    }
}

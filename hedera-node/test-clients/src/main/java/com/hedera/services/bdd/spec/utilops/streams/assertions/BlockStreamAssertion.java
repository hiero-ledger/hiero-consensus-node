// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

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
public interface BlockStreamAssertion {
    /**
     * Updates the assertion's state based on a relevant {@link Block}, throwing an {@link AssertionError} if a
     * failure state is reached; or returning true if the assertion has reached a success state.
     *
     * @param block the block to test
     * @throws AssertionError if the assertion has failed
     * @return true if the assertion has succeeded
     */
    boolean test(@NonNull Block block) throws AssertionError;
}

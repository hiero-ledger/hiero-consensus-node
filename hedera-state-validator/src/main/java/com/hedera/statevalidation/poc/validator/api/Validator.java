// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator.api;

import com.swirlds.state.MerkleNodeState;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Base interface for all validators with a clear lifecycle.
 */
public interface Validator {

    String getTag();

    void initialize(@NonNull MerkleNodeState state);

    /**
     * Finalize validation and assert results.
     * Called once after all data processing is complete.
     */
    void validate();
}

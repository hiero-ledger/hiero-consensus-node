// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators;

import com.swirlds.platform.state.MerkleNodeState;

/**
 * Base interface for all validators with clear lifecycle.
 */
public interface Validator {
    String getTag();

    /**
     * Initialize validator with necessary context.
     * Called once before validation starts.
     */
    void initialize(MerkleNodeState context);

    /**
     * Finalize validation and assert results.
     * Called once after all data processing is complete.
     */
    void validate();
}

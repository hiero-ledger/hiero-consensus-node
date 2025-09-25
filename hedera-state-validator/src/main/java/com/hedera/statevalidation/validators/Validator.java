// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators;

import com.swirlds.platform.state.MerkleNodeState;

/**
 * Base interface for all validators.
 * Simple design - each validator knows its tag and can execute validation logic.
 */
public interface Validator {

    /**
     * Returns the validation category/tag for this validator.
     */
    String getTag();

    /**
     * Executes the validation logic.
     *
     * @param merkleNodeState the state (can be a validation context in the future)
     */
    void validate(MerkleNodeState merkleNodeState);
}

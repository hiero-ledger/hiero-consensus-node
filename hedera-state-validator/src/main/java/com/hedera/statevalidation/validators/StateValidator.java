// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators;

import com.swirlds.platform.state.MerkleNodeState;

/**
 * Marker interface for validators that process the entire state independently.
 */
public interface StateValidator extends Validator {
    void processState(MerkleNodeState merkleNodeState);
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Implementations of this interface can provide access to their {@link MerkleNodeState}.
 */
public interface MerkleNodeStateAware {
    /**
     * An instance of {@link MerkleNodeState} associated with this object.
     * @return an instance of {@link MerkleNodeState} associated with this object.
     */
    @NonNull
    MerkleNodeState getState();
}

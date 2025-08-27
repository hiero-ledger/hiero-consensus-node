// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.iss.internal;

/**
 * The validity of this node's hash for a particular round.
 */
public enum HashValidityStatus {
    /**
     * The validity for this node's hash has not yet been determined.
     */
    UNDECIDED,
    /**
     * This node computed a hash equal to the consensus hash.
     */
    VALID,
    /**
     * This node computed a hash different from the consensus hash.
     */
    SELF_ISS,
    /**
     * There is no consensus hash, and the network will need human intervention to recover.
     */
    CATASTROPHIC_ISS,
    /**
     * We lack sufficient data to ever fully decide.
     */
    LACK_OF_DATA,
    /**
     * We lack sufficient data to ever fully decide, but there is strong evidence of a severely fragmented
     * and unhealthy network.
     */
    CATASTROPHIC_LACK_OF_DATA
}

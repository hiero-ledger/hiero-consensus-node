// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.iss.detection.internal;

/**
 * Various states in the process of determining the consensus hash of a round.
 */
public enum ConsensusHashStatus {
    /**
     * The consensus hash is not yet known
     */
    UNDECIDED,
    /**
     * The consensus hash is known
     */
    DECIDED,
    /**
     * There exists no consensus hash due to an ISS
     */
    CATASTROPHIC_ISS
}

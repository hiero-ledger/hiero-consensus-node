// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.main.model;

import org.hiero.base.crypto.DigestType;

/**
 * A class for Consensus Layer constants required to understand the Consensus / Execution API.
 */
public final class ConsensusLayerConstants {
    /**
     * Private constructor so that this class is never instantiated
     */
    private ConsensusLayerConstants() {}

    /** The default digest type for events. */
    public static final DigestType DEFAULT_EVENT_DIGEST_TYPE = DigestType.SHA_256;
}

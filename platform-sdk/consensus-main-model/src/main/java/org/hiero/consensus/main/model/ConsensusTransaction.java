// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.main.model;

import java.time.Instant;

/**
 * A transaction that has reached consensus.
 */
public interface ConsensusTransaction extends org.hiero.consensus.main.model.Transaction {
    /**
     * Returns the community's consensus timestamp for this item.
     *
     * @return the consensus timestamp
     */
    Instant getConsensusTimestamp();
}

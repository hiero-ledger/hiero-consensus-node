// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.hashgraph;

import java.time.Instant;

/**
 * An item that has reached consensus.
 * <p>
 * IMPORTANT: Although this interface is not sealed, it should only be implemented by internal classes. This
 * interface may be changed at any time, in any way, without notice or prior deprecation. Third parties should NOT
 * implement this interface.
 */
public interface ReachedConsensus {

    /**
     * Returns the consensus order of the consensus item, starting at zero. Smaller values occur before higher numbers.
     *
     * @return the consensus order sequence number
     */
    long getConsensusOrder();

    /**
     * Returns the community's consensus timestamp for this item.
     *
     * @return the consensus timestamp
     */
    Instant getConsensusTimestamp();
}

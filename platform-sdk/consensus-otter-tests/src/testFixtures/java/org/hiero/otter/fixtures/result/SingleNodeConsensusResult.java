// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;

/**
 * Interface that provides access to the consensus results of a single node that were created during a test.
 *
 * <p>The provided data is a snapshot of the state at the moment when the result was requested.
 */
public interface SingleNodeConsensusResult {

    /**
     * Returns the node ID of the node that created the rounds.
     *
     * @return the node ID
     */
    @NonNull
    NodeId nodeId();

    /**
     * Returns the last round created during the test.
     *
     * @return the last round or {@code -1} if no rounds were created
     */
    default long lastRoundNum() {
        return consensusRounds().stream()
                .mapToLong(ConsensusRound::getRoundNum)
                .max()
                .orElse(-1L);
    }

    /**
     * Returns the list of consensus rounds created during the test.
     *
     * @return the list of consensus rounds
     */
    @NonNull
    List<ConsensusRound> consensusRounds();
}

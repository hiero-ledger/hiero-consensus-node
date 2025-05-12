// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;

/**
 * Interface that provides access to the consensus results of a single node that are created during a test.
 */
public interface SingleNodeConsensusResult {

    /**
     * Returns the node ID of the node that created the results.
     *
     * @return the node ID
     */
    @NonNull
    NodeId nodeId();

    /**
     * Returns the last round created so far.
     *
     * @return the last round or {@code -1} if no rounds were created
     */
    default long lastRoundNum() {
        return createSnapshot().stream()
                .mapToLong(ConsensusRound::getRoundNum)
                .max()
                .orElse(-1L);
    }

    /**
     * Returns the list of consensus rounds created during the test up to this moment
     *
     * @return the list of consensus rounds
     */
    @NonNull
    List<ConsensusRound> createSnapshot();

    /**
     * Subscribes to {@link ConsensusRound}s created by the node.
     *
     * @param subscriber the subscriber that will receive the rounds
     */
    void subscribe(@NonNull ConsensusRoundSubscriber subscriber);

}

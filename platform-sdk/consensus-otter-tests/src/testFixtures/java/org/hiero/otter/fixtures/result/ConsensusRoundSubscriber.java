// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;

/**
 * Defines a subscriber that will receive {@link ConsensusRound}s.
 */
@FunctionalInterface
public interface ConsensusRoundSubscriber {

    /**
     * Called when a new {@link ConsensusRound} is available.
     *
     * @param nodeId the node that created the round
     * @param round the new {@link ConsensusRound}
     * @return {@link SubscriberAction#UNSUBSCRIBE} to unsubscribe, {@link SubscriberAction#CONTINUE} to continue
     */
    SubscriberAction onConsensusRound(@NonNull NodeId nodeId, @NonNull ConsensusRound round);
}

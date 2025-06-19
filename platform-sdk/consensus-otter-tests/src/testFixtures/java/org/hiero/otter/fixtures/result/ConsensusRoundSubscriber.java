// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;

/**
 * Defines a subscriber that will receive {@link ConsensusRound}s.
 */
@FunctionalInterface
public interface ConsensusRoundSubscriber {

    /**
     * Return value to indicate whether the subscriber should continue receiving rounds or unsubscribe.
     */
    enum SubscriberAction {
        CONTINUE,
        UNSUBSCRIBE
    }

    /**
     * Called when new {@link ConsensusRound}s are available.
     *
     * @param nodeId the node that created the round
     * @param rounds the new {@link ConsensusRound}s
     * @return {@link SubscriberAction#UNSUBSCRIBE} to unsubscribe, {@link SubscriberAction#CONTINUE} to continue
     */
    SubscriberAction onConsensusRounds(@NonNull NodeId nodeId, @NonNull List<ConsensusRound> rounds);
}

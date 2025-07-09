// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Defines a subscriber that will receive {@link ConsensusRound}s.
 */
@FunctionalInterface
public interface ConsensusRoundSubscriber {

    /**
     * Called when new {@link ConsensusRound}s are available.
     *
     * @param nodeId the node that created the round
     * @param rounds the new {@link ConsensusRound}s
     * @return {@link SubscriberAction#UNSUBSCRIBE} to unsubscribe, {@link SubscriberAction#CONTINUE} to continue
     */
    SubscriberAction onConsensusRounds(@NonNull NodeId nodeId, @NonNull List<ConsensusRound> rounds);
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Interface that provides access to the consensus results of a group of nodes that were created during a test.
 *
 * <p>The provided data is a snapshot of the state at the moment when the result was requested.
 */
public interface MultipleNodeConsensusResults extends OtterResult {

    /**
     * Returns an immutable list of {@link SingleNodeConsensusResult} for all nodes
     *
     * @return the list of results
     */
    @NonNull
    List<SingleNodeConsensusResult> results();

    /**
     * Subscribes to {@link ConsensusRound}s created by the nodes.
     *
     * @param subscriber the subscriber that will receive the rounds
     */
    void subscribe(@NonNull ConsensusRoundSubscriber subscriber);
}

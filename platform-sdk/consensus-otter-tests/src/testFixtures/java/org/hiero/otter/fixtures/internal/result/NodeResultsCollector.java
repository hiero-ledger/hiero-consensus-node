// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber.SubscriberAction;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Helper class that collects all test results of a node.
 */
public class NodeResultsCollector {

    private final NodeId nodeId;
    private final List<ConsensusRound> consensusRounds = new ArrayList<>();
    private final List<ConsensusRoundSubscriber> consensusRoundSubscribers = new CopyOnWriteArrayList<>();

    /**
     * Creates a new instance of {@link NodeResultsCollector}.
     *
     * @param nodeId the node ID of the node
     */
    public NodeResultsCollector(@NonNull final NodeId nodeId) {
        this.nodeId = requireNonNull(nodeId);
    }

    /**
     * Returns the node ID of the node that created the results.
     *
     * @return the node ID
     */
    @NonNull
    public NodeId nodeId() {
        return nodeId;
    }

    /**
     * Adds a consensus round to the list of rounds created during the test.
     *
     * @param rounds the consensus rounds to add
     */
    public void addConsensusRounds(@NonNull final List<ConsensusRound> rounds) {
        requireNonNull(rounds);
        consensusRounds.addAll(rounds);
        consensusRoundSubscribers.removeIf(
                subscriber -> subscriber.onConsensusRounds(nodeId, rounds) == SubscriberAction.UNSUBSCRIBE);
    }

    /**
     * Returns a {@link SingleNodeConsensusResult} of the current state.
     *
     * @return the {@link SingleNodeConsensusResult}
     */
    @NonNull
    public SingleNodeConsensusResult getConsensusResult() {
        return new SingleNodeConsensusResultImpl(this);
    }

    /**
     * Returns a snapshot of the consensus rounds created so far.
     *
     * @return the list of consensus rounds
     */
    @NonNull
    public List<ConsensusRound> createConsensusRoundsSnapshot() {
        return new ArrayList<>(consensusRounds);
    }

    /**
     * Subscribes to {@link ConsensusRound}s created by this node.
     *
     * @param subscriber the subscriber that will receive the rounds
     */
    public void subscribe(@NonNull final ConsensusRoundSubscriber subscriber) {
        consensusRoundSubscribers.add(subscriber);
    }
}

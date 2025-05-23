// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber.SubscriberAction;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;

/**
 * Helper class that collects all test results of a node.
 */
public class NodeResultsCollector {

    private final NodeId nodeId;
    private final Queue<ConsensusRound> consensusRounds = new ConcurrentLinkedQueue<>();
    private final List<ConsensusRoundSubscriber> consensusRoundSubscribers = new CopyOnWriteArrayList<>();
    private final List<PlatformStatus> platformStatuses = new ArrayList<>();
    private volatile boolean destroyed = false;

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
        if (!destroyed) {
            consensusRounds.addAll(rounds);
            consensusRoundSubscribers.removeIf(
                    subscriber -> subscriber.onConsensusRounds(nodeId, rounds) == SubscriberAction.UNSUBSCRIBE);
        }
    }

    /**
     * Adds a {@link PlatformStatus} to the list of collected statuses.
     *
     * @param status the {@link PlatformStatus} to add
     */
    public void addPlatformStatus(@NonNull final PlatformStatus status) {
        requireNonNull(status);
        if (!destroyed) {
            platformStatuses.add(status);
        }
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
     * Returns all the consensus rounds created at the moment of invocation, starting with and including the provided index.
     *
     * @param startIndex the index to start from
     * @return the list of consensus rounds
     */
    @NonNull
    public List<ConsensusRound> currentConsensusRounds(final int startIndex) {
        final List<ConsensusRound> copy = List.copyOf(consensusRounds);
        return copy.subList(startIndex, copy.size() - 1);
    }

    /**
     * Subscribes to {@link ConsensusRound}s created by this node.
     *
     * @param subscriber the subscriber that will receive the rounds
     */
    public void subscribe(@NonNull final ConsensusRoundSubscriber subscriber) {
        consensusRoundSubscribers.add(subscriber);
    }

    /**
     * Returns a {@link SingleNodeStatusProgression} of the current state.
     *
     * @return the {@link SingleNodeStatusProgression}
     */
    public SingleNodeStatusProgression getStatusProgression() {
        return new SingleNodeStatusProgressionImpl(nodeId, new ArrayList<>(platformStatuses));
    }

    /**
     * Destroys the collector and prevents any further updates.
     */
    public void destroy() {
        destroyed = true;
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.PlatformStatusSubscriber;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Helper class that collects all test results of a node.
 */
public class NodeResultsCollector {

    private final NodeId nodeId;
    private final Queue<ConsensusRound> consensusRounds = new ConcurrentLinkedQueue<>();
    private final List<ConsensusRoundSubscriber> consensusRoundSubscribers = new CopyOnWriteArrayList<>();
    private final List<PlatformStatus> platformStatuses = new ArrayList<>();
    private final List<PlatformStatusSubscriber> platformStatusSubscribers = new CopyOnWriteArrayList<>();

    // This class may be used in a multi-threaded context, so we use volatile to ensure visibility of state changes
    private volatile boolean destroyed = false;

    /**
     * Creates a new instance of {@link NodeResultsCollector}.
     *
     * @param nodeId the node ID of the node
     */
    public NodeResultsCollector(@NonNull final NodeId nodeId) {
        this.nodeId = requireNonNull(nodeId, "nodeId should not be null");
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
            platformStatusSubscribers.removeIf(
                    subscriber -> subscriber.onPlatformStatusChange(nodeId, status) == SubscriberAction.UNSUBSCRIBE);
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
        return copy.subList(startIndex, copy.size());
    }

    /**
     * Subscribes to {@link ConsensusRound}s created by this node.
     *
     * @param subscriber the subscriber that will receive the rounds
     */
    public void subscribeConsensusRoundSubscriber(@NonNull final ConsensusRoundSubscriber subscriber) {
        consensusRoundSubscribers.add(subscriber);
    }

    /**
     * Returns a {@link SingleNodePlatformStatusResult} of the current state.
     *
     * @return the {@link SingleNodePlatformStatusResult}
     */
    @NonNull
    public SingleNodePlatformStatusResult getStatusProgression() {
        return new SingleNodePlatformStatusResultImpl(this);
    }

    /**
     * Returns all the platform statuses the node went through until the moment of invocation, starting with and including the provided index.
     *
     * @param startIndex the index to start from
     * @return the list of platform statuses
     */
    public List<PlatformStatus> currentStatusProgression(final int startIndex) {
        final List<PlatformStatus> copy = List.copyOf(platformStatuses);
        return copy.subList(startIndex, copy.size());
    }

    /**
     * Subscribes to {@link PlatformStatus} events.
     *
     * @param subscriber the subscriber that will receive the platform statuses
     */
    public void subscribePlatformStatusSubscriber(@NonNull final PlatformStatusSubscriber subscriber) {
        platformStatusSubscribers.add(subscriber);
    }

    /**
     * Destroys the collector and prevents any further updates.
     */
    public void destroy() {
        destroyed = true;
    }
}

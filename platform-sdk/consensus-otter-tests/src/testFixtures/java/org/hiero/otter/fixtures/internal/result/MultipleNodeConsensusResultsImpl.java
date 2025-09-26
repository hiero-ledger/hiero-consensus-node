// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.OtterResult;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SubscriberAction;
import org.jetbrains.annotations.NotNull;

/**
 * Default implementation of {@link MultipleNodeConsensusResults}
 */
public class MultipleNodeConsensusResultsImpl implements MultipleNodeConsensusResults {

    private final List<SingleNodeConsensusResult> results;
    private final List<ConsensusRoundSubscriber> consensusRoundSubscribers = new CopyOnWriteArrayList<>();

    /**
     * Constructor for {@link MultipleNodeConsensusResultsImpl}.
     *
     * @param results the list of {@link SingleNodeConsensusResult} for all nodes
     */
    public MultipleNodeConsensusResultsImpl(@NonNull final List<SingleNodeConsensusResult> results) {
        this.results = unmodifiableList(requireNonNull(results));

        // The subscription mechanism is a bit tricky, because we have two levels of subscriptions.
        // A subscriber A can subscribe to this class. It will be notified if any of the nodes has new rounds.
        // To implement this, we define a meta-subscriber that will be subscribed to the results of all nodes.
        // This meta-subscriber will notify all child-subscribers to this class (among them A).
        // If a child-subscriber wants to be unsubscribed, it will return SubscriberAction.UNSUBSCRIBE.
        final ConsensusRoundSubscriber metaSubscriber = (nodeId, rounds) -> {
            // iterate over all child-subscribers and eventually remove the ones that wish to be unsubscribed
            consensusRoundSubscribers.removeIf(
                    current -> current.onConsensusRounds(nodeId, rounds) == SubscriberAction.UNSUBSCRIBE);

            // the meta-subscriber never unsubscribes
            return SubscriberAction.CONTINUE;
        };
        for (final SingleNodeConsensusResult result : results) {
            result.subscribe(metaSubscriber);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<SingleNodeConsensusResult> results() {
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void subscribe(@NonNull final ConsensusRoundSubscriber subscriber) {
        consensusRoundSubscribers.add(subscriber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodeConsensusResults suppressingNode(@NonNull final NodeId nodeId) {
        final List<SingleNodeConsensusResult> newResults = results.stream()
                .filter(result -> !Objects.equals(nodeId, result.nodeId()))
                .toList();
        return new MultipleNodeConsensusResultsImpl(newResults);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull MultipleNodeConsensusResults suppressingNodes(@NotNull final Collection<Node> nodes) {
        final Set<NodeId> nodeIdsToSuppress = nodes.stream().map(Node::selfId).collect(Collectors.toSet());
        final List<SingleNodeConsensusResult> filtered = results.stream()
                .filter(result -> !nodeIdsToSuppress.contains(result.nodeId()))
                .toList();
        return new MultipleNodeConsensusResultsImpl(filtered);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The change is done on a best effort basis. A slower node may collect rounds after a clear that were
     * discarded on faster nodes. Ideally, this method is only called while all nodes have progressed the same,
     * e.g. while in the state {@link org.hiero.consensus.model.status.PlatformStatus#FREEZE_COMPLETE}.
     */
    @Override
    public void clear() {
        results.forEach(OtterResult::clear);
    }
}

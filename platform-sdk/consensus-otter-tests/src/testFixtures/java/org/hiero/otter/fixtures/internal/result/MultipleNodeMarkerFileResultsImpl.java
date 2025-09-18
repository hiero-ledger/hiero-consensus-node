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
import org.hiero.otter.fixtures.result.MarkerFileSubscriber;
import org.hiero.otter.fixtures.result.MultipleNodeMarkerFileResults;
import org.hiero.otter.fixtures.result.SingleNodeMarkerFileResult;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Default implementation of {@link MultipleNodeMarkerFileResults}
 */
public class MultipleNodeMarkerFileResultsImpl implements MultipleNodeMarkerFileResults {

    private final List<SingleNodeMarkerFileResult> results;
    private final List<MarkerFileSubscriber> markerFileSubscribers = new CopyOnWriteArrayList<>();

    /**
     * Constructor for {@link MultipleNodeMarkerFileResultsImpl}.
     *
     * @param results the list of {@link SingleNodeMarkerFileResult} for all nodes
     */
    public MultipleNodeMarkerFileResultsImpl(@NonNull final List<SingleNodeMarkerFileResult> results) {
        this.results = unmodifiableList(requireNonNull(results));

        // The subscription mechanism is a bit tricky, because we have two levels of subscriptions.
        // A subscriber A can subscribe to this class. It will be notified if any of the nodes writes a marker file.
        // To implement this, we define a meta-subscriber that will be subscribed to the results of all nodes.
        // This meta-subscriber will notify all child-subscribers to this class (among them A).
        // If a child-subscriber wants to be unsubscribed, it will return SubscriberAction.UNSUBSCRIBE.
        final MarkerFileSubscriber metaSubscriber = (nodeId, markerFilesStatus) -> {
            // iterate over all child-subscribers and eventually remove the ones that wish to be unsubscribed
            markerFileSubscribers.removeIf(
                    current -> current.onNewMarkerFile(nodeId, markerFilesStatus) == SubscriberAction.UNSUBSCRIBE);

            // the meta-subscriber never unsubscribes
            return SubscriberAction.CONTINUE;
        };
        for (final SingleNodeMarkerFileResult result : results) {
            result.subscribe(metaSubscriber);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<SingleNodeMarkerFileResult> results() {
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void subscribe(@NonNull final MarkerFileSubscriber subscriber) {
        markerFileSubscribers.add(subscriber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodeMarkerFileResults suppressingNode(@NonNull final NodeId nodeId) {
        final List<SingleNodeMarkerFileResult> filtered = results.stream()
                .filter(result -> !Objects.equals(nodeId, result.nodeId()))
                .toList();
        return new MultipleNodeMarkerFileResultsImpl(filtered);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull MultipleNodeMarkerFileResults suppressingNodes(@NonNull final Collection<Node> nodes) {
        final Set<NodeId> nodeIdsToSuppress = nodes.stream().map(Node::selfId).collect(Collectors.toSet());
        final List<SingleNodeMarkerFileResult> filtered = results.stream()
                .filter(result -> !nodeIdsToSuppress.contains(result.nodeId()))
                .toList();
        return new MultipleNodeMarkerFileResultsImpl(filtered);
    }
}

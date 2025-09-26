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
import org.hiero.otter.fixtures.result.MultipleNodeReconnectResults;
import org.hiero.otter.fixtures.result.ReconnectNotificationSubscriber;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Default implementation of {@link MultipleNodeReconnectResults}
 */
public class MultipleNodeReconnectResultsImpl implements MultipleNodeReconnectResults {

    private final List<SingleNodeReconnectResult> results;
    private final List<ReconnectNotificationSubscriber> reconnectSubscribers = new CopyOnWriteArrayList<>();

    public MultipleNodeReconnectResultsImpl(@NonNull final List<SingleNodeReconnectResult> results) {
        this.results = unmodifiableList(requireNonNull(results));

        // The subscription mechanism is a bit tricky, because we have two levels of subscriptions.
        // A subscriber A can subscribe to this class. It will be notified if any of the nodes has new log entries.
        // To implement this, we define a meta-subscriber that will be subscribed to the results of all nodes.
        // This meta-subscriber will notify all child-subscribers to this class (among them A).
        // If a child-subscriber wants to be unsubscribed, it will return SubscriberAction.UNSUBSCRIBE.
        final ReconnectNotificationSubscriber metaSubscriber = (notification) -> {
            // iterate over all child-subscribers and eventually remove the ones that wish to be unsubscribed
            reconnectSubscribers.removeIf(
                    current -> current.onNotification(notification) == SubscriberAction.UNSUBSCRIBE);

            // the meta-subscriber never unsubscribes
            return SubscriberAction.CONTINUE;
        };
        for (final SingleNodeReconnectResult result : results) {
            result.subscribe(metaSubscriber);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodeReconnectResults suppressingNode(@NonNull final NodeId nodeId) {
        final List<SingleNodeReconnectResult> filtered = results.stream()
                .filter(it -> Objects.equals(it.nodeId(), nodeId))
                .toList();
        return new MultipleNodeReconnectResultsImpl(filtered);
    }

    /**
     * {@inheritDoc}
     *
     */
    @Override
    @NonNull
    public MultipleNodeReconnectResults suppressingNodes(@NonNull final Collection<Node> nodes) {
        final Set<NodeId> nodeIdsToSuppress = nodes.stream().map(Node::selfId).collect(Collectors.toSet());
        final List<SingleNodeReconnectResult> filtered = results.stream()
                .filter(result -> !nodeIdsToSuppress.contains(result.nodeId()))
                .toList();
        return new MultipleNodeReconnectResultsImpl(filtered);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void subscribe(@NonNull final ReconnectNotificationSubscriber subscriber) {
        reconnectSubscribers.add(subscriber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<SingleNodeReconnectResult> reconnectResults() {
        return results;
    }
}

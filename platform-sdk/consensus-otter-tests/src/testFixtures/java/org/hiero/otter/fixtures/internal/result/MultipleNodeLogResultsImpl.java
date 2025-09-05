// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.result.LogSubscriber;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.OtterResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SubscriberAction;
import org.jetbrains.annotations.NotNull;

/**
 * Default implementation of {@link MultipleNodeLogResults}
 */
public class MultipleNodeLogResultsImpl implements MultipleNodeLogResults {

    private final List<SingleNodeLogResult> results;
    private final List<LogSubscriber> logSubscribers = new CopyOnWriteArrayList<>();

    /**
     * Constructor for {@link MultipleNodeLogResultsImpl}.
     *
     * @param results the list of {@link SingleNodeLogResult} for all nodes
     */
    public MultipleNodeLogResultsImpl(@NonNull final List<SingleNodeLogResult> results) {
        this.results = unmodifiableList(requireNonNull(results));

        // The subscription mechanism is a bit tricky, because we have two levels of subscriptions.
        // A subscriber A can subscribe to this class. It will be notified if any of the nodes has new log entries.
        // To implement this, we define a meta-subscriber that will be subscribed to the results of all nodes.
        // This meta-subscriber will notify all child-subscribers to this class (among them A).
        // If a child-subscriber wants to be unsubscribed, it will return SubscriberAction.UNSUBSCRIBE.
        final LogSubscriber metaSubscriber = logEntry -> {
            // iterate over all child-subscribers and eventually remove the ones that wish to be unsubscribed
            logSubscribers.removeIf(current -> current.onLogEntry(logEntry) == SubscriberAction.UNSUBSCRIBE);

            // the meta-subscriber never unsubscribes
            return SubscriberAction.CONTINUE;
        };
        for (final SingleNodeLogResult result : results) {
            result.subscribe(metaSubscriber);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<SingleNodeLogResult> results() {
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void subscribe(@NonNull final LogSubscriber subscriber) {
        logSubscribers.add(subscriber);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MultipleNodeLogResults suppressingNode(@NonNull final NodeId nodeId) {
        requireNonNull(nodeId, "nodeId cannot be null");
        final List<SingleNodeLogResult> filteredResults = results.stream()
                .filter(res -> Objects.equals(res.nodeId(), nodeId))
                .toList();

        return new MultipleNodeLogResultsImpl(filteredResults);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull MultipleNodeLogResults suppressingNodes(@NotNull final Collection<Node> nodes) {
        final Set<NodeId> nodeIdsToSuppress = nodes.stream().map(Node::selfId).collect(Collectors.toSet());
        final List<SingleNodeLogResult> filtered = results.stream()
                .filter(result -> !nodeIdsToSuppress.contains(result.nodeId()))
                .toList();
        return new MultipleNodeLogResultsImpl(filtered);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MultipleNodeLogResults suppressingLogMarker(@NonNull final LogMarker marker) {
        requireNonNull(marker, "marker cannot be null");
        final List<SingleNodeLogResult> filteredResults =
                results.stream().map(res -> res.suppressingLogMarker(marker)).toList();

        return new MultipleNodeLogResultsImpl(filteredResults);
    }

    @Override
    public void clear() {
        results.forEach(OtterResult::clear);
    }
}

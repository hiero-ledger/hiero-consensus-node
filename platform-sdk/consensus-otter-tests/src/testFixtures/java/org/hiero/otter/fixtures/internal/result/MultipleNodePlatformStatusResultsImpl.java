// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.OtterResult;
import org.hiero.otter.fixtures.result.PlatformStatusSubscriber;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Default implementation of {@link MultipleNodePlatformStatusResults}
 */
public class MultipleNodePlatformStatusResultsImpl implements MultipleNodePlatformStatusResults {

    private final List<SingleNodePlatformStatusResults> results;
    private final List<PlatformStatusSubscriber> platformStatusSubscribers = new CopyOnWriteArrayList<>();

    /**
     * Constructor for {@link MultipleNodePlatformStatusResultsImpl}.
     *
     * @param results the list of {@link SingleNodePlatformStatusResults} for all nodes
     */
    public MultipleNodePlatformStatusResultsImpl(@NonNull final List<SingleNodePlatformStatusResults> results) {
        this.results = unmodifiableList(requireNonNull(results));

        // The subscription mechanism is a bit tricky, because we have two levels of subscriptions.
        // A subscriber A can subscribe to this class. It will be notified if any of the nodes enters a new status.
        // To implement this, we define a meta-subscriber that will be subscribed to the results of all nodes.
        // This meta-subscriber will notify all child-subscribers to this class (among them A).
        // If a child-subscriber wants to be unsubscribed, it will return SubscriberAction.UNSUBSCRIBE.
        final PlatformStatusSubscriber metaSubscriber = (nodeId, platformStatus) -> {
            // iterate over all child-subscribers and eventually remove the ones that wish to be unsubscribed
            platformStatusSubscribers.removeIf(
                    current -> current.onPlatformStatusChange(nodeId, platformStatus) == SubscriberAction.UNSUBSCRIBE);

            // the meta-subscriber never unsubscribes
            return SubscriberAction.CONTINUE;
        };
        for (final SingleNodePlatformStatusResults result : results) {
            result.subscribe(metaSubscriber);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<SingleNodePlatformStatusResults> results() {
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void subscribe(@NonNull final PlatformStatusSubscriber subscriber) {
        platformStatusSubscribers.add(subscriber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodePlatformStatusResults suppressingNode(@NonNull final NodeId nodeId) {
        final List<SingleNodePlatformStatusResults> filtered = results.stream()
                .filter(result -> !Objects.equals(nodeId, result.nodeId()))
                .toList();
        return new MultipleNodePlatformStatusResultsImpl(filtered);
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

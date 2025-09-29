// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.result.PlatformStatusSubscriber;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;

/**
 * Default implementation of {@link SingleNodePlatformStatusResult}
 */
public class SingleNodePlatformStatusResultImpl implements SingleNodePlatformStatusResult {

    private final NodeResultsCollector collector;

    // This class may be used in a multi-threaded context, so we use volatile to ensure visibility of state changes
    private volatile int startIndex = 0;

    /**
     * Creates a new instance of {@link SingleNodePlatformStatusResultImpl}.
     *
     * @param collector the {@link NodeResultsCollector} that collects the results
     */
    public SingleNodePlatformStatusResultImpl(@NonNull final NodeResultsCollector collector) {
        this.collector = requireNonNull(collector);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeId nodeId() {
        return collector.nodeId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<PlatformStatus> statusProgression() {
        return collector.currentStatusProgression(startIndex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable PlatformStatus currentStatus() {
        return statusProgression().getLast();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void subscribe(@NonNull final PlatformStatusSubscriber subscriber) {
        collector.subscribePlatformStatusSubscriber(subscriber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        startIndex = collector.currentStatusProgressionCount();
    }
}

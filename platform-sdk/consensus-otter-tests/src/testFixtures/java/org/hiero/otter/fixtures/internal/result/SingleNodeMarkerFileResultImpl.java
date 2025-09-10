// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.result.MarkerFileSubscriber;
import org.hiero.otter.fixtures.result.MarkerFilesStatus;
import org.hiero.otter.fixtures.result.SingleNodeMarkerFileResult;

/**
 * Default implementation of {@link SingleNodeMarkerFileResult}
 */
public class SingleNodeMarkerFileResultImpl implements SingleNodeMarkerFileResult {

    private final NodeResultsCollector collector;

    /**
     * Creates a new instance of {@link SingleNodeMarkerFileResultImpl}.
     *
     * @param collector the {@link NodeResultsCollector} that collects the results
     */
    public SingleNodeMarkerFileResultImpl(final NodeResultsCollector collector) {
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
    public MarkerFilesStatus status() {
        return collector.markerFilesStatus();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void subscribe(@NonNull final MarkerFileSubscriber subscriber) {
        collector.subscribeMarkerFileSubscriber(subscriber);
    }
}

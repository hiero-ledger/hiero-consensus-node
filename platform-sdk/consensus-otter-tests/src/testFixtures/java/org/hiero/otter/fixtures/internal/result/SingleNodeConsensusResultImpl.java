// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Default implementation of {@link SingleNodeConsensusResult}
 */
public class SingleNodeConsensusResultImpl implements SingleNodeConsensusResult {

    private final NodeResultsCollector collector;

    // This class may be used in a multithreaded context, so we use volatile to ensure visibility of state changes
    private volatile int startIndex = 0;

    /**
     * Creates a new instance of {@link SingleNodeConsensusResultImpl}.
     *
     * @param collector the {@link NodeResultsCollector} that collects the results
     */
    public SingleNodeConsensusResultImpl(@NonNull final NodeResultsCollector collector) {
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
    public List<ConsensusRound> consensusRounds() {
        return collector.currentConsensusRounds(startIndex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public EventWindow getLatestEventWindow() {
        if (consensusRounds().isEmpty()) {
            return EventWindow.getGenesisEventWindow();
        }
        // Return the event window of the latest consensus round
        return consensusRounds().getLast().getEventWindow();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void subscribe(@NonNull final ConsensusRoundSubscriber subscriber) {
        collector.subscribeConsensusRoundSubscriber(subscriber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        startIndex = collector.currentConsensusRoundsCount();
    }
}

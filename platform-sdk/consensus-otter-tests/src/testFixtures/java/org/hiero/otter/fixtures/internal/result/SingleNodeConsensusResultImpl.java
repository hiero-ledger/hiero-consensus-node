// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Default implementation of {@link SingleNodeConsensusResult}
 */
public class SingleNodeConsensusResultImpl implements SingleNodeConsensusResult {

    private final NodeResultsCollector collector;
    private volatile int startIndex = 0;

    /**
     * Creates a new instance of {@link SingleNodeConsensusResultImpl}.
     *
     * @param collector the {@link NodeResultsCollector} that collects the results
     */
    public SingleNodeConsensusResultImpl(@NonNull final NodeResultsCollector collector) {
        this.collector = Objects.requireNonNull(collector);
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
    public void subscribe(@NonNull final ConsensusRoundSubscriber subscriber) {
        collector.subscribe(subscriber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        startIndex = collector.currentConsensusRounds(0).size();
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.metrics;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

public class NoOpConsensusMetrics implements ConsensusMetrics {
    @Override
    public void addedEvent(final EventImpl event) {
        // no-op
    }

    @Override
    public void coinRound() {
        // no-op
    }

    @Override
    public void lastFamousInRound(final EventImpl event) {
        // no-op
    }

    @Override
    public void consensusReachedOnRound(final long round) {
        // no-op
    }

    @Override
    public void consensusReached(final EventImpl event) {
        // no-op
    }

    @Override
    public void consensusRoundEmitted(@NonNull final ConsensusRound round) {
        // no-op
    }

    @Override
    public void dotProductTime(final long nanoTime) {
        // no-op
    }

    @Override
    public void judgeWeights(long weight) {
        // no-op
    }

    @Override
    public void witnessesStronglySeen(final int numSeen) {
        // no-op
    }

    @Override
    public void roundIncrementedByStronglySeen() {
        // no-op
    }
}

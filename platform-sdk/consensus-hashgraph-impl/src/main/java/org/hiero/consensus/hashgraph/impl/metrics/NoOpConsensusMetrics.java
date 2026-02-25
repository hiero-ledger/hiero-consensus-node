// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.metrics;

import org.hiero.consensus.hashgraph.impl.EventImpl;

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
    public void consensusReachedOnRound() {
        // no-op
    }

    @Override
    public void consensusReached(final EventImpl event) {
        // no-op
    }

    @Override
    public void dotProductTime(final long nanoTime) {
        // no-op
    }

    @Override
    public double getAvgSelfCreatedTimestamp() {
        return 0;
    }

    @Override
    public double getAvgOtherReceivedTimestamp() {
        return 0;
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

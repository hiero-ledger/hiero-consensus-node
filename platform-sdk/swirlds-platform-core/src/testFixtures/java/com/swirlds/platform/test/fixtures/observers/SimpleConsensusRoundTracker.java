// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.observers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

public class SimpleConsensusRoundTracker {
    private final Map<ObservationType, Set<Long>> consensusRoundsObserved;

    public SimpleConsensusRoundTracker() {
        this.consensusRoundsObserved = new HashMap<>();
        for (final ObservationType type : ObservationType.values()) {
            this.consensusRoundsObserved.put(type, new HashSet<>());
        }
    }

    public void observe(final ObservationType observation, final ConsensusRound consensusRound) {
        if (isObserved(observation, consensusRound)) {
            throw new RuntimeException("Consensus round should not be observed twice");
        }
    }

    public boolean isObserved(final ObservationType observation, final ConsensusRound consensusRound) {
        return consensusRoundsObserved.get(observation).contains(consensusRound.getRoundNum());
    }

    public void clear() {
        for (final Set<Long> value : consensusRoundsObserved.values()) {
            value.clear();
        }
    }
}

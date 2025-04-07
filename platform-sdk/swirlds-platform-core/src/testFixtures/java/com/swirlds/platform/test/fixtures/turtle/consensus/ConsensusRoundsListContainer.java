// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.consensus;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;

/**
 * A container for collecting list of consensus rounds produced by the ConsensusEngine using List.
 */
public class ConsensusRoundsListContainer implements ConsensusRoundsHolder {

    final Map<Long, ConsensusRound> collectedRounds = new TreeMap<>();
    final NodeId selfNodeId;

    public ConsensusRoundsListContainer(final NodeId selfNodeId) {
        this.selfNodeId = selfNodeId;
    }

    @Override
    public void interceptRounds(final List<ConsensusRound> rounds) {
        for (final ConsensusRound round : rounds) {
            final long roundNumber = round.getRoundNum();

            assertThat(collectedRounds)
                    .withFailMessage(String.format(
                            "Round with number %d has been already" + " produced by node %d",
                            roundNumber, selfNodeId.id()))
                    .doesNotContainKey(roundNumber);
            collectedRounds.put(roundNumber, round);
        }
    }

    @Override
    public void clear(final Set<Long> roundNumbers) {
        for (final Long roundNumber : roundNumbers) {
            collectedRounds.remove(roundNumber);
        }
    }

    @NonNull
    @Override
    public Map<Long, ConsensusRound> getCollectedRounds() {
        return collectedRounds;
    }

    @Override
    public List<ConsensusRound> getFilteredConsensusRounds(@NonNull final Set<Long> roundNums) {
        return collectedRounds.entrySet().stream()
                .filter(e -> roundNums.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }
}

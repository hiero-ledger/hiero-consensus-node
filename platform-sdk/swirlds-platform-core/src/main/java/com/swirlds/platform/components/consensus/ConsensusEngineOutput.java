// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.consensus;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Output of the {@link ConsensusEngine}
 *
 * @param consensusRounds    the list of consensus rounds that came to consensus as a result of adding events
 * @param preConsensusEvents the list of pre-consensus events that are waiting to reach consensus
 */
public record ConsensusEngineOutput(
        @NonNull List<ConsensusRound> consensusRounds, @NonNull List<PlatformEvent> preConsensusEvents) {
    /** Empty singleton instance */
    private static final ConsensusEngineOutput EMPTY_INSTANCE = new ConsensusEngineOutput(List.of(), List.of());

    /**
     * Returns an instance of {@link ConsensusEngineOutput} which contains no rounds and no pre-consensus events.
     *
     * @return an empty instance
     */
    public static ConsensusEngineOutput emptyInstance() {
        return EMPTY_INSTANCE;
    }
}

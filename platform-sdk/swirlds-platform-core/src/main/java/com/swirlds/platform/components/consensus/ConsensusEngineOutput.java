// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.consensus;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Output of the {@link ConsensusEngine}. For more details, see {@link ConsensusEngine#addEvent(PlatformEvent)}
 *
 * @param consensusRounds    the list of consensus rounds that came to consensus as a result of adding events
 * @param preConsensusEvents the list of pre-consensus events that are waiting to reach consensus
 * @param staleEvents        a list of events that became stale as a result of consensus advancing
 */
public record ConsensusEngineOutput(
        @NonNull List<ConsensusRound> consensusRounds,
        @NonNull List<PlatformEvent> preConsensusEvents,
        @NonNull List<PlatformEvent> staleEvents) {
    /** Empty singleton instance */
    private static final ConsensusEngineOutput EMPTY_INSTANCE =
            new ConsensusEngineOutput(List.of(), List.of(), List.of());

    /**
     * Returns an instance of {@link ConsensusEngineOutput} which contains no rounds, no pre-consensus events and no
     * stale events.
     *
     * @return an empty instance
     */
    public static ConsensusEngineOutput emptyInstance() {
        return EMPTY_INSTANCE;
    }
}

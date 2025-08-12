// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.consensus;

import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

public record ConsensusEngineOutput(List<ConsensusRound> consensusRounds, List<PlatformEvent> preConsensusEvents) {
    public static final ConsensusEngineOutput EMPTY_INSTANCE = new ConsensusEngineOutput(List.of(), List.of());
}

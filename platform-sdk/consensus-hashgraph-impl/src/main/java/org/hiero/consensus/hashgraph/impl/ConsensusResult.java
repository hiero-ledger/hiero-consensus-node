package org.hiero.consensus.hashgraph.impl;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

public record ConsensusResult(@NonNull ConsensusRound consensusRound,
                              @NonNull List<PlatformEvent> staleEvents) {
}

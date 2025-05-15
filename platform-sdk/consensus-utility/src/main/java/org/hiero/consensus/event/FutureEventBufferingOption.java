package org.hiero.consensus.event;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.EventWindow;

public enum FutureEventBufferingOption {
    PENDING_CONSENSUS_ROUND,
    EVENT_BIRTH_ROUND;

    public long getOldestRoundToBuffer(@NonNull final EventWindow eventWindow) {
        return switch (this) {
            case PENDING_CONSENSUS_ROUND -> eventWindow.getPendingConsensusRound() + 1;
            case EVENT_BIRTH_ROUND -> eventWindow.getEventBirthRound() + 1;
        };
    }
}

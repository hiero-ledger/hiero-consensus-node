// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.state.snapshot.StateToDiskReason;

/**
 * The result of sealing a consensus round.
 *
 * @param shouldSignState whether the platform may create a signed state for this round
 * @param requestedStateToSaveReason the reason this round's signed state should be saved, if any
 */
public record SealConsensusRoundResult(boolean shouldSignState, @Nullable StateToDiskReason requestedStateToSaveReason) {
    private static final SealConsensusRoundResult NOT_SIGNABLE = new SealConsensusRoundResult(false, null);
    private static final SealConsensusRoundResult SAVED_STATE_NOT_NEEDED = new SealConsensusRoundResult(true, null);

    public static SealConsensusRoundResult notSignable() {
        return NOT_SIGNABLE;
    }

    public static SealConsensusRoundResult signableButSavedStateNotNeeded() {
        return SAVED_STATE_NOT_NEEDED;
    }

    public static SealConsensusRoundResult signableWithSavedStateReason(
            final StateToDiskReason requestedStateToSaveReason) {
        return new SealConsensusRoundResult(true, requestedStateToSaveReason);
    }
}

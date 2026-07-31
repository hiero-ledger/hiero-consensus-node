// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.state;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * The result of a successful state saving operation.
 *
 * @param round                              the round of the state saved to disk
 * @param freezeState                        true if the state was freeze state, false otherwise
 * @param consensusTimestamp                 the consensus timestamp of the state saved to disk
 * @param oldestRestartableConsensusSnapshot as part of the state saving operation, old states are deleted from disk.
 *                                           This value is the consensus snapshot of the oldest state on disk that we
 *                                           must be able to restart from
 */
public record StateSavingResult(
        long round, boolean freezeState, @NonNull Instant consensusTimestamp,
        @Nullable ConsensusSnapshot oldestRestartableConsensusSnapshot) {
}

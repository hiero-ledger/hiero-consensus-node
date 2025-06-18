// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.state;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * The result of a successful state saving operation.
 *
 * @param round                         the round of the state saved to disk
 * @param freezeState                   true if the state was freeze state, false otherwise
 * @param consensusTimestamp            the consensus timestamp of the state saved to disk
 * @param oldestMinimumBirthRoundOnDisk as part of the state saving operation, old states are deleted from disk. This
 *                                      value represents the minimum birth round non-ancient of the oldest state on disk
 */
public record StateSavingResult(
        long round, boolean freezeState, @NonNull Instant consensusTimestamp, long oldestMinimumBirthRoundOnDisk) {}

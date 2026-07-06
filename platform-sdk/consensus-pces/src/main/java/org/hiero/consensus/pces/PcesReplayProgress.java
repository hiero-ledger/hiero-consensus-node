// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Represents the progress of a PCES replay.
 *
 * @param round the round that was replayed, {@code 0} if the progress cannot be determined
 * @param consensusTimestamp the consensus timestamp of the round that was replayed, {@code null} if the progress cannot be determined
 */
public record PcesReplayProgress(long round, @Nullable Instant consensusTimestamp) {

    /** The replay progress that is empty. This is used to indicate that the progress cannot be determined. */
    public static final PcesReplayProgress EMPTY = new PcesReplayProgress(0L, null);
}

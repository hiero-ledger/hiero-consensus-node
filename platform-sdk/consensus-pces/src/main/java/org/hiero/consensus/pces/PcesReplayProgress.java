// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Represents the progress of a PCES replay.
 *
 * @param round the round that was replayed
 * @param consensusTimestamp the consensus timestamp of the round that was replayed
 */
public record PcesReplayProgress(long round, @Nullable Instant consensusTimestamp) {

    /** The default replay progress. */
    public static final PcesReplayProgress EMPTY = new PcesReplayProgress(0L, null);
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * A snapshot of how far PCES replay has progressed, captured at the moment this record is created.
 *
 * <p>Replay advances continuously as rounds reach consensus, so these values describe the latest round to reach
 * consensus <i>at the time the snapshot was taken</i>, not the final outcome of the replay. Comparing two snapshots
 * (for example one taken before and one after a replay) reveals the progress made in between.
 *
 * @param round the number of the latest round to reach consensus at the time this snapshot was taken, or {@code 0} if
 *     no round had reached consensus yet or the progress could not be determined
 * @param consensusTimestamp the consensus timestamp of that round, or {@code null} if no round had reached consensus yet
 *     or the progress could not be determined
 */
public record PcesReplayProgress(long round, @Nullable Instant consensusTimestamp) {

    /**
     * A snapshot indicating that no progress is known: no round has reached consensus, or the progress could not be
     * determined.
     */
    public static final PcesReplayProgress EMPTY = new PcesReplayProgress(0L, null);
}

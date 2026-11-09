// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.stream.Collectors;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Snapshot helpers for tests that need to continue an existing graph rather than start from genesis.
 * <p>
 * A snapshot anchor is what makes the restart, init-judge and roster-change code paths reachable at all: a roster is
 * supplied only at construction, so testing a roster change means continuing an already-built graph under a new
 * roster, not starting over.
 */
public final class ConsensusSnapshots {

    private ConsensusSnapshots() {}

    /**
     * The snapshot taken at a named restartRoundNum.
     * <p>
     * This takes the rounds rather than the harness that produced them, so it serves any harness.
     *
     * @param rounds the rounds a first run produced, in order
     * @param restartRoundNum  the round number to take the snapshot at
     * @return that restartRoundNum's snapshot
     * @throws IllegalArgumentException if no restartRoundNum with that number reached consensus
     */
    @NonNull
    public static ConsensusSnapshot snapshotAtRound(
            @NonNull final List<ConsensusRound> rounds, final long restartRoundNum) {
        return rounds.stream()
                .filter(consensusRound -> consensusRound.getRoundNum() == restartRoundNum)
                .findFirst()
                .map(ConsensusRound::getSnapshot)
                .orElseThrow(() -> new IllegalArgumentException("Round %d did not reach consensus. Rounds available: %s"
                        .formatted(restartRoundNum, roundNumbers(rounds))));
    }

    @NonNull
    private static String roundNumbers(@NonNull final List<ConsensusRound> rounds) {
        return rounds.stream()
                .map(ConsensusRound::getRoundNum)
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
    }
}

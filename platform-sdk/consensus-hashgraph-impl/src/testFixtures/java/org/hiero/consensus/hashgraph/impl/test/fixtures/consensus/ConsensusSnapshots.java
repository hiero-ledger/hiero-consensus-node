// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.consensus;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Snapshot helpers for tests that need to continue an existing graph rather than start from genesis.
 * <p>
 * A snapshot anchor is what makes the restart, init-judge and roster-change family reachable at all: a roster is
 * supplied only at construction, so testing a roster change means continuing an already-built graph under a new
 * roster, not starting over.
 * <p>
 * Snapshots are always <b>captured from a real run, never hand-assembled</b>. The engine cannot produce a snapshot
 * whose {@code minimumJudgeInfoList} is shorter than the non-ancient window — {@code getAncientThreshold} throws if no
 * entry exists for exactly the round it computes — so a hand-built snapshot risks encoding a state that could never
 * occur.
 */
public final class ConsensusSnapshots {

    private ConsensusSnapshots() {}

    /**
     * The snapshot taken at a named round.
     * <p>
     * This takes the rounds rather than the harness that produced them, so it serves any harness.
     *
     * @param rounds the rounds a first run produced, in order
     * @param round  the round to take the snapshot at
     * @return that round's snapshot
     * @throws IllegalArgumentException if no round with that number reached consensus
     */
    @NonNull
    public static ConsensusSnapshot snapshotAtRound(@NonNull final List<ConsensusRound> rounds, final long round) {
        return rounds.stream()
                .filter(consensusRound -> consensusRound.getRoundNum() == round)
                .findFirst()
                .map(ConsensusRound::getSnapshot)
                .orElseThrow(() -> new IllegalArgumentException("Round %d did not reach consensus. Rounds available: %s"
                        .formatted(round, roundNumbers(rounds))));
    }

    /**
     * Check that every judge named in a snapshot is among the events the test is going to supply.
     * <p>
     * Call this before replaying the continuation. Consensus correctly blocks until all init judges arrive — in
     * production they come by gossip — so a fixture that omits one simply produces no consensus rounds and then fails
     * much later on an assertion that says nothing about the real cause. There is nothing for {@code ConsensusImpl} to
     * do differently; the check belongs here, on the test side.
     *
     * @param snapshot             the snapshot about to be loaded
     * @param suppliedEventHashes  the content hashes of every event the continuation will supply
     * @throws IllegalArgumentException naming the judges that are missing
     */
    public static void requireJudgesPresent(
            @NonNull final ConsensusSnapshot snapshot, @NonNull final Collection<Hash> suppliedEventHashes) {

        final Set<Hash> supplied = Set.copyOf(suppliedEventHashes);
        final List<Hash> missing = snapshot.judgeIds().stream()
                .map(judgeId -> new Hash(judgeId.judgeHash()))
                .filter(judgeHash -> !supplied.contains(judgeHash))
                .toList();

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(("The snapshot for round %d names %d judge(s) that the graph does not "
                            + "supply: %s. Consensus blocks until every init judge arrives, so this fixture would "
                            + "produce no rounds at all. Include these events in the replay.")
                    .formatted(snapshot.round(), missing.size(), missing));
        }
    }

    @NonNull
    private static String roundNumbers(@NonNull final List<ConsensusRound> rounds) {
        return rounds.stream()
                .map(ConsensusRound::getRoundNum)
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
    }
}

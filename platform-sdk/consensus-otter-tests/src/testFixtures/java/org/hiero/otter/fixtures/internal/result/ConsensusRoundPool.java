// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.utility.Pair;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;

/**
 * A thread-safe pool that deduplicates identical ConsensusRound objects across nodes.
 *
 * <p>This class maintains instances of ConsensusRounds indexed by round number.
 * When multiple nodes produce identical rounds, only one instance is maintained in memory.
 *
 * <p>The pool is to be shared across all nodes in a network.
 */
public class ConsensusRoundPool {

    /**
     * Maps round number to ConsensusRound instance.
     */
    private final ConcurrentHashMap<Long, ConsensusRound> roundsByNumber = new ConcurrentHashMap<>();
    /**
     * Maps round number to all reporting nodes.
     */
    private final ConcurrentHashMap<Long, Set<NodeId>> roundsReports = new ConcurrentHashMap<>();
    /**
     * Maps rounds that are different from the first registered round.
     */
    private final ConcurrentHashMap<Pair<NodeId, Long>, ConsensusRound> discrepantRounds = new ConcurrentHashMap<>();

    /**
     * Lifecycle flag to prevent operations after pool is destroyed.
     */
    private volatile boolean destroyed = false;

    /**
     * Inserts a consensus round in the pool. If an equivalent round already exists for this
     * round number, returns the existing instance. Otherwise, stores and returns
     * the provided round.
     *
     * <p>If a round with the same number but different content already exists, this indicates
     * a consensus failure. The method stores the round on a different cache to be able to retrieve the reported value later and
     * (not the existing one) to allow future assertions to detect the mismatch.
     *
     * @param round the round to intern
     * @param nodeId the node contributing this round
     * @return the canonical instance (either existing or newly stored)
     */
    @NonNull
    public ConsensusRound update(@NonNull final ConsensusRound round, @NonNull final NodeId nodeId) {
        requireNonNull(round, "round must not be null");
        requireNonNull(nodeId, "nodeId must not be null");

        if (destroyed) {
            // Pool is destroyed, don't update
            return round;
        }

        final long roundNum = round.getRoundNum();

        // insert if absent
        final ConsensusRound existing = roundsByNumber.putIfAbsent(roundNum, round);

        if (existing == null) {
            // First time a round is reported - Update reporting node
            final Set<NodeId> nodes = ConcurrentHashMap.newKeySet();
            nodes.add(nodeId);
            roundsReports.put(roundNum, nodes);
            return round;
        } else {
            // the round was already reported, add the node in the reporting set for that round
            roundsReports.computeIfPresent(roundNum, (a, s) -> {
                s.add(nodeId);
                return s;
            });
        }

        // Round already exists - validate it matches the new one
        if (!existing.equals(round)) {
            // should not happen in correct consensus
            this.discrepantRounds.put(Pair.of(nodeId, roundNum), round);
            // Return the new round (not the existing one) to allow assertions to detect the issue
            return round;
        }

        // Return the canonical instance
        return existing;
    }

    /**
     * Gets a round by its round number.
     *
     * @param roundNumber the round number
     * @param nodeId the nodeId querying for the result
     * @return the round, or null if not present in the pool
     */
    @Nullable
    public ConsensusRound getRound(final long roundNumber, @NonNull final NodeId nodeId) {

        if (!roundsReports.get(roundNumber).contains(nodeId)) {
            // This node did not report a round object for that round number
            return null;
        }

        final var discrepant = discrepantRounds.get(Pair.of(nodeId, roundNumber));
        if (nonNull(discrepant)) {
            // This node reported something different, return the discrepant round to allow assertions to detect the
            // issue
            return discrepant;
        }
        return roundsByNumber.get(roundNumber);
    }

    /**
     * Returns all the consensus rounds created at the moment of invocation, starting with and including the provided
     * index.
     *
     * @param startIndex the index to start from
     * @param nodeId the nodeId querying for the result
     * @return the list of consensus rounds
     */
    @NonNull
    public List<ConsensusRound> currentConsensusRounds(final int startIndex, @NonNull final NodeId nodeId) {
        return roundsByNumber.keySet().stream()
                .sorted()
                .skip(startIndex)
                .map(i -> getRound(i, nodeId))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Clears all rounds from the pool and marks it as destroyed.
     */
    public void destroy() {
        destroyed = true;
        roundsByNumber.clear();
        discrepantRounds.clear();
        roundsReports.clear();
    }

    /**
     * Returns the size of the pool.
     *
     * @return pool size
     */
    public int size(final @NonNull NodeId nodeId) {
        return currentConsensusRounds(0, nodeId).size();
    }
}

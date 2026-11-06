// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * A thread-safe pool that deduplicates identical ConsensusRound objects across nodes.
 *
 * <p>Two rounds are identical if they have the same round number and are equal. When multiple nodes produce identical
 * rounds, only one instance is maintained in memory. If nodes produce different rounds with the same round number, the
 * pool keeps each of them, so that assertions can detect the mismatch.
 *
 * <p>The pool is to be shared across all nodes in a network.
 */
public class ConsensusRoundPool {

    /** The pool of consensus rounds, keyed by round number. */
    private final Map<Long, Set<ConsensusRound>> roundCache = new ConcurrentHashMap<>();

    /** Lifecycle flag to prevent operations after pool is destroyed. */
    private volatile boolean destroyed = false;

    /**
     * Inserts a consensus round in the pool. If an identical round already exists, returns the existing instance.
     * Otherwise, stores and returns the provided round.
     *
     * @param round the round to intern
     * @return the canonical instance (either existing or newly stored)
     */
    @NonNull
    public ConsensusRound canonicalize(@NonNull final ConsensusRound round) {
        requireNonNull(round, "round must not be null");

        if (destroyed) {
            // Pool is destroyed, don't update
            return round;
        }

        final Set<ConsensusRound> rounds =
                roundCache.computeIfAbsent(round.getRoundNum(), _ -> ConcurrentHashMap.newKeySet());
        return rounds.add(round)
                ? round
                : rounds.stream().filter(round::equals).findFirst().orElseThrow();
    }

    /**
     * Clears all rounds from the pool and marks it as destroyed.
     */
    public void destroy() {
        destroyed = true;
        roundCache.clear();
        roundCache.clear();
    }
}

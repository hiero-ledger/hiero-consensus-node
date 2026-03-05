// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
    private final ConcurrentHashMap<Long, Map<ConsensusRound, Set<NodeId>>> roundCache = new ConcurrentHashMap<>();

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

        final Map<ConsensusRound, Set<NodeId>> consensusRoundSetMap =
                roundCache.compute(roundNum, (key, existingMap) -> {
                    if (existingMap == null) {
                        // First time seeing this round number - create new newMap
                        final Map<ConsensusRound, Set<NodeId>> newMap = new ConcurrentHashMap<>();
                        final Set<NodeId> value = new HashSet<>();
                        value.add(nodeId);
                        newMap.put(round, value);
                        return newMap;
                    } else {
                        // Round number exists - update existing map
                        existingMap.computeIfAbsent(round, k -> new HashSet<>()).add(nodeId);
                        return existingMap;
                    }
                });

        // Return the canonical instance
        return findRoundForNode(consensusRoundSetMap, nodeId);
    }

    /**
     * Gets a round by its round number and reporting nodeId.
     *
     * @param roundNumber the round number
     * @param nodeId the nodeId querying for the result
     * @return the round, or null if not present in the pool
     */
    @Nullable
    public ConsensusRound getRound(final long roundNumber, @NonNull final NodeId nodeId) {
        return findRoundForNode(roundCache.get(roundNumber), nodeId);
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
        return roundCache.keySet().stream()
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
        roundCache.clear();
    }

    /**
     * Returns the size of the pool.
     *
     * @return pool size
     */
    public int size(final @NonNull NodeId nodeId) {
        return currentConsensusRounds(0, nodeId).size();
    }

    /**
     * Returns the ConsensusRound instance in the map corresponding to the NodeId.
     *
     * @return the ConsensusRound instance in the map corresponding to the NodeId.
     */
    private static ConsensusRound findRoundForNode(
            @NonNull final Map<ConsensusRound, Set<NodeId>> consensusRoundPerNodesMap, @NonNull final NodeId nodeId) {
        return consensusRoundPerNodesMap.entrySet().stream()
                .filter(e -> e.getValue().contains(nodeId))
                .map(Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}

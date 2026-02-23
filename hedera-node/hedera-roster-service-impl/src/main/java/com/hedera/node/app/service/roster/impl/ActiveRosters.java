// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.roster.impl;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.hiero.consensus.roster.ReadableRosterStore;

/**
 * Contains the active rosters for the {@link RosterServiceImpl}'s current phase; and the <b>transition</b> from a
 * <b>source roster</b> to a <b>target roster</b>, if applicable.
 * <p>
 * Recall the {@link RosterServiceImpl} has three phases, each of which involves either one or two active rosters; and
 * possibly implies a roster transition:
 * <ol>
 *     <li>{@link Phase#BOOTSTRAP} - There is a single active roster, the <b>genesis roster</b>, with an implied
 *     special transition in which the genesis roster serves as both source and target.</li>
 *     <li>{@link Phase#TRANSITION} - There are two active rosters, the <b>current roster</b> and the
 *     <b>candidate roster</b>; and an explicit transition from the former to the latter.</li>
 *     <li>{@link Phase#HANDOFF} - There is only one active roster (the current roster), and there is no transition.</li>
 * </ol>
 */
public class ActiveRosters {
    private static final int HEX_PREFIX_LENGTH = 6;

    private final Phase phase;

    @Nullable
    private final Bytes sourceRosterHash;

    private final Bytes targetRosterHash;
    private final Function<Bytes, Roster> lookup;

    /**
     * The phase of the {@link RosterServiceImpl} in which these active rosters are being used.
     */
    public enum Phase {
        /**
         * The {@link RosterServiceImpl} is in the bootstrap phase.
         */
        BOOTSTRAP,
        /**
         * The {@link RosterServiceImpl} is in a transition phase.
         */
        TRANSITION,
        /**
         * The {@link RosterServiceImpl} is in a handoff phase.
         */
        HANDOFF,
    }

    /**
     * Returns the active rosters for a given {@link ReadableRosterStore}.
     *
     * @param rosterStore the roster store
     * @return the active rosters for the given roster store
     */
    public static ActiveRosters from(@NonNull final ReadableRosterStore rosterStore) {
        final var currentRosterHash = requireNonNull(rosterStore.getCurrentRosterHash());
        final var candidateRosterHash = rosterStore.getCandidateRosterHash();
        if (candidateRosterHash == null) {
            if (rosterStore.getPreviousRosterHash() == null) {
                return new ActiveRosters(Phase.BOOTSTRAP, currentRosterHash, currentRosterHash, rosterStore::get);
            } else {
                return new ActiveRosters(Phase.HANDOFF, null, currentRosterHash, rosterStore::get);
            }
        } else {
            return new ActiveRosters(Phase.TRANSITION, currentRosterHash, candidateRosterHash, rosterStore::get);
        }
    }

    private ActiveRosters(
            @NonNull final Phase phase,
            @Nullable final Bytes sourceRosterHash,
            @NonNull final Bytes targetRosterHash,
            @NonNull final Function<Bytes, Roster> lookup) {
        this.phase = requireNonNull(phase);
        this.lookup = requireNonNull(lookup);
        this.sourceRosterHash = sourceRosterHash;
        this.targetRosterHash = requireNonNull(targetRosterHash);
    }

    /**
     * Returns the phase of the {@link RosterServiceImpl} in which these active rosters are being used.
     */
    public Phase phase() {
        return phase;
    }

    /**
     * Returns the related roster with the given hash, if one exists.
     *
     * @param rosterHash the hash of the roster to find
     */
    public @Nullable Roster findRelatedRoster(@NonNull final Bytes rosterHash) {
        return lookup.apply(rosterHash);
    }

    /**
     * Returns the current roster hash.
     */
    public @NonNull Bytes currentRosterHash() {
        return switch (phase) {
            case BOOTSTRAP, HANDOFF -> targetRosterHash;
            case TRANSITION -> requireNonNull(sourceRosterHash);
        };
    }

    /**
     * Assuming the {@link RosterServiceImpl} is in a transition phase, returns the source roster hash.
     *
     * @throws IllegalStateException if the {@link RosterServiceImpl} is not in a transition phase
     */
    public @NonNull Bytes sourceRosterHash() {
        return switch (phase) {
            case BOOTSTRAP, TRANSITION -> requireNonNull(sourceRosterHash);
            case HANDOFF -> throw new IllegalStateException("No source roster hash in handoff phase");
        };
    }

    /**
     * Assuming the {@link RosterServiceImpl} is in a transition phase, returns the target roster hash.
     *
     * @throws IllegalStateException if the {@link RosterServiceImpl} is not in a transition phase
     */
    public @NonNull Bytes targetRosterHash() {
        return switch (phase) {
            case BOOTSTRAP, TRANSITION -> targetRosterHash;
            case HANDOFF -> throw new IllegalStateException("No target roster hash in handoff phase");
        };
    }

    /**
     * Assuming the {@link RosterServiceImpl} is in a transition phase, returns the target roster.
     *
     * @throws IllegalStateException if the {@link RosterServiceImpl} is not in a transition phase
     */
    public @NonNull Roster targetRoster() {
        return switch (phase) {
            case BOOTSTRAP, TRANSITION -> lookup.apply(targetRosterHash);
            case HANDOFF -> throw new IllegalStateException("No target roster in handoff phase");
        };
    }

    /**
     * Returns the current roster.
     */
    public @NonNull Roster currentRoster() {
        return lookup.apply(currentRosterHash());
    }

    /**
     * Assuming the {@link RosterServiceImpl} is in a transition phase, returns the transition weights
     * from the source roster to the target roster.
     *
     * @param sourceWeights the source weights, if they are not to be derived from the source roster
     * @throws IllegalStateException if the {@link RosterServiceImpl} is in a handoff phase
     */
    public RosterTransitionWeights transitionWeights(@Nullable final SortedMap<Long, Long> sourceWeights) {
        return switch (phase) {
            case BOOTSTRAP, TRANSITION ->
                new RosterTransitionWeights(
                        sourceWeights != null ? sourceWeights : weightsFrom(lookup.apply(sourceRosterHash)),
                        weightsFrom(lookup.apply(targetRosterHash)));
            case HANDOFF -> throw new IllegalStateException("No target roster in handoff phase");
        };
    }

    /**
     * Assuming the {@link RosterServiceImpl} is in a transition phase, returns the possibly empty set of
     * node IDs that were removed from the source roster in the target roster.
     */
    public SortedSet<Long> removedNodeIds() {
        return switch (phase) {
            case BOOTSTRAP, TRANSITION -> {
                final var targetNodeIds = lookup.apply(targetRosterHash).rosterEntries().stream()
                        .map(RosterEntry::nodeId)
                        .collect(toSet());
                yield lookup.apply(sourceRosterHash).rosterEntries().stream()
                        .map(RosterEntry::nodeId)
                        .filter(o -> !targetNodeIds.contains(o))
                        .collect(toCollection(TreeSet::new));
            }
            case HANDOFF -> throw new IllegalStateException("No target roster in handoff phase");
        };
    }

    @Override
    public String toString() {
        return "ActiveRosters{" + "phase="
                + phase + ", source="
                + Optional.ofNullable(sourceRosterHash)
                        .map(ActiveRosters::toHex)
                        .orElse("<NONE>") + ", target="
                + toHex(targetRosterHash) + '}';
    }

    /**
     * Returns the weights of the nodes in the given roster in a map.
     * @param roster the roster
     * @return the weights of the nodes in the roster
     */
    public static @NonNull SortedMap<Long, Long> weightsFrom(@NonNull final Roster roster) {
        return requireNonNull(roster).rosterEntries().stream()
                .collect(toMap(RosterEntry::nodeId, RosterEntry::weight, (a, b) -> a, TreeMap::new));
    }

    private static String toHex(@NonNull final Bytes bytes) {
        return bytes.toHex().substring(0, HEX_PREFIX_LENGTH) + "...";
    }
}

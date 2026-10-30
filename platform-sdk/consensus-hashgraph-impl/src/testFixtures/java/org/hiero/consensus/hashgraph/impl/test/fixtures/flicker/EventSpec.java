// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The specification of one event, before any event exists.
 * <p>
 * Parents are named rather than referenced, so a graph can be written as a flat list in which every event names the
 * ones below it. {@link GraphBuilder} resolves the names and builds the events in order.
 * <p>
 * The three nullable override fields are the per-event half of {@link GraphDefaults}: null means "take the graph's
 * default", a value means this event differs. They are separate from the structural fields because they are the ones
 * a {@code .graph} row states only when it changes them.
 *
 * @param name             the fixture's name for this event, unique within its graph
 * @param creator          index into the roster's node ids — not a node id, so one graph can run under any roster
 * @param selfParent       the name of this creator's previous event, or null for a genesis event
 * @param otherParents     the names of the other parents, possibly none
 * @param coin             overrides the graph's default coin, or null to take it
 * @param birthRound       overrides the birth round derived from this event's parents, which it may only raise, or
 *                         null to take the derived value
 * @param transactionCount overrides the graph's default transaction count, or null to take it
 */
public record EventSpec(
        @NonNull String name,
        int creator,
        @Nullable String selfParent,
        @NonNull List<String> otherParents,
        @Nullable Long coin,
        @Nullable Long birthRound,
        @Nullable Integer transactionCount) {

    public EventSpec {
        requireNonNull(name, "name must not be null");
        otherParents = List.copyOf(requireNonNull(otherParents, "otherParents must not be null"));
    }

    /**
     * An event with a self-parent and any number of other-parents, taking every graph default.
     *
     * @param name         the fixture's name for this event
     * @param creator      index into the roster's node ids
     * @param selfParent   the name of this creator's previous event
     * @param otherParents the names of the other parents
     * @return the spec
     */
    @NonNull
    public static EventSpec event(
            @NonNull final String name,
            final int creator,
            @NonNull final String selfParent,
            @NonNull final String... otherParents) {
        return new EventSpec(name, creator, selfParent, List.of(otherParents), null, null, null);
    }

    /**
     * A genesis event: no self-parent, no other-parents.
     *
     * @param name    the fixture's name for this event
     * @param creator index into the roster's node ids
     * @return the spec
     */
    @NonNull
    public static EventSpec genesis(@NonNull final String name, final int creator) {
        return new EventSpec(name, creator, null, List.of(), null, null, null);
    }

    /**
     * @param newCoin the coin value this event should carry
     * @return a copy of this spec overriding the graph's default coin
     */
    @NonNull
    public EventSpec withCoin(final long newCoin) {
        return new EventSpec(name, creator, selfParent, otherParents, newCoin, birthRound, transactionCount);
    }

    /**
     * @param newBirthRound the birth round this event should carry, which may not be below its parents'
     * @return a copy of this spec overriding the derived birth round
     */
    @NonNull
    public EventSpec withBirthRound(final long newBirthRound) {
        return new EventSpec(name, creator, selfParent, otherParents, coin, newBirthRound, transactionCount);
    }

    /**
     * @param newTransactionCount how many transactions this event should carry
     * @return a copy of this spec overriding the graph's default transaction count
     */
    @NonNull
    public EventSpec withTransactionCount(final int newTransactionCount) {
        return new EventSpec(name, creator, selfParent, otherParents, coin, birthRound, newTransactionCount);
    }

    /**
     * @return every parent this event names, self-parent first, in a single list
     */
    @NonNull
    public List<String> allParents() {
        final List<String> all = new ArrayList<>(otherParents.size() + 1);
        if (selfParent != null) {
            all.add(selfParent);
        }
        all.addAll(otherParents);
        return all;
    }
}

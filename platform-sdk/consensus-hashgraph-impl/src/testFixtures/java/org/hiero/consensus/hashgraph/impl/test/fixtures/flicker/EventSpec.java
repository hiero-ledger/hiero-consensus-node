// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * The specification of one event, before any event exists.
 * <p>
 * Parents are named rather than referenced, so a graph can be written as a flat list in which every event names the
 * ones below it. {@link GraphBuilder} resolves the names and builds the events in order.
 *
 * @param name         the fixture's name for this event, unique within its graph
 * @param creator      index into the roster's node ids — not a node id, so one graph can run under any roster
 * @param selfParent   the name of this creator's previous event, or null for a genesis event
 * @param otherParents the names of the other parents, possibly none
 */
public record EventSpec(
        @NonNull String name,
        int creator,
        @Nullable String selfParent,
        @NonNull String... otherParents) {

    /**
     * An event with a self-parent and any number of other-parents.
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
        return new EventSpec(name, creator, selfParent, otherParents);
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
        return new EventSpec(name, creator, null);
    }
}

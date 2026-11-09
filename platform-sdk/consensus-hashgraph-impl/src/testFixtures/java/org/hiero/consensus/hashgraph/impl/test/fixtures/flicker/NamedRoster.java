// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;

/**
 * A roster whose nodes are named by letter: a name, and a weight per node letter. Each roster block in a
 * {@code .graph} header is one of these.
 * <p>
 * A graph names each event's creator by the letter that starts the event's name, and {@link #nodeIdFor} gives every
 * letter one node id that depends on the letter alone. A roster built here therefore uses the same ids as the events
 * {@link GraphBuilder} builds, whatever weights it gives them.
 * <p>
 * Weights are declared in the {@code .graph} file itself rather than in a separate roster file. A fixture is reviewed
 * by checking the reasoning about what its weights make consensus do, and that reasoning sits with the graph, in the
 * file's comments and in the test that uses it; a separate roster file would put the weights somewhere else.
 * Lifting a captured production roster is a different workflow — that belongs with incident replay, not here.
 *
 * @param name            the roster's name, such as {@code initial} or {@code after}
 * @param weightsByLetter each node's letter mapped to its weight, in declaration order. The order is the order of the
 *                        roster's entries and of the block {@link GraphWriter} writes, so the map is sequenced to
 *                        keep both reproducible
 */
public record NamedRoster(@NonNull String name, @NonNull SequencedMap<String, Long> weightsByLetter) {

    /** The node id of {@code a}, one above the first node id; see {@link #nodeIdFor} for why. */
    private static final long ID_ORIGIN = NodeId.FIRST_NODE_ID.id() + 1;

    /** The gaps between consecutive letters' ids, repeated in this order; see {@link #nodeIdFor} for why. */
    private static final long[] GAPS = {1, 3, 2};

    public NamedRoster {
        requireNonNull(name, "name must not be null");
        weightsByLetter = Collections.unmodifiableSequencedMap(
                new LinkedHashMap<>(requireNonNull(weightsByLetter, "weights must not be null")));
    }

    /**
     * Returns the letters of all nodes in this named roster.
     *
     * @return the node letters, in declaration order
     */
    @NonNull
    public List<String> letters() {
        return List.copyOf(weightsByLetter.keySet());
    }

    /**
     * The node id a letter always maps to.
     * <p>
     * {@code a} is one above {@link NodeId#FIRST_NODE_ID}, and each later letter adds a gap that cycles through 1, 3
     * and 2, so {@code a} to {@code g} are {@code 1, 2, 5, 7, 8, 11, 13} and {@code z} is {@code 50}. The id depends on
     * the letter alone and strictly increases with it, so each letter has its own id, and a node keeps that id — and
     * its events keep their hashes — in every roster and every graph in the suite.
     * <p>
     * The ids are deliberately not the letters' positions. The consensus code holds two numbers for every node: its id,
     * and its index in the roster. If the ids here were {@code 0, 1, 2, 3} they would equal the indices of a roster
     * listed {@code a} to {@code d}, and any code that confused the two would pass every fixture in the suite. Starting
     * one above the first node id keeps every letter's id greater than its position, so the two can never coincide.
     * Adding gaps between the node ids ensures no code depends on contiguous ids. The gaps mirror
     * {@code RosterWrapperFactory.createRandomNodeIds}, which advances one to three per node, so the values stay small
     * enough to hold in your head while debugging.
     *
     * @param letter a node letter, {@code a} to {@code z}
     * @return the node id that letter always maps to, which no other letter maps to
     * @throws IllegalArgumentException if {@code letter} is not a single lower-case letter
     */
    @NonNull
    public static NodeId nodeIdFor(@NonNull final String letter) {
        if (letter.length() != 1 || letter.charAt(0) < 'a' || letter.charAt(0) > 'z') {
            throw new IllegalArgumentException(
                    "'%s' is not a node letter; use a single lower-case letter".formatted(letter));
        }
        final int position = letter.charAt(0) - 'a';
        long id = ID_ORIGIN;
        for (int i = 0; i < position; i++) {
            id += GAPS[i % GAPS.length];
        }
        return NodeId.of(id);
    }

    /**
     * Returns the roster wrapper representation of the contained nodes and weights.
     *
     * @return this roster as one the algorithm can run under
     */
    @NonNull
    public RosterWrapper toRosterWrapper() {
        final SequencedMap<NodeId, Long> weightsByNodeId = new LinkedHashMap<>();
        weightsByLetter.forEach((letter, weight) -> weightsByNodeId.put(nodeIdFor(letter), weight));
        return RosterWrapperFactory.createRosterWrapper(weightsByNodeId);
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;

/**
 * A named {@link RosterWrapper} whose node ids all come from {@link #nodeIdFor}, so
 * each node can be referred to by its letter. Each roster block in a {@code .graph} header is one of these.
 * <p>
 * A graph names each event's creator by the letter that starts the event's name, and {@link #nodeIdFor} gives every
 * letter one node id that depends on the letter alone. A roster built here therefore uses the same ids as the events
 * {@link GraphBuilder} builds, whatever weights it gives them.
 * <p>
 * Weights are declared in the {@code .graph} file itself rather than in a separate roster file, so they sit beside the
 * graph's comments and the test that uses it, which explain what the weights make consensus do.
 *
 * @param name   the roster's name, such as {@code initial} or {@code after}
 * @param roster the roster, with node ids from {@link #nodeIdFor(String)}
 */
public record NamedRoster(@NonNull String name, @NonNull RosterWrapper roster) {

    /** The node id of {@code a}, one above the first node id; see {@link #nodeIdFor} for why. */
    private static final long ID_ORIGIN = NodeId.FIRST_NODE_ID.id() + 1;

    /** The gaps between consecutive letters' ids, repeated in this order; see {@link #nodeIdFor} for why. */
    private static final long[] GAPS = {1, 3, 2};

    public NamedRoster {
        requireNonNull(name, "name must not be null");
        requireNonNull(roster, "roster must not be null");
        // Check if all the node ids in the given roster map to a parseable letter
        roster.rosterEntries().forEach(entry -> letterFor(entry.nodeId()));
    }

    /**
     * Creates a {@code NamedRoster} from a name and a letter-to-weight map. Such a map is how a roster is defined in a
     * flicker graph.
     *
     * @param name the roster's name, such as {@code initial} or {@code after}
     * @param weightsByLetter each node's letter mapped to its weight. The map's order becomes the order of the roster's entries, and of the block {@link GraphWriter} writes.
     * @return the constructed {@code NamedRoster}
     */
    @NonNull
    public static NamedRoster of(
            @NonNull final String name, @NonNull final SequencedMap<String, Long> weightsByLetter) {
        requireNonNull(name, "name must not be null");
        requireNonNull(weightsByLetter, "weights must not be null");

        final SequencedMap<NodeId, Long> weightsByNodeId = new LinkedHashMap<>();
        weightsByLetter.forEach((letter, weight) -> weightsByNodeId.put(nodeIdFor(letter), weight));
        return new NamedRoster(name, RosterWrapperFactory.createRosterWrapper(weightsByNodeId));
    }

    /**
     * Returns the letters of all nodes in this named roster.
     *
     * @return the node letters, in declaration order
     */
    @NonNull
    public List<String> letters() {
        return roster.rosterEntries().stream()
                .map(entry -> letterFor(entry.nodeId()))
                .toList();
    }

    /**
     * The node id a letter always maps to.
     * <p>
     * {@code a} is one above {@link NodeId#FIRST_NODE_ID}, and each later letter adds a gap that cycles through 1, 3
     * and 2, so {@code a} to {@code g} are {@code 1, 2, 5, 7, 8, 11, 13} and {@code z} is {@code 50}. The id depends on
     * the letter alone and strictly increases with it, so each letter has its own id, and a node keeps that id in every
     * roster and every graph in the suite. A graph's events therefore have the same creator ids, and the same hashes,
     * whichever roster it runs under.
     * <p>
     * The ids are deliberately not the letters' positions. The consensus code holds two numbers for every node: its id,
     * and its index in the roster. If the ids here were {@code 0, 1, 2, 3} they would equal the indices of a roster
     * listed {@code a} to {@code d}, and any code that confused the two would pass every fixture in the suite. Starting
     * one above the first node id makes every letter's id greater than its position in the alphabet. In a roster that
     * lists its letters in alphabetical order, a node's index is at most that position, so its id and index never
     * coincide. A roster listed in another order can still have a node whose id equals its index. The gaps between the
     * ids make code that assumes contiguous ids fail these fixtures. The gaps mirror
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
     * The letter a node id maps back to; the inverse of {@link #nodeIdFor}.
     *
     * @param nodeId a node id
     * @return the letter whose node id is {@code nodeId}
     * @throws IllegalArgumentException if no letter maps to {@code nodeId}
     */
    @NonNull
    public static String letterFor(@NonNull final NodeId nodeId) {
        requireNonNull(nodeId, "nodeId must not be null");
        long id = ID_ORIGIN;
        for (int position = 0; position < 26 && id <= nodeId.id(); position++) {
            if (id == nodeId.id()) {
                return Character.toString('a' + position);
            }
            id += GAPS[position % GAPS.length];
        }
        throw new IllegalArgumentException("node id %d is not the id of any node letter".formatted(nodeId.id()));
    }
}

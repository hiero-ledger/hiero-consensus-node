// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.RosterEntry;
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

    /**
     * Node ids advance irregularly from {@link NodeId#FIRST_NODE_ID} rather than being the letter's position.
     * <p>
     * A real roster assigns arbitrary ids, and consensus code holds two numbers for every node: its id, and its index
     * in the roster. If the ids here were {@code 0,1,2,3} they would equal the indices of a roster listed {@code a}
     * to {@code d}, and any code that confused the two would pass every fixture in the suite.
     * <p>
     * The gaps mirror {@code RosterWrapperFactory.createRandomNodeIds}, which advances one to three per node, so the
     * values stay small enough to hold in your head while debugging — {@code a,b,c,d} are {@code 1,2,5,7}. Starting
     * one above the first node id is what guarantees a letter's id is always greater than its position, so the two can
     * never be confused for one another. The value is a function of the letter alone and strictly increases with it,
     * so each letter has its own id, and a node keeps that id — and its events keep their hashes — in every roster
     * and every graph in the suite.
     */
    private static final long ID_ORIGIN = NodeId.FIRST_NODE_ID.id() + 1;

    public NamedRoster {
        requireNonNull(name, "name must not be null");
        weightsByLetter = Collections.unmodifiableSequencedMap(
                new LinkedHashMap<>(requireNonNull(weightsByLetter, "weights must not be null")));
    }

    /**
     * @return the node letters, in declaration order
     */
    @NonNull
    public List<String> letters() {
        return List.copyOf(weightsByLetter.keySet());
    }

    /**
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
        final int index = letter.charAt(0) - 'a';
        long id = ID_ORIGIN;
        for (int step = 1; step <= index; step++) {
            id += 1 + (2L * step + 1) % 3;
        }
        return NodeId.of(id);
    }

    /**
     * @return this block as a roster the algorithm can run under
     */
    @NonNull
    public RosterWrapper toRosterWrapper() {
        final List<RosterEntry> entries = weightsByLetter.entrySet().stream()
                .map(entry -> RosterEntry.newBuilder()
                        .nodeId(nodeIdFor(entry.getKey()).id())
                        .weight(entry.getValue())
                        .build())
                .toList();
        return RosterWrapperFactory.createRosterWrapper(entries);
    }
}

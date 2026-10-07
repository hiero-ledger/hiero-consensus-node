// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.EventSpec.event;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.EventSpec.genesis;

import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.EventSpec;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.GraphBuilder;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;
import org.hiero.consensus.test.fixtures.WeightGenerators;

/**
 * The SCN-001 graph: two same-round judges on one ancestry chain, with a non-judge strictly between them.
 *
 * <p>
 * Drawn bottom-up, as a hashgraph grows: genesis at the bottom, later events above. Each column is one creator, and a
 * vertical bar is a self-parent edge — so a bar passing a layer means that creator made no event at that layer.
 * Other-parent edges are listed separately rather than drawn, because there are too many to render legibly.
 *
 * <pre>
 *      a          b          c          d
 *
 *      |          |      c3 (J2)        |      layer 5 - c's first round-2 event, so a round-2 witness
 *      |          |          |          |
 *      |      b4 (mid)       |          |      layer 4 - round 2 under R_old, round 3 under R_new
 *      |          |          |          |
 *     a3         b3          |         d3      layer 3 - round 2, none of them witnesses
 *      |          |          |          |
 *     a2      b2 (J1)       c2         d2      layer 2 - a2/b2/d2 are round-2 witnesses;
 *      |          |          |          |                c2 is starved and stays in round 1
 *     a1         b1         c1         d1      layer 1 - round 1
 *      |          |          |          |
 *     a0         b0         c0         d0      layer 0 - round 1 witnesses
 * </pre>
 *
 * Other-parent edges:
 *
 * <pre>
 *   a1, b1, c1, d1   each take the other three layer-0 events
 *   a2, b2, d2       each take the other three layer-1 events   -> promoted to round 2
 *   c2               takes a1 only                              -> starved, stays in round 1
 *   a3               takes d2
 *   b3               takes none                                 -> b2 stays reachable only through b
 *   d3               takes a2
 *   b4 (mid)         takes a3 and d3
 *   c3 (J2)          takes b4
 * </pre>
 *
 * <b>The chain.</b> {@code b2 -> b3 -> b4(mid) -> c3}: three self-parent steps up b's column, then one other-parent
 * edge across to c. {@code b2} and {@code c3} are both round-2 judges and {@code b2} is therefore an ancestor of
 * {@code c3}; {@code mid} sits strictly between them and is not a judge, because {@code b2} is already b's round-2
 * witness. That is SCN-001's Setup precondition, exactly.
 *
 * <b>Why c is starved.</b> {@code J2} has to be a round-2 witness that <i>descends from</i> {@code mid}. A witness is
 * its creator's first event in a round, so c must reach round 2 for the first time above {@code mid}. {@code c2}
 * therefore takes only {@code a1}, leaving it seeing the round-1 witnesses through two creators and short of the
 * three-of-four supermajority. Note this is forced, not decorative: by INV-001 any descendant of a round-2 witness is
 * itself at least round 2, so c cannot both supply an intermediate below {@code mid} and stay unpromoted.
 *
 * <b>The flip.</b> Under {@code R_new}, {@code mid}'s recalculated round must rise from 2 to 3. Two conditions have to
 * hold at once, and they pull in opposite directions:
 * <ul>
 *     <li>{@code mid} strongly sees {@code a2} and {@code d2} through creators {A, B, D} — weight 3 of 4 under
 *         {@code R_old} and 9 of 10 under {@code R_new}, so the strongly-seeing relation survives both weightings.</li>
 *     <li>The <i>seen set</i> {@code {a2, d2}} carries weight 2 of 4 under {@code R_old} (below the 2.67 threshold, so
 *         {@code mid} stays in round 2) and 8 of 10 under {@code R_new} (above 6.67, so it is promoted).</li>
 * </ul>
 * {@code mid} never strongly sees {@code b2}: the only events between them are b's own, so that path is worth one
 * creator under either roster. That is what keeps the seen set small enough to be sub-supermajority under
 * {@code R_old}.
 *
 * <b>Layers 6 and up</b> are not drawn. They are full-connectivity closure — four more rounds of every node taking
 * every other node's latest event — whose only job is to drive round 2 to a decision, so that a snapshot can be taken
 * at it. They cannot disturb anything below, because an event's round depends only on its ancestors.
 * <p>
 * <b>The rosters live here too.</b> They are part of the fixture, not setup the test happens to perform: the whole
 * scenario is a statement about one graph read under two weightings, and the derivation above is unreadable without
 * them. {@link GraphBuilder} documents the fields pinned on every event and why.
 */
public final class Scn001Graph {

    /** The round the two same-round judges live in, and the round the snapshot is taken at. */
    public static final long ANCHOR_ROUND = 2;

    /** The earlier of the two same-round judges; an ancestor of {@link #MID}. */
    public static final String J1 = "b2";

    /** The non-judge strictly between the two judges. This is the event the roster change promotes. */
    public static final String MID = "b4";

    /** The later of the two same-round judges; a descendant of {@link #MID}. */
    public static final String J2 = "c3";

    /** Creator indices into the roster's node ids. */
    private static final int A = 0;
    private static final int B = 1;
    private static final int C = 2;
    private static final int D = 3;

    /** Fixed so the graph - and therefore every content hash in it - is reproducible. */
    private static final long SEED = 20260925L;

    /**
     * {@code R_new}'s weights by creator index. The pair {a, d} holds 8 of 10 here — a supermajority — where under the
     * balanced {@code R_old} it holds only 2 of 4, which is not. That single change is what promotes {@link #MID}.
     */
    private static final long[] NEW_WEIGHTS = {4, 1, 1, 4};

    private static final List<EventSpec> SPECS = List.of(
            // layer 0 - round 1 witnesses
            genesis("a0", A),
            genesis("b0", B),
            genesis("c0", C),
            genesis("d0", D),
            // layer 1 - round 1, fully connected
            event("a1", A, "a0", "b0", "c0", "d0"),
            event("b1", B, "b0", "a0", "c0", "d0"),
            event("c1", C, "c0", "a0", "b0", "d0"),
            event("d1", D, "d0", "a0", "b0", "c0"),
            // layer 2 - a2/b2/d2 are promoted and become round-2 witnesses; c2 is starved and stays in round 1
            event("a2", A, "a1", "b1", "c1", "d1"),
            event("b2", B, "b1", "a1", "c1", "d1"),
            event("d2", D, "d1", "a1", "b1", "c1"),
            event("c2", C, "c1", "a1"),
            // layer 3 - round 2, none of them witnesses. b3 takes no other-parent, which is what keeps b2 reachable
            // from mid through b alone and therefore never strongly seen.
            event("a3", A, "a2", "d2"),
            event("b3", B, "b2"),
            event("d3", D, "d2", "a2"),
            // layer 4 - the event the roster change promotes
            event("b4", B, "b3", "a3", "d3"),
            // layer 5 - c's first round-2 event, descending from mid
            event("c3", C, "c2", "b4"),
            // layers 6+ - closure, to drive round 2 to a decision
            event("a4", A, "a3", "b4", "c3", "d3"),
            event("b5", B, "b4", "a3", "c3", "d3"),
            event("c4", C, "c3", "a3", "b4", "d3"),
            event("d4", D, "d3", "a3", "b4", "c3"),
            event("a5", A, "a4", "b5", "c4", "d4"),
            event("b6", B, "b5", "a4", "c4", "d4"),
            event("c5", C, "c4", "a4", "b5", "d4"),
            event("d5", D, "d4", "a4", "b5", "c4"),
            event("a6", A, "a5", "b6", "c5", "d5"),
            event("b7", B, "b6", "a5", "c5", "d5"),
            event("c6", C, "c5", "a5", "b6", "d5"),
            event("d6", D, "d5", "a5", "b6", "c5"),
            event("a7", A, "a6", "b7", "c6", "d6"),
            event("b8", B, "b7", "a6", "c6", "d6"),
            event("c7", C, "c6", "a6", "b7", "d6"),
            event("d7", D, "d6", "a6", "b7", "c6"));

    private Scn001Graph() {}

    /** @return the roster round {@link #ANCHOR_ROUND} is decided under: four nodes of equal weight. */
    @NonNull
    public static RosterWrapper rosterOld() {
        return RosterWrapperFactory.randomRoster(new Random(SEED), 4, WeightGenerators.BALANCED);
    }

    /** @return the roster the replay runs under: the same nodes, reweighted so that {@link #MID} is promoted. */
    @NonNull
    public static RosterWrapper rosterNew() {
        final RosterWrapper old = rosterOld();
        final List<RosterEntry> entries = new ArrayList<>();
        for (int i = 0; i < old.size(); i++) {
            entries.add(old.rosterEntry(i)
                    .toPbj()
                    .copyBuilder()
                    .weight(NEW_WEIGHTS[i])
                    .build());
        }
        return RosterWrapperFactory.createRosterWrapper(entries);
    }

    /**
     * Build the graph.
     *
     * @param roster the roster whose node ids the events are created by
     * @return the events in topological order, each paired with its name
     */
    @NonNull
    public static List<NamedEvent> build(@NonNull final RosterWrapper roster) {
        return GraphBuilder.build(SPECS, new Random(SEED), roster);
    }
}

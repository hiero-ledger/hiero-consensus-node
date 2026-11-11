// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerGraph;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.GraphFiles;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.model.roster.RosterWrapper;

/**
 * The SCN-001 graph: two same-round judges on one ancestry chain, with a non-judge strictly between them.
 *
 * <p>
 * The shape is {@code scn-001.graph}, drawn as columns in a comment at its head. Here is why it has that shape.
 *
 * <p>
 * <b>The chain.</b> {@code b2 -> b3 -> b4(mid) -> c3}: three self-parent steps up b's column, then one other-parent
 * edge across to c. {@code b2} and {@code c3} are both round-2 judges and {@code b2} is therefore an ancestor of
 * {@code c3}; {@code mid} sits strictly between them and is not a judge, because {@code b2} is already b's round-2
 * witness. That is SCN-001's Setup precondition, exactly.
 *
 * <p>
 * <b>Why c is starved.</b> {@code J2} has to be a round-2 witness that <i>descends from</i> {@code mid}. A witness is
 * its creator's first event in a round, so c must reach round 2 for the first time above {@code mid}. {@code c2}
 * therefore takes only {@code a1}, leaving it seeing the round-1 witnesses through two creators and short of the
 * three-of-four supermajority. Note this is forced, not decorative: by INV-001 any descendant of a round-2 witness is
 * itself at least round 2, so c cannot both supply an intermediate below {@code mid} and stay unpromoted.
 *
 * <p>
 * <b>The flip.</b> Under {@code after}, {@code mid}'s recalculated round must rise from 2 to 3. Two conditions have to
 * hold at once, and they pull in opposite directions:
 * <ul>
 *     <li>{@code mid} strongly sees {@code a2} and {@code d2} through creators {A, B, D} — weight 3 of 4 under
 *         {@code initial} and 9 of 10 under {@code after}, so the strongly-seeing relation survives both weightings.</li>
 *     <li>The <i>seen set</i> {@code {a2, d2}} carries weight 2 of 4 under {@code initial} (below the 2.67 threshold, so
 *         {@code mid} stays in round 2) and 8 of 10 under {@code after} (above 6.67, so it is promoted).</li>
 * </ul>
 * {@code mid} never strongly sees {@code b2}: the only events between them are b's own, so that path is worth one
 * creator under either roster. That is what keeps the seen set small enough to be sub-supermajority under
 * {@code initial}.
 */
public final class Scn001Graph {

    /** The file the shape lives in: rows, both rosters, and the anchor round. */
    public static final String FILE = "scn-001.graph";

    private static final FlickerGraph GRAPH = GraphFiles.load(FILE);

    /** The round the two same-round judges live in, and the round the snapshot is taken at. */
    public static final long ANCHOR_ROUND = Objects.requireNonNull(GRAPH.anchorRound(), "anchor round");

    /** The earlier of the two same-round judges; an ancestor of {@link #MID}. */
    public static final String J1 = "b2";

    /** The non-judge strictly between the two judges. This is the event the roster change promotes. */
    public static final String MID = "b4";

    /** The later of the two same-round judges; a descendant of {@link #MID}. */
    public static final String J2 = "c3";

    private Scn001Graph() {}

    /** @return the parsed fixture, header and all */
    @NonNull
    public static FlickerGraph graph() {
        return GRAPH;
    }

    /**
     * @return the file's {@code initial} block: the four nodes at equal weight, which round {@link #ANCHOR_ROUND} is
     *     decided under
     */
    @NonNull
    public static RosterWrapper rosterOld() {
        return GRAPH.roster("initial").toRosterWrapper();
    }

    /** @return the file's {@code after} block: the same nodes, reweighted so that {@link #MID} is promoted */
    @NonNull
    public static RosterWrapper rosterNew() {
        return GRAPH.roster("after").toRosterWrapper();
    }

    /**
     * Build the graph. The events are the same under either roster, since every creator id comes from its letter.
     *
     * @return the events in creation order, each paired with its name
     */
    @NonNull
    public static List<NamedEvent> build() {
        return GRAPH.build();
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.EventSpec.event;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.EventSpec.genesis;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.EventSpec;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.GraphBuilder;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;
import org.hiero.consensus.test.fixtures.WeightGenerators;

/**
 * A hand-built graph over four equal-weight nodes, used by the Flicker harness tests.
 * <p>
 * Drawn bottom-up, as a hashgraph grows: genesis at the bottom, later events above. Each column is one creator, and a
 * vertical bar is a self-parent edge. Other-parent edges are listed below rather than drawn.
 *
 * <pre>
 *     a          b          c          d
 *
 *    a4         b4         c4         d4      layer 4 - round 3 witnesses
 *     |          |          |          |
 *    a3         b3         c3         d3      layer 3 - a3 is A's round-2 witness; b3/c3/d3 are round 2
 *     |          |          |          |                but not witnesses
 *    a2         b2         c2         d2      layer 2 - b2/c2/d2 are round-2 witnesses; a2 is starved
 *     |          |          |          |                and stays in round 1
 *    a1         b1         c1         d1      layer 1 - round 1, not witnesses
 *     |          |          |          |
 *    a0         b0         c0         d0      layer 0 - round 1 witnesses (genesis)
 * </pre>
 *
 * Other-parent edges: every event takes all three of the other nodes' events from the layer below, with one exception
 * — {@code a2} takes {@code b1} alone.
 * <p>
 * That exception is the point of the graph. It leaves only two distinct creators — A via {@code a1}, B via {@code b1}
 * — supplying intermediate events between {@code a2} and the round-1 witnesses, which is short of the three-of-four
 * supermajority needed for promotion, so {@code a2} stays in round 1.
 * <p>
 * The effect is that A's round-2 witness is {@code a3}, a layer above the other three rather than alongside them. A
 * graph that promotes all four nodes in the same layer only ever shows the harness four interchangeable promotions;
 * this one makes it catch a promotion that happens somewhere else in the graph, which demonstrates more of the
 * capability.
 * <p>
 * Note what the starvation does <i>not</i> do: it does not make "the first event promoted to round 2" a structural
 * fact. That is still feed order — feeding {@code c2} before {@code b2} would make {@code c2} first.
 * <p>
 * The graph is five layers deep so that round 1's fame is decided by the round-3 witnesses' counting votes at
 * {@code diff=2}, which is what gives the pinning test a decided round to compare. {@link GraphBuilder} documents the
 * fields pinned on every event and why.
 */
public final class LadderGraph {

    /** Creator indices into the roster's node ids. */
    private static final int A = 0;

    private static final int B = 1;
    private static final int C = 2;
    private static final int D = 3;

    /** Fixed so the graph - and therefore every content hash in it - is reproducible. */
    private static final long SEED = 20260925L;

    private static final List<EventSpec> SPECS = List.of(
            // layer 0 - round 1 witnesses
            genesis("a0", A),
            genesis("b0", B),
            genesis("c0", C),
            genesis("d0", D),
            // layer 1 - round 1, fully connected, no witnesses
            event("a1", A, "a0", "b0", "c0", "d0"),
            event("b1", B, "b0", "a0", "c0", "d0"),
            event("c1", C, "c0", "a0", "b0", "d0"),
            event("d1", D, "d0", "a0", "b0", "c0"),
            // layer 2 - a2 is starved and stays in round 1; the rest become round-2 witnesses
            event("a2", A, "a1", "b1"),
            event("b2", B, "b1", "a1", "c1", "d1"),
            event("c2", C, "c1", "a1", "b1", "d1"),
            event("d2", D, "d1", "a1", "b1", "c1"),
            // layer 3 - a3 is A's round-2 witness, a layer later than the others
            event("a3", A, "a2", "b2", "c2", "d2"),
            event("b3", B, "b2", "a2", "c2", "d2"),
            event("c3", C, "c2", "a2", "b2", "d2"),
            event("d3", D, "d2", "a2", "b2", "c2"),
            // layer 4 - round 3 witnesses; adding a4 is what decides round 1
            event("a4", A, "a3", "b3", "c3", "d3"),
            event("b4", B, "b3", "a3", "c3", "d3"),
            event("c4", C, "c3", "a3", "b3", "d3"),
            event("d4", D, "d3", "a3", "b3", "c3"));

    private LadderGraph() {}

    /** @return the roster this graph is derived against: four nodes of equal weight. */
    @NonNull
    public static RosterWrapper roster() {
        return RosterWrapperFactory.randomRoster(new Random(SEED), 4, WeightGenerators.BALANCED);
    }

    /**
     * Build the graph.
     *
     * @param roster the four-node roster whose node ids the events are created by
     * @return the events in topological order, each paired with its name
     */
    @NonNull
    public static List<NamedEvent> build(@NonNull final RosterWrapper roster) {
        return GraphBuilder.build(SPECS, new Random(SEED), roster);
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;

/**
 * A hand-built graph over four equal-weight nodes, used by the Flicker harness tests.
 *
 * <pre>
 *   A     B     C     D
 *
 *   a4    b4    c4    d4      layer 4 - round 3 witnesses
 *   a3    b3    c3    d3      layer 3 - a3 is round 2 (A's round-2 witness); b3/c3/d3 are round 2, not witnesses
 *   a2    b2    c2    d2      layer 2 - b2/c2/d2 are round 2 witnesses; a2 is deliberately starved and stays round 1
 *   a1    b1    c1    d1      layer 1 - round 1, not witnesses
 *   a0    b0    c0    d0      layer 0 - round 1 witnesses (genesis)
 * </pre>
 *
 * Every event takes its own creator's previous event as self-parent. Other-parents are all three of the other nodes'
 * events from the layer below, with one exception: {@code a2} takes only {@code b1}. That exception is the point of the
 * graph. It leaves only two distinct creators - A via {@code a1}, B via {@code b1} - supplying intermediate events
 * between {@code a2} and the round-1 witnesses, which is short of the three-of-four supermajority needed for
 * promotion, so {@code a2} stays in round 1.
 * <p>
 * The effect is that A's round-2 witness is {@code a3}, a layer above the other three rather than alongside them. A
 * graph that promotes all four nodes in the same layer only ever shows the harness four interchangeable promotions;
 * this one makes it catch a promotion that happens somewhere else in the graph, which demonstrates more of the
 * capability.
 * <p>
 * Note what the starvation does <i>not</i> do: it does not make "the first event promoted to round 2" a structural
 * fact. That is still feed order — feeding {@code c2} before {@code b2} would make {@code c2} first.
 * <p>
 * Three fields are pinned on every event rather than left to {@link TestingEventBuilder}'s defaults, because all three
 * are hashed content and hashes feed the final tie-break in consensus order:
 * <ul>
 *     <li><b>birth round</b> - the default is {@code max(parent birth rounds) + random(0..2)}, which would drift the
 *         ancient window between runs. Pinned to 1, so nothing in this graph ever goes ancient.</li>
 *     <li><b>time created</b> - the default advances 1-99ms from the self-parent only, so an other-parent from a lower
 *         layer can end up with a later timestamp than its child. {@code ConsensusLinker} silently refuses to link a
 *         parent whose {@code timeCreated} is at or after the child's, which would quietly change the graph. Pinned to
 *         one second per layer, staggered by a millisecond per creator.</li>
 *     <li><b>transactions</b> - the default is two random 4-byte app transactions. Pinned to none.</li>
 * </ul>
 * The {@code coin} field is still drawn from the supplied {@link Random} - {@code TestingEventBuilder} has no setter for
 * it - so a fixed seed is what makes this graph reproducible.
 * <p>
 * Each event is hashed as it is built, before it is used as anyone's parent. {@code TestingEventBuilder} installs a
 * random placeholder hash and takes a parent's descriptor from whatever hash the parent is carrying at the time, while
 * both harnesses overwrite that placeholder with the real content hash. Without hashing here, every child would
 * claim a parent hash that no longer resolves and the orphan buffer would hold the whole graph back.
 */
public final class LadderGraph {

    /** A name and the event it refers to. */
    public record NamedEvent(@NonNull String name, @NonNull PlatformEvent event) {}

    private static final EventHasher HASHER = new DefaultEventHasher();

    private static final Instant BASE_TIME = Instant.ofEpochSecond(1_700_000_000L);
    private static final String[] NODE_NAMES = {"a", "b", "c", "d"};

    /** Layer indices at which the graph is built. */
    private static final int LAYERS = 5;

    private LadderGraph() {}

    /**
     * Build the graph.
     *
     * @param random the source of randomness; supply a fixed seed for a reproducible graph
     * @param roster the four-node roster whose node ids the events are created by
     * @return the events in topological order, each paired with its name
     */
    @NonNull
    public static List<NamedEvent> build(@NonNull final Random random, @NonNull final RosterWrapper roster) {
        if (roster.size() != NODE_NAMES.length) {
            throw new IllegalArgumentException("LadderGraph needs a %d-node roster".formatted(NODE_NAMES.length));
        }
        final List<NodeId> nodeIds = roster.nodeIds();
        final Map<String, PlatformEvent> eventsByName = new LinkedHashMap<>();
        final List<NamedEvent> orderedNamedEvents = new ArrayList<>();

        for (int layer = 0; layer < LAYERS; layer++) {
            for (int nodeIndex = 0; nodeIndex < NODE_NAMES.length; nodeIndex++) {
                final String name = NODE_NAMES[nodeIndex] + layer;
                final TestingEventBuilder builder = new TestingEventBuilder(random)
                        .setCreatorId(nodeIds.get(nodeIndex))
                        .setBirthRound(1)
                        .setTransactionBytes(List.of())
                        .setTimeCreated(BASE_TIME.plusSeconds(layer).plusMillis(nodeIndex));

                if (layer > 0) {
                    builder.setSelfParent(eventsByName.get(NODE_NAMES[nodeIndex] + (layer - 1)));
                    builder.setOtherParents(otherParentsOf(name, layer, nodeIndex, eventsByName));
                }

                final PlatformEvent event = HASHER.hashEvent(builder.build());
                eventsByName.put(name, event);
                orderedNamedEvents.add(new NamedEvent(name, event));
            }
        }
        return List.copyOf(orderedNamedEvents);
    }

    /**
     * The other-parents for one event: every other node's event from the layer below, except for {@code a2}, which
     * takes {@code b1} alone so that it is starved of the promotion supermajority.
     */
    @NonNull
    private static List<PlatformEvent> otherParentsOf(
            @NonNull final String name,
            final int layer,
            final int node,
            @NonNull final Map<String, PlatformEvent> built) {

        if ("a2".equals(name)) {
            return List.of(built.get("b1"));
        }

        final List<PlatformEvent> otherParents = new ArrayList<>(NODE_NAMES.length - 1);
        for (int other = 0; other < NODE_NAMES.length; other++) {
            if (other != node) {
                otherParents.add(built.get(NODE_NAMES[other] + (layer - 1)));
            }
        }
        return otherParents;
    }
}

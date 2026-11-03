// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.EventSpec.event;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.EventSpec.genesis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.EventSpec;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.GraphBuilder;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The property the {@code .graph} format rests on: editing a graph perturbs exactly the events the edit structurally
 * reaches, and nothing else.
 * <p>
 * Every field the builder leaves to its {@link Random} is hashed content, so before {@code setCoin} existed a fixture's
 * hashes depended on the exact <i>sequence</i> of builder calls, not just on the seed. Inserting one row anywhere
 * shifted every later {@code coin} and therefore every later hash. Deriving {@code timeCreated} from list position had
 * the same effect for the same reason. A file format whose whole purpose is to be edited cannot sit on either, which
 * is why this is asserted rather than assumed.
 * <p>
 * <b>The useful statement is about the blast radius, not about nothing changing.</b> An inserted event that becomes a
 * parent necessarily changes its descendants' hashes — they gained an ancestor, and a parent's hash is hashed content.
 * That is a real structural edit and no derivation can or should hide it. What the derivation buys is that the changed
 * set stops there: {@code timeCreated} depends only on the creator's own self-parent chain, {@code nGen} only on
 * parents, and every other hashed field is a constant, so file position reaches nothing.
 * <p>
 * <b>Hash stability is not outcome stability.</b> Whitening is an XOR over a round's judge hashes, so if the edit
 * reaches a judge, the intra-round order of events that are <i>not</i> descendants can still move. This class bounds
 * what changed in the data; it does not claim consensus output is unaffected.
 */
class GraphBuilderStabilityTest {

    private static final long SEED = 20261013L;

    private static final int A = 0;
    private static final int B = 1;
    private static final int C = 2;
    private static final int D = 3;

    /** Two fully-connected layers over genesis. Nothing here is special; it only needs to be more than one layer. */
    private static final List<EventSpec> BASE = List.of(
            genesis("a0", A),
            genesis("b0", B),
            genesis("c0", C),
            genesis("d0", D),
            event("a1", A, "a0", "b0", "c0", "d0"),
            event("b1", B, "b0", "a0", "c0", "d0"),
            event("c1", C, "c0", "a0", "b0", "d0"),
            event("d1", D, "d0", "a0", "b0", "c0"),
            event("a2", A, "a1", "b1", "c1", "d1"),
            event("b2", B, "b1", "a1", "c1", "d1"),
            event("c2", C, "c1", "a1", "b1", "d1"),
            event("d2", D, "d1", "a1", "b1", "c1"));

    /**
     * The same graph with one inert event inserted in the middle of the list.
     * <p>
     * {@code a3} is deliberately nobody's parent, so it reaches no other event structurally. Any hash that moves must
     * therefore have moved because of where the row sits in the file, which is the thing being ruled out. This is the
     * degenerate case of the blast-radius property — an empty descendant set — and on its own it is a weak test,
     * because an event that is nobody's parent is also an event that cannot change what consensus does.
     */
    private static List<EventSpec> withInsertion() {
        final List<EventSpec> specs = new ArrayList<>(BASE);
        specs.add(9, event("a3", A, "a2")); // directly after a2, before b2
        return List.copyOf(specs);
    }

    @Test
    @DisplayName("inserting an event that reaches nothing leaves every hash unchanged")
    void insertingAnInertEventDisturbsNothing() {
        final RosterWrapper roster = roster();

        final Map<String, Hash> before = hashesByName(GraphBuilder.build(BASE, new Random(SEED), roster));
        final Map<String, Hash> after = hashesByName(GraphBuilder.build(withInsertion(), new Random(SEED), roster));

        final List<String> disturbed = before.keySet().stream()
                .filter(name -> !before.get(name).equals(after.get(name)))
                .toList();

        assertThat(disturbed)
                .as("events whose hash changed when an unrelated event was inserted above them")
                .isEmpty();
    }

    /**
     * The realistic edit, and the one the inert case does not cover: the inserted event becomes a parent.
     * <p>
     * {@code b1a} is spliced into B's chain between {@code b1} and {@code b2}, so {@code b2}'s self-parent changes.
     * {@code b2} is the whole descendant set, and it moves for two independent reasons — a new parent hash, and a
     * self-parent one step lower. Both are genuine. The assertion is that {@code a2}, {@code c2} and {@code d2} do
     * <i>not</i> move: they sit below the insertion point in the file and would have moved under a position-derived
     * timestamp, while structurally the edit never reaches them.
     */
    @Test
    @DisplayName("inserting an event that becomes a parent disturbs exactly its descendants")
    void insertingAParentDisturbsExactlyItsDescendants() {
        final RosterWrapper roster = roster();

        final List<EventSpec> edited = new ArrayList<>(BASE);
        edited.add(8, event("b1a", B, "b1")); // after d1, before b2 is defined
        edited.replaceAll(spec -> "b2".equals(spec.name()) ? event("b2", B, "b1a", "a1", "c1", "d1") : spec);

        final Map<String, Hash> before = hashesByName(GraphBuilder.build(BASE, new Random(SEED), roster));
        final Map<String, Hash> after = hashesByName(GraphBuilder.build(List.copyOf(edited), new Random(SEED), roster));

        final List<String> disturbed = before.keySet().stream()
                .filter(name -> !before.get(name).equals(after.get(name)))
                .toList();

        assertThat(disturbed)
                .as("only b2 descends from the inserted event, so only b2 may change")
                .containsExactly("b2");
    }

    @Test
    @DisplayName("the seed no longer determines any hash in a built graph")
    void hashesDoNotDependOnTheSeed() {
        final RosterWrapper roster = roster();

        final Map<String, Hash> one = hashesByName(GraphBuilder.build(BASE, new Random(SEED), roster));
        final Map<String, Hash> two = hashesByName(GraphBuilder.build(BASE, new Random(SEED + 1), roster));

        assertThat(two)
                .as("with every hashed field pinned, nothing the builder still draws from the Random reaches the hash")
                .isEqualTo(one);
    }

    private static RosterWrapper roster() {
        return RosterWrapperFactory.randomRoster(new Random(SEED), 4, WeightGenerators.BALANCED);
    }

    private static Map<String, Hash> hashesByName(final List<NamedEvent> graph) {
        final Map<String, Hash> hashes = new LinkedHashMap<>();
        graph.forEach(named -> hashes.put(named.name(), named.event().getHash()));
        return hashes;
    }
}

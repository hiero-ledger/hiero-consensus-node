// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;

import com.google.common.collect.Collections2;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Tipset Tests")
class TipsetTests {

    private static void validateTipset(final Tipset tipset, final Map<NodeId, Long> expectedTipGenerations) {
        for (final NodeId nodeId : expectedTipGenerations.keySet()) {
            assertThat(tipset.getTipGenerationForNode(nodeId)).isEqualTo(expectedTipGenerations.get(nodeId));
        }
    }

    @Test
    @DisplayName("Advancement Test")
    void advancementTest() {
        final Random random = getRandomPrintSeed();

        final int nodeCount = 100;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(nodeCount).build();

        final Tipset tipset = new Tipset(roster);
        assertThat(tipset.size()).isEqualTo(nodeCount);

        final Map<NodeId, Long> expected = new HashMap<>();

        for (int iteration = 0; iteration < 10; iteration++) {
            for (int creator = 0; creator < nodeCount; creator++) {
                final NodeId creatorId =
                        NodeId.of(roster.rosterEntries().get(creator).nodeId());
                final long generation = random.nextLong(1, 100);

                tipset.advance(creatorId, generation);
                expected.put(creatorId, Math.max(generation, expected.getOrDefault(creatorId, 0L)));
                validateTipset(tipset, expected);
            }
        }
    }

    @Test
    @DisplayName("Merge Test")
    void mergeTest() {
        final Random random = getRandomPrintSeed();

        final int nodeCount = 100;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(nodeCount).build();

        // Given:
        final Tipset emptyTipset = new Tipset(roster);
        final Tipset maxTipset = new Tipset(roster);
        final Tipset randomTipset = new Tipset(roster);
        final Tipset biggerTipset = new Tipset(roster);
        final Tipset smallerTipset = new Tipset(roster);

        for (var entry : roster.rosterEntries()) {
            maxTipset.advance(NodeId.of(entry.nodeId()), Long.MAX_VALUE);
            final long generation = random.nextLong(1, Long.MAX_VALUE - 1);
            biggerTipset.advance(NodeId.of(entry.nodeId()), generation + 1);
            randomTipset.advance(NodeId.of(entry.nodeId()), generation);
            smallerTipset.advance(NodeId.of(entry.nodeId()), generation - 1);
        }

        // verify that an empty tipset merged against any other the result is the other
        assertThat(emptyTipset.merge(List.of(emptyTipset))).isEqualTo(emptyTipset);
        assertThat(emptyTipset.merge(List.of(maxTipset))).isEqualTo(maxTipset);
        assertThat(emptyTipset.merge(List.of(randomTipset))).isEqualTo(randomTipset);

        // verify that a maxed-out tipset merged against any other is equals to itself
        assertThat(maxTipset.merge(List.of(maxTipset))).isEqualTo(maxTipset);
        assertThat(maxTipset.merge(List.of(emptyTipset))).isEqualTo(maxTipset);
        assertThat(maxTipset.merge(List.of(randomTipset))).isEqualTo(maxTipset);

        // verify that a random tipset merged against a) itself b) an empty tipset c) a smaller tipset, is equals to
        // itself
        assertThat(randomTipset.merge(List.of(randomTipset))).isEqualTo(randomTipset);
        assertThat(randomTipset.merge(List.of(emptyTipset))).isEqualTo(randomTipset);
        assertThat(randomTipset.merge(List.of(smallerTipset))).isEqualTo(randomTipset);

        // verify that a random tipset merged against a) a maxed-out b) a bigger tipset, is equals to the other
        assertThat(randomTipset.merge(List.of(maxTipset))).isEqualTo(maxTipset);
        assertThat(randomTipset.merge(List.of(biggerTipset))).isEqualTo(biggerTipset);

        // verify that a random tipset merged against a) a maxed-out b) a bigger tipset, is equals to the other
        assertThat(smallerTipset.merge(List.of(randomTipset))).isEqualTo(randomTipset);
        assertThat(biggerTipset.merge(List.of(randomTipset))).isEqualTo(biggerTipset);

        // verify that an empty tipset merged a list of tipsets, is equals to the biggest in the list
        assertThat(emptyTipset.merge(List.of(randomTipset, biggerTipset, smallerTipset)))
                .isEqualTo(biggerTipset);

        // verify that tipsets being merged to a list of tiptsets returns the biggest in the list regardless of
        // the position the tipset being merged to occupies in the list
        for (var listOfTipsets : Collections2.permutations(List.of(randomTipset, biggerTipset, smallerTipset))) {
            assertThat(emptyTipset.merge(listOfTipsets)).isEqualTo(biggerTipset);
        }

        for (var listOfTipsets :
                Collections2.permutations(List.of(randomTipset, maxTipset, biggerTipset, smallerTipset))) {
            assertThat(emptyTipset.merge(listOfTipsets)).isEqualTo(maxTipset);
        }

        for (var listOfTipsets : Collections2.permutations(List.of(emptyTipset, biggerTipset, randomTipset))) {
            assertThat(smallerTipset.merge(listOfTipsets)).isEqualTo(biggerTipset);
        }

        // verify that tipsets being merged to a list of tiptsets containing itself still produces the expected result
        // regardless of the position the tipset being merged to occupies in the list
        final List<Tipset> exaustiveList = List.of(emptyTipset, biggerTipset, randomTipset, smallerTipset);
        for (var selected : exaustiveList) {
            for (var listOfTipsets : Collections2.permutations(exaustiveList)) {
                assertThat(selected.merge(listOfTipsets)).isEqualTo(biggerTipset);
            }
        }
    }

    @Test
    @DisplayName("getAdvancementCount() Test")
    void getAdvancementCountTest() {
        final Random random = getRandomPrintSeed();

        final int nodeCount = 100;

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(nodeCount)
                .withWeightGenerator(WeightGenerators.BALANCED)
                .build();

        final NodeId selfId =
                NodeId.of(roster.rosterEntries().get(random.nextInt(nodeCount)).nodeId());

        final Tipset initialTipset = new Tipset(roster);
        for (long creator = 0; creator < nodeCount; creator++) {
            final NodeId creatorId =
                    NodeId.of(roster.rosterEntries().get((int) creator).nodeId());
            final long generation = random.nextLong(1, 100);
            initialTipset.advance(creatorId, generation);
        }

        // Merging the tipset with itself will result in a copy
        final Tipset comparisonTipset = new Tipset(roster).merge(List.of(initialTipset));
        assertThat(comparisonTipset.size()).isEqualTo(initialTipset.size());
        for (int creator = 0; creator < 100; creator++) {
            final NodeId creatorId =
                    NodeId.of(roster.rosterEntries().get(creator).nodeId());
            assertThat(comparisonTipset.getTipGenerationForNode(creatorId))
                    .isEqualTo(initialTipset.getTipGenerationForNode(creatorId));
        }

        // Cause the comparison tipset to advance in a random way
        for (int entryIndex = 0; entryIndex < 100; entryIndex++) {
            final long creator = random.nextLong(100);
            final NodeId creatorId =
                    NodeId.of(roster.rosterEntries().get((int) creator).nodeId());
            final long generation = random.nextLong(1, 100);

            comparisonTipset.advance(creatorId, generation);
        }

        long expectedAdvancementCount = 0;
        for (int i = 0; i < 100; i++) {
            final NodeId nodeId = NodeId.of(roster.rosterEntries().get(i).nodeId());
            if (nodeId.equals(selfId)) {
                // Self advancements are not counted
                continue;
            }
            if (initialTipset.getTipGenerationForNode(nodeId) < comparisonTipset.getTipGenerationForNode(nodeId)) {
                expectedAdvancementCount++;
            }
        }
        assertThat(initialTipset.getTipAdvancementWeight(selfId, comparisonTipset))
                .isEqualTo(TipsetAdvancementWeight.of(expectedAdvancementCount, 0));
    }

    @Test
    @DisplayName("Weighted getAdvancementCount() Test")
    void weightedGetAdvancementCountTest() {
        final Random random = getRandomPrintSeed();
        final int nodeCount = 100;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(nodeCount).build();

        final Map<NodeId, Long> weights = new HashMap<>();
        for (final RosterEntry address : roster.rosterEntries()) {
            weights.put(NodeId.of(address.nodeId()), address.weight());
        }

        final NodeId selfId =
                NodeId.of(roster.rosterEntries().get(random.nextInt(nodeCount)).nodeId());

        final Tipset initialTipset = new Tipset(roster);
        for (long creator = 0; creator < 100; creator++) {
            final NodeId creatorId =
                    NodeId.of(roster.rosterEntries().get((int) creator).nodeId());
            final long generation = random.nextLong(1, 100);
            initialTipset.advance(creatorId, generation);
        }

        // Merging the tipset with itself will result in a copy
        final Tipset comparisonTipset = new Tipset(roster).merge(List.of(initialTipset));
        assertThat(comparisonTipset.size()).isEqualTo(initialTipset.size());
        for (int creator = 0; creator < 100; creator++) {
            final NodeId creatorId =
                    NodeId.of(roster.rosterEntries().get(creator).nodeId());
            assertThat(comparisonTipset.getTipGenerationForNode(creatorId))
                    .isEqualTo(initialTipset.getTipGenerationForNode(creatorId));
        }

        // Cause the comparison tipset to advance in a random way
        for (final RosterEntry address : roster.rosterEntries()) {
            final long generation = random.nextLong(1, 100);
            comparisonTipset.advance(NodeId.of(address.nodeId()), generation);
        }

        long expectedAdvancementCount = 0;
        for (final RosterEntry address : roster.rosterEntries()) {
            final NodeId nodeId = NodeId.of(address.nodeId());
            if (nodeId.equals(selfId)) {
                // Self advancements are not counted
                continue;
            }
            if (initialTipset.getTipGenerationForNode(nodeId) < comparisonTipset.getTipGenerationForNode(nodeId)) {
                expectedAdvancementCount += weights.get(nodeId);
            }
        }

        assertThat(initialTipset.getTipAdvancementWeight(selfId, comparisonTipset))
                .isEqualTo(TipsetAdvancementWeight.of(expectedAdvancementCount, 0));
    }
}

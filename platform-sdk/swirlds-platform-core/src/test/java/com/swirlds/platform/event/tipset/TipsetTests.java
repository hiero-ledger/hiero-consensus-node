// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.tipset;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.platform.event.creation.tipset.Tipset;
import com.swirlds.platform.event.creation.tipset.TipsetAdvancementWeight;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.util.ArrayList;
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
            assertEquals(expectedTipGenerations.get(nodeId), tipset.getTipGenerationForNode(nodeId));
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
        assertEquals(nodeCount, tipset.size());

        final Map<NodeId, Long> expected = new HashMap<>();

        for (int iteration = 0; iteration < 10; iteration++) {
            for (int creator = 0; creator < 100; creator++) {
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

        for (int count = 0; count < 10; count++) {
            final List<Tipset> tipsets = new ArrayList<>();
            final Map<NodeId, Long> expected = new HashMap<>();

            for (int tipsetIndex = 0; tipsetIndex < 10; tipsetIndex++) {
                final Tipset tipset = new Tipset(roster);
                for (int creator = 0; creator < nodeCount; creator++) {
                    final NodeId creatorId =
                            NodeId.of(roster.rosterEntries().get(creator).nodeId());
                    final long generation = random.nextLong(1, 100);
                    tipset.advance(creatorId, generation);
                    expected.put(creatorId, Math.max(generation, expected.getOrDefault(creatorId, 0L)));
                }
                tipsets.add(tipset);
            }

            final Tipset merged = Tipset.merge(tipsets);
            validateTipset(merged, expected);
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
        final Tipset comparisonTipset = Tipset.merge(List.of(initialTipset));
        assertEquals(initialTipset.size(), comparisonTipset.size());
        for (int creator = 0; creator < 100; creator++) {
            final NodeId creatorId =
                    NodeId.of(roster.rosterEntries().get(creator).nodeId());
            assertEquals(
                    initialTipset.getTipGenerationForNode(creatorId),
                    comparisonTipset.getTipGenerationForNode(creatorId));
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
        assertEquals(
                TipsetAdvancementWeight.of(expectedAdvancementCount, 0),
                initialTipset.getTipAdvancementWeight(selfId, comparisonTipset));
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
        final Tipset comparisonTipset = Tipset.merge(List.of(initialTipset));
        assertEquals(initialTipset.size(), comparisonTipset.size());
        for (int creator = 0; creator < 100; creator++) {
            final NodeId creatorId =
                    NodeId.of(roster.rosterEntries().get(creator).nodeId());
            assertEquals(
                    initialTipset.getTipGenerationForNode(creatorId),
                    comparisonTipset.getTipGenerationForNode(creatorId));
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

        assertEquals(
                TipsetAdvancementWeight.of(expectedAdvancementCount, 0),
                initialTipset.getTipAdvancementWeight(selfId, comparisonTipset));
    }
}

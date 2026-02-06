// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("GeneratorEventGraphSource Tests")
class GeneratorEventGraphSourceTest {

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Default builder produces valid events")
    void defaultBuilderProducesValidEvents() {
        final GeneratorEventGraphSource generator =
                GeneratorEventGraphSourceBuilder.builder().build();

        final List<PlatformEvent> events = generator.nextEvents(100);

        boolean anyTransactions = false;
        assertEquals(100, events.size());
        for (final PlatformEvent event : events) {
            if (!event.getTransactions().isEmpty()) {
                anyTransactions = true;
            }
            assertNotNull(event.getHash(), "every event should be hashed");
            assertNotNull(event.getCreatorId(), "every event should have a creator");
            assertNotNull(event.getTimeCreated(), "every event should have a timestamp");
            assertTrue(event.getBirthRound() >= 1, "birth round should be at least 1");
        }
        assertTrue(anyTransactions, "at least some events should contain transactions");
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Custom seed produces deterministic events")
    void customSeedProducesDeterministicEvents() {
        final long seed = 42L;

        final GeneratorEventGraphSource gen1 =
                GeneratorEventGraphSourceBuilder.builder().seed(seed).build();
        final GeneratorEventGraphSource gen2 =
                GeneratorEventGraphSourceBuilder.builder().seed(seed).build();

        final List<PlatformEvent> events1 = gen1.nextEvents(200);
        final List<PlatformEvent> events2 = gen2.nextEvents(200);

        assertEquals(events1.size(), events2.size());
        for (int i = 0; i < events1.size(); i++) {
            assertEquals(events1.get(i), events2.get(i), "events at index " + i + " should be equal");
        }
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Different seeds produce different events")
    void differentSeedsProduceDifferentEvents() {
        final GeneratorEventGraphSource gen1 =
                GeneratorEventGraphSourceBuilder.builder().seed(1L).build();
        final GeneratorEventGraphSource gen2 =
                GeneratorEventGraphSourceBuilder.builder().seed(2L).build();

        final List<PlatformEvent> events1 = gen1.nextEvents(50);
        final List<PlatformEvent> events2 = gen2.nextEvents(50);

        boolean anyDifference = false;
        for (int i = 0; i < events1.size(); i++) {
            if (!events1.get(i).equals(events2.get(i))) {
                anyDifference = true;
                break;
            }
        }
        assertTrue(anyDifference, "different seeds should produce different event sequences");
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Custom numNodes creates roster of correct size")
    void customNumNodesCreatesCorrectRoster() {
        final int numNodes = 7;
        final GeneratorEventGraphSource generator =
                GeneratorEventGraphSourceBuilder.builder().numNodes(numNodes).build();

        assertEquals(numNodes, generator.getRoster().rosterEntries().size());
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("All nodes in roster produce events")
    void allNodesProduceEvents() {
        final int numNodes = 5;
        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .numNodes(numNodes)
                .seed(12345L)
                .build();

        // Generate enough events so every node should appear at least once
        final List<PlatformEvent> events = generator.nextEvents(500);

        final Set<NodeId> creators = new HashSet<>();
        for (final PlatformEvent event : events) {
            creators.add(event.getCreatorId());
        }

        final Roster roster = generator.getRoster();
        for (int i = 0; i < numNodes; i++) {
            final NodeId nodeId = RosterUtils.getNodeId(roster, i);
            assertTrue(creators.contains(nodeId), "node " + nodeId + " should have created at least one event");
        }
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("numNodes controls roster size")
    void numNodesControlsRosterSize() {
        for (final int size : new int[] {2, 3, 6, 10}) {
            final GeneratorEventGraphSource generator =
                    GeneratorEventGraphSourceBuilder.builder().numNodes(size).build();

            assertEquals(size, generator.getRoster().rosterEntries().size());
        }
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Custom roster is used")
    void customRosterIsUsed() {
        final Roster roster = RandomRosterBuilder.create(Randotron.create(0L))
                .withSize(3)
                .withRealKeysEnabled(false)
                .build();

        final GeneratorEventGraphSource generator =
                GeneratorEventGraphSourceBuilder.builder().roster(roster).build();

        assertEquals(roster, generator.getRoster());
        assertEquals(3, generator.getRoster().rosterEntries().size());

        final List<PlatformEvent> events = generator.nextEvents(50);
        assertEquals(50, events.size());
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("maxOtherParents limits number of other parents")
    void maxOtherParentsLimitsParents() {
        final int maxOtherParents = 2;
        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .numNodes(6)
                .maxOtherParents(maxOtherParents)
                .seed(99L)
                .build();

        final List<PlatformEvent> events = generator.nextEvents(300);

        for (final PlatformEvent event : events) {
            assertTrue(
                    event.getOtherParents().size() <= maxOtherParents,
                    "other parents count " + event.getOtherParents().size() + " should not exceed maxOtherParents "
                            + maxOtherParents);
        }
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("maxOtherParents of 0 produces events with no other parents")
    void zeroMaxOtherParentsProducesNoOtherParents() {
        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .numNodes(4)
                .maxOtherParents(0)
                .seed(7L)
                .build();

        final List<PlatformEvent> events = generator.nextEvents(100);

        for (final PlatformEvent event : events) {
            assertTrue(event.getOtherParents().isEmpty(), "events should have no other parents");
        }
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Birth rounds advance monotonically")
    void birthRoundsAdvanceMonotonically() {
        final GeneratorEventGraphSource generator =
                GeneratorEventGraphSourceBuilder.builder().seed(0L).build();

        final List<PlatformEvent> events = generator.nextEvents(500);

        long previousBirthRound = events.getFirst().getBirthRound();
        for (final PlatformEvent event : events) {
            assertTrue(
                    event.getBirthRound() >= previousBirthRound,
                    "birth round should not decrease: was " + previousBirthRound + " then " + event.getBirthRound());
            previousBirthRound = event.getBirthRound();
        }

        // Verify birth round actually advances beyond 1
        final long lastBirthRound = events.getLast().getBirthRound();
        assertTrue(lastBirthRound > 1, "birth round should advance beyond 1 after 500 events");
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Event timestamps are non-decreasing")
    void timestampsAreNonDecreasing() {
        final GeneratorEventGraphSource generator =
                GeneratorEventGraphSourceBuilder.builder().seed(0L).build();

        final List<PlatformEvent> events = generator.nextEvents(200);

        for (int i = 1; i < events.size(); i++) {
            assertFalse(
                    events.get(i).getTimeCreated().isBefore(events.get(i - 1).getTimeCreated()),
                    "timestamps should not decrease");
        }
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Self-parent references valid prior event from same creator")
    void selfParentIsFromSameCreator() {
        final GeneratorEventGraphSource generator =
                GeneratorEventGraphSourceBuilder.builder().seed(0L).build();

        final List<PlatformEvent> events = generator.nextEvents(200);

        for (final PlatformEvent event : events) {
            final EventDescriptorWrapper selfParent = event.getSelfParent();
            if (selfParent != null) {
                assertEquals(
                        event.getCreatorId(),
                        selfParent.creator(),
                        "self-parent's creator should match the event's creator");
            }
        }
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Other parents reference different creators than the event creator")
    void otherParentsFromDifferentCreators() {
        final GeneratorEventGraphSource generator =
                GeneratorEventGraphSourceBuilder.builder().numNodes(4).seed(0L).build();

        final List<PlatformEvent> events = generator.nextEvents(200);

        for (final PlatformEvent event : events) {
            for (final EventDescriptorWrapper otherParent : event.getOtherParents()) {
                // Other parents should be created by a node different from the event creator node
                assertNotEquals(
                        event.getCreatorId(), otherParent.creator(), "other parent should be from a different creator");
            }
        }
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Cannot set roster when numNodes is already set")
    void cannotSetRosterWhenNumNodesSet() {
        final Roster roster = RandomRosterBuilder.create(Randotron.create(0L))
                .withSize(3)
                .withRealKeysEnabled(false)
                .build();

        final GeneratorEventGraphSourceBuilder builder =
                GeneratorEventGraphSourceBuilder.builder().numNodes(4);

        assertThrows(IllegalStateException.class, () -> builder.roster(roster));
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Cannot set numNodes when roster is already set")
    void cannotSetNumNodesWhenRosterSet() {
        final Roster roster = RandomRosterBuilder.create(Randotron.create(0L))
                .withSize(3)
                .withRealKeysEnabled(false)
                .build();

        final GeneratorEventGraphSourceBuilder builder =
                GeneratorEventGraphSourceBuilder.builder().roster(roster);

        assertThrows(IllegalStateException.class, () -> builder.numNodes(5));
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Cannot use realSignatures with a supplied roster")
    void cannotUseRealSignaturesWithSuppliedRoster() {
        final Roster roster = RandomRosterBuilder.create(Randotron.create(0L))
                .withSize(3)
                .withRealKeysEnabled(false)
                .build();

        final GeneratorEventGraphSourceBuilder builder =
                GeneratorEventGraphSourceBuilder.builder().roster(roster);

        assertThrows(IllegalStateException.class, () -> builder.realSignatures(true));
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Single node network generates valid events")
    void singleNodeNetworkGeneratesValidEvents() {
        final GeneratorEventGraphSource generator =
                GeneratorEventGraphSourceBuilder.builder().numNodes(1).seed(0L).build();

        final List<PlatformEvent> events = generator.nextEvents(50);

        assertEquals(50, events.size());
        final NodeId expectedCreator = RosterUtils.getNodeId(generator.getRoster(), 0);

        for (final PlatformEvent event : events) {
            assertEquals(expectedCreator, event.getCreatorId(), "all events should be from the single node");
            assertTrue(event.getOtherParents().isEmpty(), "single-node network should have no other parents");
        }
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Events can have transactions")
    void eventsCanHaveTransactions() {
        final GeneratorEventGraphSource generator =
                GeneratorEventGraphSourceBuilder.builder().seed(0L).build();

        final List<PlatformEvent> events = generator.nextEvents(200);

        boolean anyTransactions = false;
        for (final PlatformEvent event : events) {
            if (!event.getTransactions().isEmpty()) {
                anyTransactions = true;
                break;
            }
        }
        assertTrue(anyTransactions, "at least some events should contain transactions");
    }
}

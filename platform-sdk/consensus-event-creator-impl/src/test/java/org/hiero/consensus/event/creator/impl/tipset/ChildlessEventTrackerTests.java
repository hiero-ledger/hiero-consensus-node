// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("ChildlessEventTracker Tests")
class ChildlessEventTrackerTests {

    @Test
    @DisplayName("Newest events by creator are tracked")
    void testNewestEventsByCreatorAreKept() {
        final Random random = getRandomPrintSeed();
        final int numNodes = random.nextInt(10, 100);

        final ChildlessEventTracker tracker = new ChildlessEventTracker();

        // Add some events with no parents
        loadTrackerWithInitialEvents(random, tracker, numNodes);

        // Increase generation. Each creator will create a new event with a higher
        // non-deterministic generation and unknown parents. Only the new events should
        // be tracked because they have a higher nGen.
        final List<PlatformEvent> batch2 = new ArrayList<>();
        for (int nodeId = 0; nodeId < numNodes; nodeId++) {
            final NodeId nonExistentParentId1 = NodeId.of(nodeId + 100);
            final PlatformEvent nonExistentParent1 = new TestingEventBuilder(random)
                    .setCreatorId(nonExistentParentId1)
                    .setNGen(0)
                    .build();

            final NodeId nonExistentParentId2 = NodeId.of(nodeId + 110);
            final PlatformEvent nonExistentParent2 = new TestingEventBuilder(random)
                    .setCreatorId(nonExistentParentId2)
                    .setNGen(0)
                    .build();

            final PlatformEvent event = new TestingEventBuilder(random)
                    .setCreatorId(NodeId.of(nodeId))
                    .setSelfParent(nonExistentParent1)
                    .setOtherParent(nonExistentParent2)
                    .setNGen(1)
                    .build();

            tracker.addEvent(event);
            assertThat(tracker.getChildlessEvents()).contains(event);
            assertThat(getChildlessEvent(tracker, event.getDescriptor())).isEqualTo(event);
            batch2.add(event);
        }

        assertThat(tracker.getChildlessEvents().size()).isEqualTo(batch2.size());
        assertThat(tracker.getChildlessEvents())
                .withFailMessage("Only the new events with higher generations should be tracked")
                .containsAll(batch2);
    }

    @Test
    @DisplayName("Older events by creator are ignored")
    void testOlderEventsByCreatorAreIgnored() {
        final Random random = getRandomPrintSeed();
        final int numNodes = random.nextInt(10, 100);

        final ChildlessEventTracker tracker = new ChildlessEventTracker();

        // Add some events with no parents
        loadTrackerWithInitialEvents(random, tracker, numNodes);

        // Add some generation 1 events to the tracker
        for (int nodeId = 0; nodeId < numNodes; nodeId++) {
            final NodeId parent1 = NodeId.of(nodeId);
            final PlatformEvent parentEvent1 =
                    new TestingEventBuilder(random).setCreatorId(parent1).build();

            final NodeId parent2 = NodeId.of(nodeId);
            final PlatformEvent parentEvent2 =
                    new TestingEventBuilder(random).setCreatorId(parent2).build();

            final PlatformEvent event = new TestingEventBuilder(random)
                    .setCreatorId(NodeId.of(nodeId))
                    .setSelfParent(parentEvent1)
                    .setOtherParent(parentEvent2)
                    .setNGen(1)
                    .build();
            tracker.addEvent(event);
            assertThat(tracker.getChildlessEvents()).contains(event);
        }

        final Collection<PlatformEvent> childlessEvents = new ArrayList<>(tracker.getChildlessEvents());

        // Create events with a lower generation for all nodes. Each creator will create a new event,
        // with a lower non-deterministic generation. None of these events should
        // be tracked because they have a lower nGen.
        for (int nodeId = 0; nodeId < numNodes; nodeId++) {
            final NodeId parent1 = NodeId.of(nodeId);
            final PlatformEvent parentEvent1 =
                    new TestingEventBuilder(random).setCreatorId(parent1).build();

            final NodeId parent2 = NodeId.of(nodeId);
            final PlatformEvent parentEvent2 =
                    new TestingEventBuilder(random).setCreatorId(parent2).build();

            final PlatformEvent event = new TestingEventBuilder(random)
                    .setCreatorId(NodeId.of(nodeId))
                    .setSelfParent(parentEvent1)
                    .setOtherParent(parentEvent2)
                    .setNGen(0)
                    .build();

            tracker.addEvent(event);
            assertThat(tracker.getChildlessEvents()).doesNotContain(event);
            assertThat(getChildlessEvent(tracker, event.getDescriptor())).isNotEqualTo(event);
        }

        // Verify that the original events are unmodified in the tracker
        assertThat(tracker.getChildlessEvents())
                .withFailMessage("Tracked events should not have changed after adding older events")
                .containsAll(childlessEvents);
        assertThat(tracker.getChildlessEvents().size()).isEqualTo(childlessEvents.size());
    }

    @Test
    @DisplayName("Parents of new self events should not be tracked")
    void testSelfEventParentsAreRemoved() {
        final Random random = getRandomPrintSeed();
        final int numNodes = random.nextInt(10, 100);
        final NodeId selfId = NodeId.of(random.nextLong(numNodes));

        final ChildlessEventTracker tracker = new ChildlessEventTracker();

        // Add some events with no parents
        loadTrackerWithInitialEvents(random, tracker, numNodes);

        final List<PlatformEvent> previousChildlessEvents = new ArrayList<>(tracker.getChildlessEvents());

        final PlatformEvent selfParent = tracker.getChildlessEvents().stream()
                .filter(event -> event.getCreatorId().equals(selfId))
                .findFirst()
                .orElseThrow();
        final PlatformEvent otherParent = tracker.getChildlessEvents().stream()
                .filter(event -> !event.getCreatorId().equals(selfId))
                .findFirst()
                .orElseThrow();

        // Register the parents of a new self event with existing childless events as parents
        tracker.registerSelfEventParents(List.of(selfParent.getDescriptor(), otherParent.getDescriptor()));

        // Verify that the parents are no longer tracked as childless
        assertThat(tracker.getChildlessEvents()).doesNotContain(selfParent);
        assertThat(tracker.getChildlessEvents()).doesNotContain(otherParent);

        // Verify that only the two parents were removed. All other events should remain.
        previousChildlessEvents.remove(selfParent);
        previousChildlessEvents.remove(otherParent);
        assertThat(tracker.getChildlessEvents()).containsAll(previousChildlessEvents);
        assertThat(tracker.getChildlessEvents().size()).isEqualTo(previousChildlessEvents.size());
    }

    @Test
    @DisplayName("Non-existent parents of new self events should not cause problems")
    void testSelfEventParentsNotDoNotExist() {
        final Random random = getRandomPrintSeed();
        final int numNodes = random.nextInt(10, 100);

        final ChildlessEventTracker tracker = new ChildlessEventTracker();

        // Add some events with no parents
        loadTrackerWithInitialEvents(random, tracker, numNodes);

        final Collection<PlatformEvent> previousChildlessEvents = tracker.getChildlessEvents();

        final PlatformEvent parent1 = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(numNodes + 1))
                .setNGen(100)
                .build();
        final PlatformEvent parent2 = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(numNodes + 2))
                .setNGen(100)
                .build();

        // Register the parents of a new self event with existing childless events as parents
        tracker.registerSelfEventParents(List.of(parent1.getDescriptor(), parent2.getDescriptor()));

        // Verify that only the two parents were removed. All other events should remain.
        assertThat(tracker.getChildlessEvents()).containsAll(previousChildlessEvents);
        assertThat(tracker.getChildlessEvents().size()).isEqualTo(previousChildlessEvents.size());
    }

    private PlatformEvent getChildlessEvent(
            @NonNull final ChildlessEventTracker tracker, @NonNull final EventDescriptorWrapper descriptor) {
        return tracker.getChildlessEvents().stream()
                .filter(e -> e.getDescriptor().equals(descriptor))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("Events with children are not tracked")
    void testEventsWithChildrenAreNotTracked() {
        final Random random = getRandomPrintSeed();
        final ChildlessEventTracker tracker = new ChildlessEventTracker();

        // Add 3 events, created by different nodes with no parents
        final List<PlatformEvent> initialEvents = loadTrackerWithInitialEvents(random, tracker, 3);

        // Add a newer event that has two of the existing events as parents
        final PlatformEvent eventWithTwoParents = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(0))
                .setSelfParent(initialEvents.get(0))
                .setOtherParent(initialEvents.get(1))
                .setNGen(1)
                .build();
        tracker.addEvent(eventWithTwoParents);

        assertThat(tracker.getChildlessEvents())
                .withFailMessage("Tracker should contain the newly added event")
                .contains(eventWithTwoParents);
        assertThat(tracker.getChildlessEvents())
                .withFailMessage("Tracker should contain the single initial event that was not used as a parent")
                .contains(initialEvents.get(2));
        assertThat(tracker.getChildlessEvents())
                .withFailMessage("There should now be two childless events")
                .hasSize(2);

        // Create a new event who uses the final initial event as both parents
        final PlatformEvent eventWithSameParents = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(2))
                .setSelfParent(initialEvents.get(2))
                .setOtherParent(initialEvents.get(2))
                .setNGen(1)
                .build();
        tracker.addEvent(eventWithSameParents);

        assertThat(tracker.getChildlessEvents())
                .withFailMessage("Tracker should contain the newly added event")
                .contains(eventWithSameParents);
        assertThat(tracker.getChildlessEvents())
                .withFailMessage("Tracker should contain the single initial event that was not used as a parent")
                .contains(eventWithTwoParents);
        assertThat(tracker.getChildlessEvents())
                .withFailMessage("There should still be two childless events")
                .hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(AncientMode.class)
    @DisplayName("Ancient events are removed when they become ancient")
    void testAncientEventsArePruned(final AncientMode ancientMode) {
        final Random random = getRandomPrintSeed();
        final int numNodes = random.nextInt(10, 100);

        final ChildlessEventTracker tracker = new ChildlessEventTracker();

        // Add some events with no parents. Make each event have a different generation and birth round
        // so it is easy to track which should be pruned later.
        final long ancientThresholdOffset = 100;
        final Map<Long, PlatformEvent> eventsByCreator = new HashMap<>();
        for (long nodeId = 0; nodeId < numNodes; nodeId++) {

            final NodeId nonExistentParentId1 = NodeId.of(nodeId + 100);
            final PlatformEvent nonExistentParent1 = new TestingEventBuilder(random)
                    .setCreatorId(nonExistentParentId1)
                    .setNGen(0)
                    .build();
            final NodeId nonExistentParentId2 = NodeId.of(nodeId + 101);
            final PlatformEvent nonExistentParent2 = new TestingEventBuilder(random)
                    .setCreatorId(nonExistentParentId2)
                    .setNGen(0)
                    .build();

            final long parentGeneration = nodeId + ancientThresholdOffset - 1;
            final long birthRound = nodeId + ancientThresholdOffset;
            final PlatformEvent event = new TestingEventBuilder(random)
                    .setCreatorId(NodeId.of(nodeId))
                    .setBirthRound(birthRound)
                    .setSelfParent(nonExistentParent1)
                    .setOtherParent(nonExistentParent2)
                    .overrideSelfParentGeneration(parentGeneration)
                    .overrideOtherParentGeneration(parentGeneration)
                    .setNGen(1)
                    .build();
            tracker.addEvent(event);
            assertThat(tracker.getChildlessEvents()).contains(event);
            assertThat(getChildlessEvent(tracker, event.getDescriptor())).isEqualTo(event);
            eventsByCreator.put(nodeId, event);
        }

        assertThat(tracker.getChildlessEvents().size()).isEqualTo(eventsByCreator.size());
        assertThat(tracker.getChildlessEvents())
                .withFailMessage("Tracker should contain the most recent events from each node")
                .containsAll(eventsByCreator.values());

        // Increment the ancient threshold by 1 each iteration. All events in the tracker have
        // a unique, monotonically increasing ancient threshold value (generation/birth round),
        // so one event should be pruned in each event window update.
        for (long nodeId = 0; nodeId < numNodes; nodeId++) {
            final long ancientThreshold = nodeId + ancientThresholdOffset + 1;
            tracker.pruneOldEvents(EventWindowBuilder.builder()
                    .setAncientMode(ancientMode)
                    .setAncientThreshold(ancientThreshold)
                    .build());
            final PlatformEvent event = eventsByCreator.get(nodeId);
            assertThat(tracker.getChildlessEvents())
                    .withFailMessage("Tracker should have pruned event {}", event.getDescriptor())
                    .doesNotContain(event);
            assertThat(getChildlessEvent(tracker, event.getDescriptor()))
                    .withFailMessage("Tracker should have pruned event {}", event.getDescriptor())
                    .isNull();
            assertThat(tracker.getChildlessEvents())
                    .withFailMessage("A single event should be pruned each time the event window is incremented")
                    .hasSize((int) (numNodes - nodeId - 1));
        }
    }

    @Test
    @DisplayName("Only the highest generation events from a branch are tracked")
    void testHighestGenBranchedEventsAreTracked() {
        final Random random = getRandomPrintSeed();
        final ChildlessEventTracker tracker = new ChildlessEventTracker();
        final NodeId nodeId = NodeId.of(0);

        final PlatformEvent e0 =
                new TestingEventBuilder(random).setCreatorId(nodeId).setNGen(0).build();
        final PlatformEvent e1 =
                new TestingEventBuilder(random).setCreatorId(nodeId).setNGen(1).build();
        final PlatformEvent e2 =
                new TestingEventBuilder(random).setCreatorId(nodeId).setNGen(2).build();

        tracker.addEvent(e0);
        tracker.addEvent(e1);
        tracker.addEvent(e2);

        assertThat(tracker.getChildlessEvents()).hasSize(1);
        assertThat(tracker.getChildlessEvents().iterator().next()).isEqualTo(e2);

        final PlatformEvent e3 = new TestingEventBuilder(random)
                .setCreatorId(nodeId)
                .setSelfParent(e2)
                .setNGen(3)
                .build();
        final PlatformEvent e3Branch = new TestingEventBuilder(random)
                .setCreatorId(nodeId)
                .setSelfParent(e2)
                .setNGen(3)
                .build();

        // Branch with the same generation, existing event should not be discarded.
        tracker.addEvent(e3);
        tracker.addEvent(e3Branch);

        assertThat(tracker.getChildlessEvents()).hasSize(1);
        assertThat(tracker.getChildlessEvents().iterator().next()).isEqualTo(e3);

        // Branch with a lower generation, existing event should not be discarded.
        final PlatformEvent e2Branch = new TestingEventBuilder(random)
                .setCreatorId(nodeId)
                .setSelfParent(e1)
                .setNGen(2)
                .build();
        tracker.addEvent(e2Branch);

        assertThat(tracker.getChildlessEvents()).hasSize(1);
        assertThat(tracker.getChildlessEvents().iterator().next()).isEqualTo(e3);

        // Branch with a higher generation, existing event should be discarded.
        final PlatformEvent e99Branch =
                new TestingEventBuilder(random).setCreatorId(nodeId).setNGen(99).build();
        tracker.addEvent(e99Branch);

        assertThat(tracker.getChildlessEvents()).hasSize(1);
        assertThat(tracker.getChildlessEvents().iterator().next()).isEqualTo(e99Branch);
    }

    /**
     * Creates an initial set of events (without parents), one per node in the network, and loads them into the tracker.
     * Once this method returns, the tracker is tracking all the initial events.
     *
     * @param tracker  the tracker to add the events to
     * @param numNodes the number of nodes in the network
     * @return the list of initial events the tracker is now tracking
     */
    private List<PlatformEvent> loadTrackerWithInitialEvents(
            final Random random, final ChildlessEventTracker tracker, final int numNodes) {
        final List<PlatformEvent> initialEvents = createInitialEvents(random, numNodes);
        initialEvents.forEach(event -> {
            tracker.addEvent(event);
            assertThat(tracker.getChildlessEvents()).contains(event);
            assertThat(getChildlessEvent(tracker, event.getDescriptor())).isEqualTo(event);
        });
        assertThat(tracker.getChildlessEvents().size()).isEqualTo(initialEvents.size());
        assertThat(tracker.getChildlessEvents())
                .withFailMessage("Tracker should contain the initial event from each node")
                .containsAll(initialEvents);
        return initialEvents;
    }

    /**
     * Create a single initial event (with no parents) for each node in the network.
     *
     * @param numNodes the number of nodes in the network.
     * @return the initial list of events
     */
    private List<PlatformEvent> createInitialEvents(final Random random, final int numNodes) {
        final List<PlatformEvent> initialEvents = new ArrayList<>(numNodes);
        for (long nodeId = 0; nodeId < numNodes; nodeId++) {
            final PlatformEvent event = new TestingEventBuilder(random)
                    .setCreatorId(NodeId.of(nodeId))
                    .setNGen(EventConstants.FIRST_GENERATION)
                    .build();
            initialEvents.add(event);
        }
        return initialEvents;
    }
}

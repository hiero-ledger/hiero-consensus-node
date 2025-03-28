// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.tipset;

import static com.swirlds.common.test.fixtures.crypto.CryptoRandomUtils.randomHash;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.platform.event.EventDescriptor;
import com.swirlds.platform.event.creation.tipset.ChildlessEventTracker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChildlessEventTracker Tests")
class ChildlessEventTrackerTests {

    /**
     * The childless event tracker doesn't track branches in the hashgraph, it only stores whatever branch has produced
     * the event with the highest generation. Return a set that contains only the event from the latest branch from each
     * creator.
     */
    @NonNull
    private Set<EventDescriptorWrapper> removeBranches(@NonNull List<EventDescriptorWrapper> events) {
        final Map<NodeId, EventDescriptorWrapper> uniqueEvents = new HashMap<>();

        for (final EventDescriptorWrapper event : events) {
            final EventDescriptorWrapper existingEvent = uniqueEvents.get(event.creator());
            if (existingEvent == null
                    || existingEvent.eventDescriptor().generation()
                            < event.eventDescriptor().generation()) {
                uniqueEvents.put(event.creator(), event);
            }
        }

        return new HashSet<>(uniqueEvents.values());
    }

    @Test
    @DisplayName("Basic Behavior Test")
    void basicBehaviorTest() {
        final Random random = getRandomPrintSeed();

        final ChildlessEventTracker tracker = new ChildlessEventTracker();

        // Adding some event with no parents
        final List<EventDescriptorWrapper> batch1 = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final EventDescriptorWrapper descriptor = newEventDescriptor(randomHash(random), NodeId.of(i), 0);
            tracker.addEvent(descriptor, List.of());
            batch1.add(descriptor);
        }

        assertEquals(new HashSet<>(batch1), new HashSet<>(tracker.getChildlessEvents()));

        // Increase generation. All new events will either have parents with odd node
        // IDs or parents that haven't been registered yet. When this is completed,
        // the new events should be tracked, and all registered parents should not be.

        final List<EventDescriptorWrapper> batch2 = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final NodeId nonExistentParentId = NodeId.of(i + 100);
            final EventDescriptorWrapper nonExistentParent =
                    newEventDescriptor(randomHash(random), nonExistentParentId, 0);
            final int oddParentId = (i * 2 + 1) % 10;
            final EventDescriptorWrapper oddParent = batch1.get(oddParentId);

            final EventDescriptorWrapper descriptor = newEventDescriptor(randomHash(), NodeId.of(i), 1);
            tracker.addEvent(descriptor, List.of(nonExistentParent, oddParent));
            batch2.add(descriptor);
        }

        final List<EventDescriptorWrapper> expectedEvents = new ArrayList<>(batch2);
        for (final EventDescriptorWrapper descriptor : batch1) {
            if (descriptor.eventDescriptor().creatorNodeId() % 2 == 0) {
                expectedEvents.add(descriptor);
            }
        }

        assertEquals(removeBranches(expectedEvents), new HashSet<>(tracker.getChildlessEvents()));

        // Increase the minimum generation non-ancient to 1, all events from batch1 should be removed
        // FUTURE WORK: Change the test to use round instead of generation for ancient.
        tracker.pruneOldEvents(
                new EventWindow(1, 1, 0 /* ignored in this context */, AncientMode.GENERATION_THRESHOLD));

        assertEquals(removeBranches(batch2), new HashSet<>(tracker.getChildlessEvents()));
    }

    @Test
    @DisplayName("Branching Test")
    void branchingTest() {
        final Random random = getRandomPrintSeed();

        final ChildlessEventTracker tracker = new ChildlessEventTracker();

        final EventDescriptorWrapper e0 = newEventDescriptor(randomHash(random), NodeId.of(0), 0);
        final EventDescriptorWrapper e1 = newEventDescriptor(randomHash(random), NodeId.of(0), 1);
        final EventDescriptorWrapper e2 = newEventDescriptor(randomHash(random), NodeId.of(0), 2);

        tracker.addEvent(e0, List.of());
        tracker.addEvent(e1, List.of(e0));
        tracker.addEvent(e2, List.of(e1));

        final List<EventDescriptorWrapper> batch1 = tracker.getChildlessEvents();
        assertEquals(1, batch1.size());
        assertEquals(e2, batch1.get(0));

        final EventDescriptorWrapper e3 = newEventDescriptor(randomHash(random), NodeId.of(0), 3);
        final EventDescriptorWrapper e3Branch = newEventDescriptor(randomHash(random), NodeId.of(0), 3);

        // Branch with the same generation, existing event should not be discarded.
        tracker.addEvent(e3, List.of(e2));
        tracker.addEvent(e3Branch, List.of(e2));

        final List<EventDescriptorWrapper> batch2 = tracker.getChildlessEvents();
        assertEquals(1, batch2.size());
        assertEquals(e3, batch2.get(0));

        // Branch with a lower generation, existing event should not be discarded.
        final EventDescriptorWrapper e2Branch = newEventDescriptor(randomHash(random), NodeId.of(0), 2);
        tracker.addEvent(e2Branch, List.of(e1));

        assertEquals(1, batch2.size());
        assertEquals(e3, batch2.get(0));

        // Branch with a higher generation, existing event should be discarded.
        final EventDescriptorWrapper e99Branch = newEventDescriptor(randomHash(random), NodeId.of(0), 99);
        tracker.addEvent(e99Branch, List.of());

        final List<EventDescriptorWrapper> batch3 = tracker.getChildlessEvents();
        assertEquals(1, batch3.size());
        assertEquals(e99Branch, batch3.get(0));
    }

    /**
     * Create a new event descriptor with the given parameters and -1 for the address book round.
     * @param hash the hash of the event
     * @param creator the creator of the event
     * @param generation the generation of the event
     * @return the event descriptor
     */
    private EventDescriptorWrapper newEventDescriptor(
            @NonNull final Hash hash, @NonNull final NodeId creator, final long generation) {
        return new EventDescriptorWrapper(new EventDescriptor(hash.getBytes(), creator.id(), -1, generation));
    }
}

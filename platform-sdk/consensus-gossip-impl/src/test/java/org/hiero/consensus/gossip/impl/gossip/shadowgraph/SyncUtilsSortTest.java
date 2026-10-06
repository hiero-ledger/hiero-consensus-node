// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SyncUtils#sort(List)}, which orders the phase-3 send list. Sorting uses the locally calculated
 * sequence number (assigned at orphan-buffer exit), not the birth round, so that the list is topologically ordered
 * before it is sent to a peer.
 */
class SyncUtilsSortTest {

    /**
     * The send list must come back ordered by ascending sequence number regardless of its initial order.
     */
    @Test
    void sortOrdersBySequenceNumberAscending() {
        final Random random = getRandomPrintSeed();

        // Build events whose sequence numbers are deliberately out of order relative to the list order.
        final long[] sequenceNumbers = {5, 1, 4, 2, 3};
        final List<PlatformEvent> events = new ArrayList<>();
        for (final long sequenceNumber : sequenceNumbers) {
            events.add(new TestingEventBuilder(random)
                    .setSequenceNumberOverride(sequenceNumber)
                    .build());
        }

        SyncUtils.sort(events);

        long previous = Long.MIN_VALUE;
        for (final PlatformEvent event : events) {
            assertTrue(
                    event.getSequenceNumber() > previous, "events must be in strictly ascending sequence-number order");
            previous = event.getSequenceNumber();
        }
        assertEquals(sequenceNumbers.length, events.size(), "sort must not add or drop events");
    }

    /**
     * A creator's self-parent always leaves the orphan buffer before its child, and parents are released before their
     * children, so sequence numbers increase down every parent edge. Sorting by sequence number must therefore place
     * every parent before its children, even when the input is shuffled.
     */
    @Test
    void sortYieldsTopologicalOrder() {
        final Random random = getRandomPrintSeed();

        // Build a small DAG parent-first. TestingEventBuilder assigns an ever-increasing sequence number at build
        // time, mirroring the orphan buffer assigning it at release time, so a parent always has a lower sequence
        // number than its children.
        final PlatformEvent a = new TestingEventBuilder(random).build();
        final PlatformEvent b = new TestingEventBuilder(random).build();
        final PlatformEvent c = new TestingEventBuilder(random)
                .setSelfParent(a)
                .setOtherParent(b)
                .build();
        final PlatformEvent d = new TestingEventBuilder(random)
                .setSelfParent(b)
                .setOtherParent(c)
                .build();
        final PlatformEvent e = new TestingEventBuilder(random)
                .setSelfParent(c)
                .setOtherParent(d)
                .build();

        final List<PlatformEvent> events = new ArrayList<>(List.of(a, b, c, d, e));
        Collections.shuffle(events, random);

        SyncUtils.sort(events);

        // Record the position of each event in the sorted list, keyed by hash.
        final Map<Hash, Integer> positionByHash = new HashMap<>();
        for (int i = 0; i < events.size(); i++) {
            positionByHash.put(events.get(i).getHash(), i);
        }

        // Every parent that is present in the list must appear before its child.
        for (int i = 0; i < events.size(); i++) {
            final PlatformEvent event = events.get(i);
            for (final EventDescriptorWrapper parent : event.getAllParents()) {
                final Integer parentPosition = positionByHash.get(parent.hash());
                if (parentPosition != null) {
                    assertTrue(
                            parentPosition < i, "parent must be sorted before its child to preserve topological order");
                }
            }
        }
    }
}

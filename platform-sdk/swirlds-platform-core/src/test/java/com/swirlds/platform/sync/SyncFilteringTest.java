// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.sync;

import static com.swirlds.common.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.utility.CompareTo;
import com.swirlds.platform.gossip.shadowgraph.SyncUtils;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterBuilder;
import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

class SyncFilteringTest {

    /**
     * Generate a random list of events.
     *
     * @param eventEmitter the event emitter
     * @param time            provides the current time
     * @param timeStep        the time between events
     * @param count           the number of events to generate
     * @return the list of events
     */
    private static List<EventImpl> generateEvents(
            final StandardEventEmitter eventEmitter,
            @NonNull final FakeTime time,
            final Duration timeStep,
            final int count) {

        final List<EventImpl> events = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            final EventImpl event = eventEmitter.getGraphGenerator().generateEvent();
            event.getBaseEvent().setTimeReceived(time.now());
            time.tick(timeStep);
            events.add(event);
        }

        return events;
    }

    /**
     * Find all ancestors of expected events, and add them to the list of expected events.
     *
     * @param expectedEvents the list of expected events
     * @param eventMap       a map of event hashes to events
     */
    private static void findAncestorsOfExpectedEvents(
            @NonNull final List<PlatformEvent> expectedEvents, @NonNull final Map<Hash, PlatformEvent> eventMap) {

        final Set<Hash> expectedEventHashes = new HashSet<>();
        for (final PlatformEvent event : expectedEvents) {
            expectedEventHashes.add(event.getHash());
        }

        for (int index = 0; index < expectedEvents.size(); index++) {

            final PlatformEvent event = expectedEvents.get(index);

            final EventDescriptorWrapper selfParent = event.getSelfParent();
            if (selfParent != null) {
                final Hash selfParentHash = selfParent.hash();
                if (!expectedEventHashes.contains(selfParentHash)) {
                    expectedEvents.add(eventMap.get(selfParentHash));
                    expectedEventHashes.add(selfParentHash);
                }
            }
            final List<EventDescriptorWrapper> otherParents = event.getOtherParents();
            if (!otherParents.isEmpty()) {
                for (final EventDescriptorWrapper otherParent : otherParents) {
                    final Hash otherParentHash = otherParent.hash();
                    if (!expectedEventHashes.contains(otherParentHash)) {
                        expectedEvents.add(eventMap.get(otherParentHash));
                        expectedEventHashes.add(otherParentHash);
                    }
                }
            }
        }
    }

    @Test
    void filterLikelyDuplicatesTest() {
        final Random random = getRandomPrintSeed();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final StandardEventEmitter eventEmitter = EventEmitterBuilder.newBuilder()
                .setPlatformContext(platformContext)
                .setRandomSeed(random.nextLong())
                .setNumNodes(32)
                .build();

        final NodeId selfId =
                RosterUtils.getNodeId(eventEmitter.getGraphGenerator().getRoster(), 0);

        final Instant startingTime = Instant.ofEpochMilli(random.nextInt());
        final Duration timeStep = Duration.ofMillis(10);

        final FakeTime time = new FakeTime(startingTime, Duration.ZERO);

        final int eventCount = 1000;
        final List<PlatformEvent> events = generateEvents(eventEmitter, time, timeStep, eventCount).stream()
                .map(EventImpl::getBaseEvent)
                .sorted(Comparator.comparingLong(PlatformEvent::getGeneration))
                .toList();

        final Map<Hash, PlatformEvent> eventMap =
                events.stream().collect(Collectors.toMap(PlatformEvent::getHash, Function.identity()));

        final Duration nonAncestorSendThreshold = platformContext
                .getConfiguration()
                .getConfigData(SyncConfig.class)
                .nonAncestorFilterThreshold();

        final Instant endTime =
                startingTime.plus(timeStep.multipliedBy(eventCount)).plus(nonAncestorSendThreshold.multipliedBy(2));

        // Test filtering multiple times. Each iteration, move time forward. We should see more and more events
        // returned as they age.
        while (time.now().isBefore(endTime)) {
            final List<PlatformEvent> filteredEvents =
                    SyncUtils.filterLikelyDuplicates(selfId, nonAncestorSendThreshold, time.now(), events);

            // Gather a list of events we expect to see.
            final List<PlatformEvent> expectedEvents = new ArrayList<>();
            for (int index = events.size() - 1; index >= 0; index--) {
                final PlatformEvent event = events.get(index);
                if (event.getCreatorId().equals(selfId)) {
                    expectedEvents.add(event);
                } else {
                    final Duration eventAge = Duration.between(event.getTimeReceived(), time.now());
                    if (CompareTo.isGreaterThan(eventAge, nonAncestorSendThreshold)) {
                        expectedEvents.add(event);
                    }
                }
            }

            // The ancestors of events that meet the above criteria are also expected to be seen.
            findAncestorsOfExpectedEvents(expectedEvents, eventMap);

            // Gather a list of hashes that were allowed through by the filter.
            final Set<Hash> filteredHashes = new HashSet<>();
            for (final PlatformEvent event : filteredEvents) {
                filteredHashes.add(event.getHash());
            }

            // Make sure we see exactly the events we are expecting.
            assertEquals(expectedEvents.size(), filteredEvents.size());
            for (final PlatformEvent expectedEvent : expectedEvents) {
                assertTrue(filteredHashes.contains(expectedEvent.getHash()));
            }

            // Verify topological ordering.
            long maxGeneration = -1;
            for (final PlatformEvent event : filteredEvents) {
                final long generation = event.getGeneration();
                assertTrue(generation >= maxGeneration);
                maxGeneration = generation;
            }

            time.tick(Duration.ofMillis(100));
        }
    }
}

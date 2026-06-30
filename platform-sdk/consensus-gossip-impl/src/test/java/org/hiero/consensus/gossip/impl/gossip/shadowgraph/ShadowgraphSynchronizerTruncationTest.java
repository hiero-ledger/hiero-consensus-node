// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link ShadowgraphSynchronizer#createSendList} truncates the send list to {@code sync.maxSyncEventCount}
 * after the topological sort, so the events that are kept form a topological prefix (every parent of a kept event is
 * also kept). Because the send list is sorted by the ever-increasing sequence number, a parent always sorts before its
 * children, so truncation never drops a parent while keeping its child.
 */
class ShadowgraphSynchronizerTruncationTest {

    private static final int NUM_NODES = 4;

    /**
     * A genesis event window: birth round 1 is non-ancient and non-expired, so every event the test builds is eligible
     * to send (none are filtered out before truncation).
     */
    private static EventWindow nonAncientWindow() {
        return EventWindow.getGenesisEventWindow();
    }

    @Test
    void createSendListTruncatesToTopologicalPrefix() {
        final Random random = getRandomPrintSeed();

        final int maxSyncEventCount = 3;
        final Configuration configuration = new TestConfigBuilder()
                // isolate truncation: do not let the duplicate filter drop events based on age
                .withValue("sync.filterLikelyDuplicates", false)
                .withValue("sync.maxSyncEventCount", maxSyncEventCount)
                .getOrCreateConfig();

        final NodeId selfId = NodeId.of(0);
        final ShadowgraphSynchronizer synchronizer = new ShadowgraphSynchronizer(
                configuration,
                new NoOpMetrics(),
                new FakeTime(),
                NUM_NODES,
                mock(SyncMetrics.class),
                new NoOpIntakeEventCounter(),
                progress -> {});

        synchronizer.updateEventWindow(nonAncientWindow());

        // Build a single-creator chain of 6 events, parent-first. Each event's self-parent is the previous event. The
        // sequence number is assigned explicitly and strictly increasing down the chain, mirroring the orphan buffer
        // assigning an ever-increasing number at release time (a parent always exits before its child).
        final int chainLength = 6;
        final List<PlatformEvent> chain = new ArrayList<>();
        PlatformEvent selfParent = null;
        for (int i = 0; i < chainLength; i++) {
            final PlatformEvent event = new TestingEventBuilder(random)
                    .setCreatorId(selfId)
                    .setSelfParent(selfParent)
                    .setBirthRound(1)
                    .setSequenceNumberOverride(i + 1)
                    .build();
            chain.add(event);
            synchronizer.addEvent(event);
            selfParent = event;
        }

        final Set<ShadowEvent> knownSet = new HashSet<>();
        final List<PlatformEvent> sendList =
                synchronizer.createSendList(selfId, knownSet, nonAncientWindow(), nonAncientWindow(), false);

        // The list is truncated to the configured maximum.
        assertEquals(maxSyncEventCount, sendList.size(), "send list must be truncated to maxSyncEventCount");

        // The kept events are exactly the maxSyncEventCount events with the lowest sequence numbers.
        final Set<Hash> expectedHashes = chain.stream()
                .sorted(Comparator.comparingLong(PlatformEvent::getSequenceNumber))
                .limit(maxSyncEventCount)
                .map(PlatformEvent::getHash)
                .collect(Collectors.toSet());
        final Set<Hash> actualHashes =
                sendList.stream().map(PlatformEvent::getHash).collect(Collectors.toSet());
        assertEquals(expectedHashes, actualHashes, "truncation must keep the lowest-sequence-number events");

        // The kept set is a topological prefix: every parent of a kept event is also kept. This is the property the
        // sequence-number sort guarantees and that truncation must not break by shipping an orphan.
        for (final PlatformEvent event : sendList) {
            for (final EventDescriptorWrapper parent : event.getAllParents()) {
                assertTrue(
                        actualHashes.contains(parent.hash()),
                        "a parent of a sent event must also be sent (no orphan in the truncated list)");
            }
        }
    }
}

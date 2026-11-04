// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests that {@link ShadowgraphSynchronizer#createSendList} never sends an event that is a far future event for the
 * peer, i.e. an event whose birth round is greater than the peer's pending round plus
 * {@code gossip.farFutureEventThreshold}.
 */
class ShadowgraphSynchronizerFarFutureTest {

    private static final int NUM_NODES = 4;
    private static final NodeId SELF_ID = NodeId.of(0);

    /** This node is well ahead of the peer, so every event built by the tests is in its shadowgraph. */
    private static final EventWindow MY_WINDOW = new EventWindow(30, 31, 1, 1);

    private Random random;
    private long nextSequenceNumber;

    @BeforeEach
    void setUp() {
        random = getRandomPrintSeed();
        nextSequenceNumber = 1;
    }

    private static ShadowgraphSynchronizer createSynchronizer(final int threshold, final int maxSyncEventCount) {
        final Configuration configuration = new TestConfigBuilder()
                // isolate the far future filter: do not let the duplicate filter drop events based on age
                .withValue("sync.filterLikelyDuplicates", false)
                .withValue("sync.maxSyncEventCount", maxSyncEventCount)
                .withValue("gossip.farFutureEventThreshold", threshold)
                .getOrCreateConfig();
        final ShadowgraphSynchronizer synchronizer = new ShadowgraphSynchronizer(
                configuration,
                new NoOpMetrics(),
                new FakeTime(),
                NUM_NODES,
                mock(SyncMetrics.class),
                new NoOpIntakeEventCounter(),
                progress -> {});
        synchronizer.updateEventWindow(MY_WINDOW);
        return synchronizer;
    }

    /**
     * Builds a single-creator chain of events with the given birth rounds (which must be non-decreasing, as they are
     * for an honest creator), adds them to the synchronizer, and returns them. Sequence numbers are strictly increasing
     * across all events built by a test, mirroring the orphan buffer's release order.
     */
    private List<PlatformEvent> addChain(
            final ShadowgraphSynchronizer synchronizer, final NodeId creator, final long... birthRounds) {
        final List<PlatformEvent> chain = new ArrayList<>();
        PlatformEvent selfParent = null;
        for (final long birthRound : birthRounds) {
            final PlatformEvent event = new TestingEventBuilder(random)
                    .setCreatorId(creator)
                    .setSelfParent(selfParent)
                    .setBirthRound(birthRound)
                    .setSequenceNumberOverride(nextSequenceNumber++)
                    .build();
            synchronizer.addEvent(event);
            chain.add(event);
            selfParent = event;
        }
        return chain;
    }

    private static Set<Hash> hashes(final List<PlatformEvent> events) {
        return events.stream().map(PlatformEvent::getHash).collect(Collectors.toSet());
    }

    private static List<PlatformEvent> createSendList(
            final ShadowgraphSynchronizer synchronizer, final EventWindow theirWindow) {
        return synchronizer.createSendList(SELF_ID, new HashSet<>(), MY_WINDOW, theirWindow, false);
    }

    /**
     * An event whose birth round is exactly the peer's pending round plus the threshold is sent; one round higher is
     * not.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 10})
    void boundaryIsPendingRoundPlusThreshold(final int threshold) {
        final ShadowgraphSynchronizer synchronizer = createSynchronizer(threshold, 0);

        final EventWindow theirWindow = new EventWindow(10, 11, 1, 1);
        final long maxAcceptable = theirWindow.getPendingConsensusRound() + threshold;

        final List<PlatformEvent> accepted = new ArrayList<>();
        accepted.addAll(addChain(synchronizer, NodeId.of(1), 1, maxAcceptable - 1, maxAcceptable));
        // far future for the peer
        addChain(synchronizer, NodeId.of(2), maxAcceptable + 1, maxAcceptable + 5);
        accepted.addAll(addChain(synchronizer, NodeId.of(3), maxAcceptable));

        final List<PlatformEvent> sendList = createSendList(synchronizer, theirWindow);

        assertEquals(hashes(accepted), hashes(sendList), "only events at or below the boundary should be sent");
    }

    /**
     * The filter is computed from the peer's pending round, not this node's. This node is far ahead of the peer, so
     * events that are fine for this node are far future events for the peer.
     */
    @Test
    void filterUsesPeerPendingRound() {
        final int threshold = 5;
        final ShadowgraphSynchronizer synchronizer = createSynchronizer(threshold, 0);

        // our pending round is 31 (max acceptable for us would be 36); the peer's is 11 (max acceptable 16)
        final List<PlatformEvent> forPeer = addChain(synchronizer, NodeId.of(1), 5, 16);
        addChain(synchronizer, NodeId.of(2), 17, 31);

        final EventWindow peerBehind = new EventWindow(10, 11, 1, 1);
        assertEquals(hashes(forPeer), hashes(createSendList(synchronizer, peerBehind)));

        // a peer with the same pending round as ours receives everything
        final EventWindow peerLevel = new EventWindow(30, 31, 1, 1);
        assertEquals(4, createSendList(synchronizer, peerLevel).size());
    }

    /**
     * Far future events are removed before the send list is truncated to {@code sync.maxSyncEventCount}. Events from
     * different creators are not ordered by birth round, so far future events can sort ahead of acceptable ones; if the
     * list were truncated first, they would take up every slot and the peer would receive nothing useful.
     */
    @Test
    void filterIsAppliedBeforeTruncation() {
        final int threshold = 5;
        final int maxSyncEventCount = 2;
        final ShadowgraphSynchronizer synchronizer = createSynchronizer(threshold, maxSyncEventCount);

        // far future events for the peer, released first so they have the lowest sequence numbers
        addChain(synchronizer, NodeId.of(1), 17, 17, 18);
        // acceptable events from a lagging creator, released later
        final List<PlatformEvent> acceptable = addChain(synchronizer, NodeId.of(2), 10, 10, 11);

        final EventWindow theirWindow = new EventWindow(10, 11, 1, 1);
        final List<PlatformEvent> sendList = createSendList(synchronizer, theirWindow);

        assertEquals(
                hashes(acceptable.subList(0, maxSyncEventCount)),
                hashes(sendList),
                "the truncated list must contain the lowest-sequence acceptable events, not be emptied by far future"
                        + " events");
    }
}

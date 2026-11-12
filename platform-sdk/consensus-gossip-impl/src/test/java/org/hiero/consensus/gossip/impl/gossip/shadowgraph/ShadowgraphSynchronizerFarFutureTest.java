// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.consensus.gossip.config.GossipConfig.FAR_FUTURE_EVENT_FILTER_DISABLED;
import static org.mockito.Mockito.mock;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.validation.ConfigViolationException;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
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
 * {@code gossip.farFutureEventOffset}.
 */
class ShadowgraphSynchronizerFarFutureTest {

    private static final int NUM_NODES = 4;
    private static final NodeId SELF_ID = NodeId.of(0);
    private static final int UNLIMITED_EVENTS_PER_SYNC = 0;

    /**
     * This node's event window: well ahead of the peers in these tests, so no test event is a far future event for this
     * node, and an ancient threshold of 1 so no test event is ancient.
     */
    private static final EventWindow MY_WINDOW = new EventWindow(30, 31, 1, 1);

    private Random random;
    private long nextSequenceNumber;

    @BeforeEach
    void setUp() {
        random = getRandomPrintSeed();
        nextSequenceNumber = 1;
    }

    private static ShadowgraphSynchronizer createSynchronizer(final int offset, final int maxSyncEventCount) {
        final ShadowgraphSynchronizer synchronizer = createSynchronizerWithoutEventWindow(offset, maxSyncEventCount);
        synchronizer.updateEventWindow(MY_WINDOW);
        return synchronizer;
    }

    private static ShadowgraphSynchronizer createSynchronizerWithoutEventWindow(
            final int offset, final int maxSyncEventCount) {
        final Configuration configuration = new TestConfigBuilder()
                // isolate the far future filter: do not let the duplicate filter drop events based on age
                .withValue("sync.filterLikelyDuplicates", false)
                .withValue("sync.maxSyncEventCount", maxSyncEventCount)
                .withValue("gossip.farFutureEventOffset", offset)
                .getOrCreateConfig();
        return new ShadowgraphSynchronizer(
                configuration,
                new NoOpMetrics(),
                new FakeTime(),
                NUM_NODES,
                mock(SyncMetrics.class),
                new NoOpIntakeEventCounter(),
                _ -> {});
    }

    /**
     * Builds a single-creator chain of events with the given birth rounds (which must be non-decreasing, as they are
     * for an honest creator), adds them to the synchronizer, and returns them. Sequence numbers are strictly increasing
     * across all events built by a test, mirroring the orphan buffer's release order.
     */
    private List<PlatformEvent> addSingleCreatorEventChain(
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

    private static List<PlatformEvent> createSendList(
            final ShadowgraphSynchronizer synchronizer, final EventWindow theirWindow) {
        return synchronizer.createSendList(SELF_ID, new HashSet<>(), MY_WINDOW, theirWindow, false);
    }

    /**
     * An event whose birth round is exactly the peer's pending round plus the offset is sent; one round higher is
     * not.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 10})
    void boundaryIsPendingRoundPlusOffset(final int offset) {
        final ShadowgraphSynchronizer synchronizer = createSynchronizer(offset, UNLIMITED_EVENTS_PER_SYNC);

        final EventWindow theirWindow = new EventWindow(10, 11, 1, 1);
        final long maxAcceptable = theirWindow.getPendingConsensusRound() + offset;

        // A list of all events we expect to be sent to the peer
        final List<PlatformEvent> accepted = new ArrayList<>();

        // Add some events to the shadowgraph that should pass the far future filter
        accepted.addAll(addSingleCreatorEventChain(synchronizer, NodeId.of(1), 1, maxAcceptable - 1, maxAcceptable));

        // Add some far future events to the shadowgraph that should not pass the far future filter
        addSingleCreatorEventChain(synchronizer, NodeId.of(2), maxAcceptable + 1, maxAcceptable + 5);

        // Add more events that should pass, but with a higher sequence number than events that should not pass.
        accepted.addAll(addSingleCreatorEventChain(synchronizer, NodeId.of(3), maxAcceptable));

        final List<PlatformEvent> sendList = createSendList(synchronizer, theirWindow);

        assertThat(sendList)
                .as("only events at or below the boundary should be sent")
                .containsExactlyInAnyOrderElementsOf(accepted);
    }

    /**
     * The filter is computed from the peer's pending round, not this node's. This node is far ahead of the peer, so
     * events that are fine for this node are far future events for the peer.
     */
    @Test
    void filterUsesPeerPendingRound() {
        final int offset = 5;
        final ShadowgraphSynchronizer synchronizer = createSynchronizer(offset, UNLIMITED_EVENTS_PER_SYNC);

        // our pending round is 31 (max acceptable for us would be 36); the peer's is 11 (max acceptable 16)
        // Add two events to the shadowgraph which are acceptable for the peer
        final List<PlatformEvent> forPeer = addSingleCreatorEventChain(synchronizer, NodeId.of(1), 5, 16);
        // Add two events to the shadowgraph which are NOT acceptable for the peer
        addSingleCreatorEventChain(synchronizer, NodeId.of(2), 17, 31);

        // Verify that only the events that match the peer's event window are sent
        final EventWindow peerBehind = new EventWindow(10, 11, 1, 1);
        assertThat(createSendList(synchronizer, peerBehind)).containsExactlyInAnyOrderElementsOf(forPeer);

        // Verify that a peer with the same pending round as ours receives everything
        final EventWindow peerLevel = new EventWindow(30, 31, 1, 1);
        assertThat(createSendList(synchronizer, peerLevel)).hasSize(4);
    }

    /**
     * Far future events are removed before the send list is truncated to {@code sync.maxSyncEventCount}. Events from
     * different creators are not ordered by birth round, so far future events can sort ahead of acceptable ones; if the
     * list were truncated first, they would take up every slot and the peer would receive nothing useful.
     */
    @Test
    void filterIsAppliedBeforeTruncation() {
        final int offset = 5;
        final int maxSyncEventCount = 2;
        final ShadowgraphSynchronizer synchronizer = createSynchronizer(offset, maxSyncEventCount);

        // far future events for the peer, released first so they have the lowest sequence numbers
        addSingleCreatorEventChain(synchronizer, NodeId.of(1), 17, 17, 18);
        // acceptable events from a lagging creator, released later and therefore have a higher sequence number
        final List<PlatformEvent> acceptable = addSingleCreatorEventChain(synchronizer, NodeId.of(2), 10, 10, 11);

        final EventWindow theirWindow = new EventWindow(10, 11, 1, 1);
        final List<PlatformEvent> sendList = createSendList(synchronizer, theirWindow);

        assertThat(sendList)
                .as("the truncated list must contain the lowest-sequence acceptable events, not be emptied by far"
                        + " future events")
                .containsExactlyElementsOf(acceptable.subList(0, maxSyncEventCount));
    }

    /** Disabling the filter means every event is sent, however far ahead of the peer it is. */
    @Test
    void disabledFilterSendsAllEvents() {
        final ShadowgraphSynchronizer synchronizer =
                createSynchronizer(FAR_FUTURE_EVENT_FILTER_DISABLED, UNLIMITED_EVENTS_PER_SYNC);

        final List<PlatformEvent> all = new ArrayList<>();
        all.addAll(addSingleCreatorEventChain(synchronizer, NodeId.of(1), 1, 11));
        all.addAll(addSingleCreatorEventChain(synchronizer, NodeId.of(2), 20, 31));

        final EventWindow theirWindow = new EventWindow(10, 11, 1, 1);
        assertThat(createSendList(synchronizer, theirWindow)).containsExactlyInAnyOrderElementsOf(all);
    }

    /** The disabled value is the only negative offset; any lower value is rejected when the configuration is read. */
    @ParameterizedTest
    @ValueSource(ints = {-2, -5, Integer.MIN_VALUE})
    void offsetBelowDisabledValueIsRejected(final int offset) {
        assertThatThrownBy(() -> createSynchronizer(offset, UNLIMITED_EVENTS_PER_SYNC))
                .isInstanceOf(ConfigViolationException.class);
    }

    // THIS NODE'S EVENT WINDOW, used by RpcPeerHandler to filter received events

    /** Before any event window has been applied, this node's event window is the genesis window. */
    @Test
    void eventWindowIsGenesisBeforeFirstUpdate() {
        final ShadowgraphSynchronizer synchronizer = createSynchronizerWithoutEventWindow(5, UNLIMITED_EVENTS_PER_SYNC);
        assertThat(synchronizer.getEventWindow()).isEqualTo(EventWindow.getGenesisEventWindow());
    }

    /** This node's event window is the one most recently applied. */
    @Test
    void eventWindowIsLatestUpdate() {
        final ShadowgraphSynchronizer synchronizer = createSynchronizer(5, UNLIMITED_EVENTS_PER_SYNC);
        assertThat(synchronizer.getEventWindow()).isEqualTo(MY_WINDOW);

        final EventWindow next = new EventWindow(31, 32, 1, 1);
        synchronizer.updateEventWindow(next);
        assertThat(synchronizer.getEventWindow()).isEqualTo(next);
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.model.event.EventConstants.SEQUENCE_NUMBER_UNDEFINED;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.event.creator.impl.tipset.DefaultTipsetTracker;
import org.hiero.consensus.event.creator.impl.tipset.Tipset;
import org.hiero.consensus.event.creator.impl.tipset.TipsetTracker;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FalconSharedTipsets Test")
class FalconSharedTipsetsTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    /** The number of birth rounds below the latest consensus round that are not ancient. */
    private static final int NON_ANCIENT_ROUNDS = 5;

    private List<PlatformEvent> events;
    private RosterWrapper roster;
    private long highestBirthRound;
    private FalconSharedTipsets sharedTipsets;

    @BeforeEach
    void setUp() {
        final GeneratorEventGraphSource source = GeneratorEventGraphSourceBuilder.builder()
                .seed(42)
                .numNodes(4)
                .time(new FakeTime(START, Duration.ofMillis(1)))
                .build();
        events = source.nextEvents(1_000);
        roster = source.getRoster();
        highestBirthRound =
                events.stream().mapToLong(PlatformEvent::getBirthRound).max().orElseThrow();
        sharedTipsets = new FalconSharedTipsets();
    }

    @Test
    @DisplayName("Keeps a tipset until its event is ancient for every tracker")
    void keepsTipsetsUntilAncientForEveryTracker() {
        final NodeId firstId = roster.nodeIdAtIndex(0);
        final FalconSharedTipsetTracker first = sharedTipsets.newTracker(firstId, roster);
        final FalconSharedTipsetTracker second = sharedTipsets.newTracker(roster.nodeIdAtIndex(1), roster);
        events.forEach(event -> addAsMiniNode(first, firstId, event));
        assertThat(sharedTipsets.size()).as("tipsets after adding all events").isEqualTo(events.size());

        final long lowerThreshold = highestBirthRound / 3;
        first.setEventWindow(window(lowerThreshold));
        assertThat(sharedTipsets.size())
                .as("tipsets while another tracker still needs all of them")
                .isEqualTo(events.size());

        second.setEventWindow(window(lowerThreshold));
        assertThat(sharedTipsets.size())
                .as("tipsets once all trackers have the same window")
                .isEqualTo(countNonAncient(lowerThreshold));

        final long higherThreshold = 2 * highestBirthRound / 3;
        first.setEventWindow(window(higherThreshold));
        assertThat(sharedTipsets.size())
                .as("tipsets while another tracker has a lower threshold")
                .isEqualTo(countNonAncient(lowerThreshold));

        sharedTipsets.unregister(second);
        assertThat(sharedTipsets.size())
                .as("tipsets once the tracker with the lower threshold is removed")
                .isEqualTo(countNonAncient(higherThreshold));
    }

    @Test
    @DisplayName("A tracker created late keeps the tipsets the shared tipsets no longer hold")
    void lateTrackerKeepsTipsetsTheSharedTipsetsNoLongerHold() {
        final NodeId earlyId = roster.nodeIdAtIndex(0);
        final FalconSharedTipsetTracker early = sharedTipsets.newTracker(earlyId, roster);
        events.forEach(event -> addAsMiniNode(early, earlyId, event));
        final long threshold = highestBirthRound / 2;
        early.setEventWindow(window(threshold));
        assertThat(sharedTipsets.size()).as("tipsets after pruning").isEqualTo(countNonAncient(threshold));

        // The late tracker starts from the genesis window, so it adds the events that the shared tipsets no longer hold
        final NodeId selfId = roster.nodeIdAtIndex(1);
        final FalconSharedTipsetTracker late = sharedTipsets.newTracker(selfId, roster);
        final DefaultTipsetTracker expectedTracker = new DefaultTipsetTracker(Time.getCurrent(), selfId, roster);
        final Map<Long, Long> sequenceNumbers = new HashMap<>();
        for (int i = 0; i < events.size(); i++) {
            final PlatformEvent event = events.get(i).copyGossipedData();
            event.setSequenceNumber(i);
            sequenceNumbers.put(FalconSharedTipsets.orderingKey(event), (long) i);
            final Tipset expected = add(expectedTracker, selfId, event);
            final Tipset actual = addAsMiniNode(late, selfId, event);
            for (final NodeId nodeId : roster.nodeIds()) {
                if (!nodeId.equals(selfId)) {
                    final long orderingKey = actual.getTipOrderingKeyForNode(nodeId);
                    final long sequenceNumber =
                            orderingKey == SEQUENCE_NUMBER_UNDEFINED ? orderingKey : sequenceNumbers.get(orderingKey);
                    assertThat(sequenceNumber)
                            .as("tip of %s in the tipset of %s", nodeId, event.getDescriptor())
                            .isEqualTo(expected.getTipOrderingKeyForNode(nodeId));
                }
            }
        }
        assertThat(late.getDivergedTipsetCount())
                .as("events whose shared tipset lacked parents the shared tipsets no longer hold")
                .isPositive();
    }

    /**
     * Adds an event to a tracker of a mini node, as its event creator does, and an event of the node itself to the
     * shared tipsets, as the mini node does when the event leaves its intake.
     *
     * @param tracker the tracker
     * @param selfId the id of the tracker's node
     * @param event the event
     * @return the tipset of the event
     */
    @NonNull
    private Tipset addAsMiniNode(
            @NonNull final FalconSharedTipsetTracker tracker,
            @NonNull final NodeId selfId,
            @NonNull final PlatformEvent event) {
        final Tipset tipset = add(tracker, selfId, event);
        if (selfId.equals(event.getCreatorId())) {
            sharedTipsets.computeIfAbsent(event);
        }
        return tipset;
    }

    /**
     * Adds an event to a tracker, as an event creator does.
     *
     * @param tracker the tracker
     * @param selfId the id of the tracker's node
     * @param event the event
     * @return the tipset of the event
     */
    @NonNull
    private static Tipset add(
            @NonNull final TipsetTracker tracker, @NonNull final NodeId selfId, @NonNull final PlatformEvent event) {
        return selfId.equals(event.getCreatorId())
                ? tracker.addSelfEvent(event.getDescriptor(), event.getAllParents())
                : tracker.addPeerEvent(event);
    }

    @NonNull
    private static EventWindow window(final long ancientThreshold) {
        return EventWindowBuilder.builder()
                .setLatestConsensusRound(ancientThreshold + NON_ANCIENT_ROUNDS - 1)
                .setAncientThreshold(ancientThreshold)
                .build();
    }

    private long countNonAncient(final long ancientThreshold) {
        return events.stream()
                .filter(event -> event.getBirthRound() >= ancientThreshold)
                .count();
    }
}

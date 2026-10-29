// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.model.event.EventConstants.SEQUENCE_NUMBER_UNDEFINED;
import static org.hiero.otter.fixtures.falcon.FalconMiniIntakeEquivalenceTest.shuffledDeliveries;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.hiero.consensus.event.creator.impl.tipset.ChildlessEventTracker;
import org.hiero.consensus.event.creator.impl.tipset.DefaultTipsetTracker;
import org.hiero.consensus.event.creator.impl.tipset.Tipset;
import org.hiero.consensus.event.creator.impl.tipset.TipsetWeightCalculator;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs a {@link FalconSharedTipsetTracker} next to a {@link DefaultTipsetTracker} at every node of a generated graph,
 * and checks that both compute the same tipsets, apart from the ordering keys and the node's own entry, and give the
 * same advancement weights and selfishness scores.
 *
 * <p>Every node receives the events of the graph in an order of its own, some of them twice and some of them never,
 * through a {@link FalconMiniIntake} of its own. The intake also gives the events the sequence numbers that the default
 * tracker uses as ordering keys. The nodes take turns receiving an event, so which node adds an event first varies.
 *
 * <p>The event window follows the highest birth round any node has received. With staggered windows, every node adopts
 * each window after a random delay, so the trackers have different event windows at the same time.
 */
@DisplayName("FalconSharedTipsets Equivalence Test")
class FalconSharedTipsetsEquivalenceTest {

    /** The number of birth rounds below the highest one received that are not ancient. */
    private static final int NON_ANCIENT_ROUNDS = 5;

    /** The largest number of rounds the window moves at a time. */
    private static final int MAX_WINDOW_STEP = 3;

    /** The largest delay, in rounds of the generated graph, with which a node adopts a window if windows are staggered. */
    private static final double MAX_WINDOW_DELAY_ROUNDS = 2;

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * A window that a node adopts once the turn has come.
     *
     * @param turn the turn in which the node adopts the window
     * @param window the window
     */
    private record ScheduledWindow(int turn, @NonNull EventWindow window) {}

    /** A node with its intake, the two trackers it compares, and their weight calculators. */
    private static final class TestNode {

        private final NodeId selfId;
        private final List<PlatformEvent> deliveries;
        private final FalconMiniIntake intake = new FalconMiniIntake();
        private final DefaultTipsetTracker defaultTracker;
        private final FalconSharedTipsetTracker sharedTracker;
        private final ChildlessEventTracker childlessEvents = new ChildlessEventTracker();
        private final TipsetWeightCalculator defaultCalculator;
        private final TipsetWeightCalculator sharedCalculator;
        private final ArrayDeque<ScheduledWindow> windows = new ArrayDeque<>();

        /**
         * The sequence numbers this node's intake gave the events, by their ordering keys in the shared tipsets. The
         * generator gives every event a different creation time, so the ordering keys identify the events.
         */
        private final Map<Long, Long> sequenceNumbers = new HashMap<>();

        private int nextDelivery;

        @Nullable
        private EventDescriptorWrapper lastSelfEvent;

        private TestNode(
                @NonNull final NodeId selfId,
                @NonNull final List<PlatformEvent> deliveries,
                @NonNull final RosterWrapper roster,
                @NonNull final FalconSharedTipsets sharedTipsets,
                @NonNull final Configuration configuration) {
            this.selfId = selfId;
            this.deliveries = deliveries;
            final Time time = Time.getCurrent();
            defaultTracker = new DefaultTipsetTracker(time, selfId, roster);
            sharedTracker = sharedTipsets.newTracker(selfId, roster);
            defaultCalculator =
                    new TipsetWeightCalculator(configuration, time, roster, selfId, defaultTracker, childlessEvents);
            sharedCalculator =
                    new TipsetWeightCalculator(configuration, time, roster, selfId, sharedTracker, childlessEvents);
        }

        private boolean hasWork() {
            return nextDelivery < deliveries.size() || !windows.isEmpty();
        }
    }

    /**
     * The scenarios: the number of nodes, the number of events, whether windows are staggered, and the seed. The number
     * of events is chosen so that each graph reaches 25 rounds or more.
     */
    static Stream<Arguments> scenarios() {
        return Stream.of(
                Arguments.of(4, 2_000, false, 1L),
                Arguments.of(4, 2_000, true, 2L),
                Arguments.of(10, 3_000, false, 3L),
                Arguments.of(10, 3_000, true, 4L),
                Arguments.of(25, 7_500, true, 5L));
    }

    @ParameterizedTest(name = "{0} nodes, {1} events, staggered windows: {2}, seed {3}")
    @MethodSource("scenarios")
    @DisplayName("Shared trackers compute what default trackers compute")
    void sharedTrackersComputeWhatDefaultTrackersCompute(
            final int nodeCount, final int eventCount, final boolean staggeredWindows, final long seed) {
        final Random random = new Random(seed);
        final GeneratorEventGraphSource source = GeneratorEventGraphSourceBuilder.builder()
                .seed(seed)
                .numNodes(nodeCount)
                .time(new FakeTime(START, Duration.ofMillis(1)))
                .build();
        final List<PlatformEvent> events = source.nextEvents(eventCount);
        final RosterWrapper roster = source.getRoster();
        assertThat(events.stream().map(FalconSharedTipsets::orderingKey))
                .as("creation times of the generated events")
                .doesNotHaveDuplicates();

        final long highestGeneratedBirthRound =
                events.stream().mapToLong(PlatformEvent::getBirthRound).max().orElseThrow();
        final double eventsPerRound = (double) eventCount / highestGeneratedBirthRound;
        final int maxDisplacement = (int) Math.ceil(eventsPerRound);
        final int maxWindowDelay = staggeredWindows ? (int) Math.ceil(MAX_WINDOW_DELAY_ROUNDS * eventsPerRound) : 0;

        final Configuration configuration =
                ConfigurationBuilder.create().autoDiscoverExtensions().build();
        final FalconSharedTipsets sharedTipsets = new FalconSharedTipsets();
        final List<TestNode> nodes = new ArrayList<>();
        for (final NodeId nodeId : roster.nodeIds()) {
            final List<PlatformEvent> deliveries = shuffledDeliveries(events, maxDisplacement, eventsPerRound, random);
            nodes.add(new TestNode(nodeId, deliveries, roster, sharedTipsets, configuration));
        }

        long highestBirthRound = ConsensusConstants.ROUND_FIRST;
        long ancientThreshold = ConsensusConstants.ROUND_FIRST;
        int windowStep = 1 + random.nextInt(MAX_WINDOW_STEP);
        int windowShifts = 0;
        for (int turn = 0; nodes.stream().anyMatch(TestNode::hasWork); turn++) {
            for (final TestNode node : nodes) {
                if (node.nextDelivery < node.deliveries.size()) {
                    final PlatformEvent event = node.deliveries.get(node.nextDelivery++);
                    highestBirthRound = Math.max(highestBirthRound, event.getBirthRound());
                    // Every node sets sequence numbers on what its intake releases, so each gets a copy of its own
                    register(node, roster, sharedTipsets, node.intake.handleEvent(event.copyGossipedData()));
                }
            }

            final long newAncientThreshold = highestBirthRound - NON_ANCIENT_ROUNDS;
            if (newAncientThreshold >= ancientThreshold + windowStep) {
                final EventWindow window = EventWindowBuilder.builder()
                        .setLatestConsensusRound(newAncientThreshold + NON_ANCIENT_ROUNDS - 1)
                        .setAncientThreshold(newAncientThreshold)
                        .build();
                for (final TestNode node : nodes) {
                    // A node adopts the windows in the order they were created
                    final int previousTurn = node.windows.isEmpty()
                            ? turn
                            : node.windows.peekLast().turn();
                    final int delay = maxWindowDelay == 0 ? 0 : random.nextInt(maxWindowDelay + 1);
                    node.windows.addLast(new ScheduledWindow(Math.max(previousTurn, turn + delay), window));
                }
                ancientThreshold = newAncientThreshold;
                windowStep = 1 + random.nextInt(MAX_WINDOW_STEP);
                windowShifts++;
            }

            for (final TestNode node : nodes) {
                while (!node.windows.isEmpty() && node.windows.peekFirst().turn() <= turn) {
                    final EventWindow window = node.windows.removeFirst().window();
                    // As in a mini node: the intake releases events, the trackers get the window, then the events
                    final List<PlatformEvent> released = node.intake.setEventWindow(window);
                    node.defaultTracker.setEventWindow(window);
                    node.sharedTracker.setEventWindow(window);
                    node.childlessEvents.pruneOldEvents(window);
                    register(node, roster, sharedTipsets, released);
                }
            }
        }

        assertThat(windowShifts).as("window shifts").isGreaterThan(3);
        // Nodes that miss different events, or get them at different times, already disagree about which parents
        // are ancient, so trackers diverge even if all of them adopt each window at the same time
        assertThat(nodes.stream()
                        .mapToLong(node -> node.sharedTracker.getDivergedTipsetCount())
                        .sum())
                .as("peer events whose tipset differed from the shared one")
                .isPositive();
    }

    /**
     * Adds the events released by a node's intake to both of its trackers, and compares what they compute.
     *
     * @param node the node
     * @param roster the roster
     * @param sharedTipsets the shared tipsets
     * @param released the released events, in order
     */
    private static void register(
            @NonNull final TestNode node,
            @NonNull final RosterWrapper roster,
            @NonNull final FalconSharedTipsets sharedTipsets,
            @NonNull final List<PlatformEvent> released) {
        for (final PlatformEvent event : released) {
            node.sequenceNumbers.put(FalconSharedTipsets.orderingKey(event), event.getSequenceNumber());
            if (node.selfId.equals(event.getCreatorId())) {
                registerSelfEvent(node, roster, sharedTipsets, event);
            } else {
                final Tipset expected = node.defaultTracker.addPeerEvent(event);
                final Tipset actual = node.sharedTracker.addPeerEvent(event);
                assertSameTips(node, roster, expected, actual, "tipset of peer event " + event.getDescriptor());
                node.childlessEvents.addEvent(event);
            }
        }
    }

    /**
     * Adds an event of the node to both of its trackers, as its event creator would when it creates the event, and
     * compares what they compute before and after.
     *
     * @param node the node
     * @param roster the roster
     * @param sharedTipsets the shared tipsets
     * @param event the event
     */
    private static void registerSelfEvent(
            @NonNull final TestNode node,
            @NonNull final RosterWrapper roster,
            @NonNull final FalconSharedTipsets sharedTipsets,
            @NonNull final PlatformEvent event) {
        final EventDescriptorWrapper descriptor = event.getDescriptor();

        // What the event creator looks at to choose the other parents
        if (node.lastSelfEvent != null) {
            for (final PlatformEvent candidate : node.childlessEvents.getChildlessEvents()) {
                final List<EventDescriptorWrapper> parents = List.of(candidate.getDescriptor(), node.lastSelfEvent);
                assertThat(node.sharedCalculator.getTheoreticalAdvancementWeight(parents))
                        .as("advancement weight of other parent %s for %s", candidate.getDescriptor(), descriptor)
                        .isEqualTo(node.defaultCalculator.getTheoreticalAdvancementWeight(parents));
            }
        }
        for (final NodeId nodeId : roster.nodeIds()) {
            if (!nodeId.equals(node.selfId)) {
                assertThat(node.sharedCalculator.getSelfishnessScoreForNode(nodeId))
                        .as("selfishness towards %s before %s", nodeId, descriptor)
                        .isEqualTo(node.defaultCalculator.getSelfishnessScoreForNode(nodeId));
                assertThat(sequenceNumberOf(node, node.sharedTracker.getLatestTipOrderingKeyForNode(nodeId)))
                        .as("latest tip of %s before %s", nodeId, descriptor)
                        .isEqualTo(node.defaultTracker.getLatestTipOrderingKeyForNode(nodeId));
            }
        }

        final Tipset expected = node.defaultTracker.addSelfEvent(descriptor, event.getAllParents());
        final Tipset actual = node.sharedTracker.addSelfEvent(descriptor, event.getAllParents());
        // As in a mini node, whose own event leaves its intake right after the event creator created it
        sharedTipsets.computeIfAbsent(event);
        assertSameTips(node, roster, expected, actual, "tipset of self event " + descriptor);
        assertThat(node.sharedCalculator.addEventAndGetAdvancementWeight(descriptor))
                .as("advancement weight of %s", descriptor)
                .isEqualTo(node.defaultCalculator.addEventAndGetAdvancementWeight(descriptor));

        node.childlessEvents.registerSelfEventParents(event.getOtherParents());
        node.lastSelfEvent = descriptor;
    }

    /**
     * Checks that a tipset of the shared tracker has the same tips as one of the default tracker, apart from the
     * node's own tip.
     *
     * @param node the node
     * @param roster the roster
     * @param expected the tipset of the default tracker
     * @param actual the tipset of the shared tracker
     * @param description what the tipsets are
     */
    private static void assertSameTips(
            @NonNull final TestNode node,
            @NonNull final RosterWrapper roster,
            @NonNull final Tipset expected,
            @NonNull final Tipset actual,
            @NonNull final String description) {
        for (final NodeId nodeId : roster.nodeIds()) {
            if (!nodeId.equals(node.selfId)) {
                assertThat(sequenceNumberOf(node, actual.getTipOrderingKeyForNode(nodeId)))
                        .as("tip of %s in the %s at %s", nodeId, description, node.selfId)
                        .isEqualTo(expected.getTipOrderingKeyForNode(nodeId));
            }
        }
    }

    /**
     * Translates an ordering key of the shared tipsets into the sequence number the node's intake gave the event.
     *
     * @param node the node
     * @param orderingKey the ordering key
     * @return the sequence number, or {@link org.hiero.consensus.model.event.EventConstants#SEQUENCE_NUMBER_UNDEFINED}
     *     if the ordering key is undefined
     */
    private static long sequenceNumberOf(@NonNull final TestNode node, final long orderingKey) {
        if (orderingKey == SEQUENCE_NUMBER_UNDEFINED) {
            return SEQUENCE_NUMBER_UNDEFINED;
        }
        assertThat(node.sequenceNumbers)
                .as("events released at %s", node.selfId)
                .containsKey(orderingKey);
        return node.sequenceNumbers.get(orderingKey);
    }
}

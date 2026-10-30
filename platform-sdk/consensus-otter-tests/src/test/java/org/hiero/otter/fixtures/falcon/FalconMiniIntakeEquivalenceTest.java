// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.event.intake.impl.deduplication.EventDeduplicator;
import org.hiero.consensus.event.intake.impl.deduplication.StandardEventDeduplicator;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feeds the same events to a {@link FalconMiniIntake} and to the {@link StandardEventDeduplicator} followed by the
 * {@link DefaultOrphanBuffer} it replaces, and checks that both release the same events, in the same order, with the
 * same sequence numbers.
 *
 * <p>The events of a generated graph are delivered out of order, some of them twice and some of them never, while the
 * event window follows the highest birth round delivered so far. An event that is never delivered keeps its descendants
 * waiting until it becomes ancient, so window shifts release orphans as well. How far events move, how many are never
 * delivered and how far the window moves at a time are measured in rounds of the generated graph, so that every
 * scenario exercises the same paths whatever the number of events per round.
 */
@DisplayName("FalconMiniIntake Equivalence Test")
class FalconMiniIntakeEquivalenceTest {

    /** The probability that an event is delivered a second time. */
    private static final double DUPLICATE_PROBABILITY = 0.3;

    /** The average number of events per round that are never delivered. */
    private static final double WITHHELD_EVENTS_PER_ROUND = 0.5;

    /** The number of birth rounds below the highest one delivered that are not ancient. */
    private static final int NON_ANCIENT_ROUNDS = 5;

    /** The largest number of rounds the window moves at a time. */
    private static final int MAX_WINDOW_STEP = 3;

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * An event released by an intake, reduced to what the comparison looks at.
     *
     * @param descriptor the descriptor of the event
     * @param sequenceNumber the sequence number the intake gave the event
     */
    private record Release(@NonNull EventDescriptorWrapper descriptor, long sequenceNumber) {}

    /**
     * An event to deliver.
     *
     * @param position the position the event is delivered at
     * @param event the event
     */
    private record Delivery(int position, @NonNull PlatformEvent event) {}

    /**
     * The scenarios: the number of nodes, the number of events, the number of rounds an event moves back at most, and
     * the seed. The number of events is chosen so that each graph reaches 25 rounds or more.
     */
    static Stream<Arguments> scenarios() {
        return Stream.of(
                Arguments.of(4, 2_000, 1, 1L),
                Arguments.of(4, 2_000, 4, 2L),
                Arguments.of(10, 3_000, 1, 3L),
                Arguments.of(10, 3_000, 4, 4L),
                Arguments.of(50, 15_000, 1, 5L),
                Arguments.of(50, 15_000, 4, 6L));
    }

    @ParameterizedTest(name = "{0} nodes, {1} events, displacement of up to {2} round(s), seed {3}")
    @MethodSource("scenarios")
    @DisplayName("Releases the same events in the same order as a deduplicator and an orphan buffer")
    void releasesTheSameEventsInTheSameOrder(
            final int nodeCount, final int eventCount, final int displacementRounds, final long seed) {
        final Random random = new Random(seed);
        final List<PlatformEvent> events = GeneratorEventGraphSourceBuilder.builder()
                .seed(seed)
                .numNodes(nodeCount)
                .time(new FakeTime(START, Duration.ofMillis(1)))
                .build()
                .nextEvents(eventCount);
        final long highestGeneratedBirthRound =
                events.stream().mapToLong(PlatformEvent::getBirthRound).max().orElseThrow();
        final double eventsPerRound = (double) eventCount / highestGeneratedBirthRound;
        final List<PlatformEvent> deliveries = shuffledDeliveries(
                events, (int) Math.ceil(displacementRounds * eventsPerRound), eventsPerRound, random);

        final FalconMiniIntake intake = new FalconMiniIntake();
        final Metrics metrics = new NoOpMetrics();
        final IntakeEventCounter intakeEventCounter = new NoOpIntakeEventCounter();
        final EventDeduplicator deduplicator = new StandardEventDeduplicator(metrics, intakeEventCounter);
        final OrphanBuffer orphanBuffer = new DefaultOrphanBuffer(metrics, intakeEventCounter);

        final Set<EventDescriptorWrapper> released = new HashSet<>();
        long ancientThreshold = ConsensusConstants.ROUND_FIRST;
        long highestBirthRound = ConsensusConstants.ROUND_FIRST;
        int windowStep = 1 + random.nextInt(MAX_WINDOW_STEP);
        int deliveriesReleasingOrphans = 0;
        int shiftsReleasingOrphans = 0;
        int shiftsWithSeveralAncientParents = 0;

        for (int i = 0; i < deliveries.size(); i++) {
            final PlatformEvent event = deliveries.get(i);

            // Both sides set sequence numbers on what they release, so each gets a copy of its own
            final PlatformEvent deduplicatedEvent = deduplicator.handleEvent(event.copyGossipedData());
            final List<PlatformEvent> expected =
                    deduplicatedEvent == null ? List.of() : orphanBuffer.handleEvent(deduplicatedEvent);
            final List<PlatformEvent> actual = intake.handleEvent(event.copyGossipedData());
            assertThat(releases(actual)).as("events released by delivery %d", i).isEqualTo(releases(expected));
            expected.forEach(e -> released.add(e.getDescriptor()));
            if (expected.size() > 1) {
                deliveriesReleasingOrphans++;
            }

            highestBirthRound = Math.max(highestBirthRound, event.getBirthRound());
            final long newAncientThreshold = highestBirthRound - NON_ANCIENT_ROUNDS;
            if (newAncientThreshold >= ancientThreshold + windowStep) {
                final EventWindow eventWindow = EventWindowBuilder.builder()
                        .setLatestConsensusRound(newAncientThreshold + NON_ANCIENT_ROUNDS - 1)
                        .setAncientThreshold(newAncientThreshold)
                        .build();
                deduplicator.setEventWindow(eventWindow);
                final List<PlatformEvent> expectedByShift = orphanBuffer.setEventWindow(eventWindow);
                final List<PlatformEvent> actualByShift = intake.setEventWindow(eventWindow);
                assertThat(releases(actualByShift))
                        .as("events released by the window shift to %d after delivery %d", newAncientThreshold, i)
                        .isEqualTo(releases(expectedByShift));

                if (!expectedByShift.isEmpty()) {
                    shiftsReleasingOrphans++;
                }
                if (countParentsBecomingAncient(expectedByShift, released, ancientThreshold, newAncientThreshold) > 1) {
                    shiftsWithSeveralAncientParents++;
                }
                expectedByShift.forEach(e -> released.add(e.getDescriptor()));
                ancientThreshold = newAncientThreshold;
                windowStep = 1 + random.nextInt(MAX_WINDOW_STEP);
            }
        }

        assertThat(deliveriesReleasingOrphans)
                .as("deliveries that released orphans")
                .isPositive();
        assertThat(shiftsReleasingOrphans)
                .as("window shifts that released orphans")
                .isPositive();
        // The order of the orphans of different parents is what depends on how the missing parents are stored
        assertThat(shiftsWithSeveralAncientParents)
                .as("window shifts that released orphans of several parents")
                .isPositive();
    }

    /**
     * Puts the events into the order they are delivered in. Every event moves back by up to {@code maxDisplacement}
     * positions, some events are delivered a second time, and some are never delivered.
     *
     * @param events the events in the order they were created
     * @param maxDisplacement the maximum number of positions an event moves back
     * @param eventsPerRound the average number of events per round
     * @param random the source of randomness
     * @return the events in the order they are delivered
     */
    @NonNull
    static List<PlatformEvent> shuffledDeliveries(
            @NonNull final List<PlatformEvent> events,
            final int maxDisplacement,
            final double eventsPerRound,
            @NonNull final Random random) {
        final double withheldProbability = WITHHELD_EVENTS_PER_ROUND / eventsPerRound;
        final List<Delivery> deliveries = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (random.nextDouble() < withheldProbability) {
                continue;
            }
            final PlatformEvent event = events.get(i);
            deliveries.add(new Delivery(i + random.nextInt(maxDisplacement), event));
            if (random.nextDouble() < DUPLICATE_PROBABILITY) {
                deliveries.add(new Delivery(i + random.nextInt(2 * maxDisplacement), event));
            }
        }
        deliveries.sort(Comparator.comparingInt(Delivery::position));
        return deliveries.stream().map(Delivery::event).toList();
    }

    /**
     * Counts the parents that the events released by a window shift were waiting for and that became ancient in it.
     *
     * @param releasedByShift the events released by the window shift
     * @param released the events released before the window shift
     * @param oldAncientThreshold the ancient threshold before the window shift
     * @param newAncientThreshold the ancient threshold after the window shift
     * @return the number of such parents
     */
    private static int countParentsBecomingAncient(
            @NonNull final List<PlatformEvent> releasedByShift,
            @NonNull final Set<EventDescriptorWrapper> released,
            final long oldAncientThreshold,
            final long newAncientThreshold) {
        final Set<EventDescriptorWrapper> parents = new HashSet<>();
        for (final PlatformEvent event : releasedByShift) {
            for (final EventDescriptorWrapper parent : event.getAllParents()) {
                if (parent.birthRound() >= oldAncientThreshold
                        && parent.birthRound() < newAncientThreshold
                        && !released.contains(parent)) {
                    parents.add(parent);
                }
            }
        }
        return parents.size();
    }

    /**
     * Reduces released events to what the comparison looks at.
     *
     * @param events the released events
     * @return the descriptors and sequence numbers of the events, in order
     */
    @NonNull
    private static List<Release> releases(@NonNull final List<PlatformEvent> events) {
        return events.stream()
                .map(event -> new Release(event.getDescriptor(), event.getSequenceNumber()))
                .toList();
    }
}

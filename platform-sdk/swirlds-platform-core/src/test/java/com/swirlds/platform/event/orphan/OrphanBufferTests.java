// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.orphan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.utility.Mnemonics;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.eventhandling.EventConfig_;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link OrphanBuffer}
 */
class OrphanBufferTests {
    /**
     * Events that will be "received" from intake
     */
    private List<PlatformEvent> intakeEvents;

    /**
     * The maximum generation of any event that has been created
     */
    private long maxGeneration;

    private Random random;

    /**
     * The number of events to be created for testing
     */
    private static final long TEST_EVENT_COUNT = 10_000;
    /**
     * Number of possible nodes in the universe
     */
    private static final int NODE_ID_COUNT = 100;

    /**
     * The average number of generations per round.
     */
    private static final long AVG_GEN_PER_ROUND = 2;

    /**
     * A method that returns 1 to advance a node's birth round to achieve the AVG_GEN_PER_ROUND and taking into account
     * the number of nodes in the network indicated by NODE_ID_COUNT
     */
    private static final Function<Random, Long> maybeAdvanceRound =
            random -> (random.nextLong(0L, AVG_GEN_PER_ROUND * NODE_ID_COUNT) == 0L ? 1L : 0L);

    /**
     * The number of most recently created events to consider when choosing an other parent
     */
    private static final int PARENT_SELECTION_WINDOW = 100;

    /**
     * The maximum amount to advance minimumGenerationNonAncient at a time. Average advancement will be half this.
     */
    private static final int MAX_GENERATION_STEP = 10;

    private AtomicLong eventsExitedIntakePipeline;

    /**
     * Create a random event
     *
     * @param parentCandidates the list of events to choose from when selecting an other parent
     * @param tips             the most recent events from each node
     * @return the random event
     */
    private PlatformEvent createRandomEvent(
            @NonNull final List<PlatformEvent> parentCandidates, @NonNull final Map<NodeId, PlatformEvent> tips) {

        final NodeId eventCreator = NodeId.of(random.nextInt(NODE_ID_COUNT));

        final PlatformEvent selfParent = tips.get(eventCreator);
        final PlatformEvent otherParent = chooseOtherParent(parentCandidates);

        final long maxParentGeneration = Math.max(selfParent.getGeneration(), otherParent.getGeneration());
        final long eventGeneration = maxParentGeneration + 1;
        maxGeneration = Math.max(maxGeneration, eventGeneration);

        return new TestingEventBuilder(random)
                .setCreatorId(eventCreator)
                .setSelfParent(selfParent)
                .setOtherParent(otherParent)
                .build();
    }

    /**
     * Check if an event has been emitted or is ancient
     *
     * @param event       the event to check
     * @param eventWindow the event window defining ancient.
     * @return true if the event has been emitted or is ancient, false otherwise
     */
    private static boolean eventEmittedOrAncient(
            @NonNull final EventDescriptorWrapper event,
            @NonNull final EventWindow eventWindow,
            @NonNull final Collection<Hash> emittedEvents) {

        return emittedEvents.contains(event.hash()) || eventWindow.isAncient(event);
    }

    /**
     * Assert that an event should have been emitted by the orphan buffer, based on its parents being either emitted or
     * ancient.
     *
     * @param event         the event to check
     * @param eventWindow   the event window
     * @param emittedEvents the events that have been emitted so far
     */
    private static void assertValidParents(
            @NonNull final PlatformEvent event,
            @NonNull final EventWindow eventWindow,
            @NonNull final Collection<Hash> emittedEvents) {
        for (final EventDescriptorWrapper parent : event.getAllParents()) {
            assertThat(eventEmittedOrAncient(parent, eventWindow, emittedEvents))
                    .isTrue();
        }
    }

    /**
     * Choose an other parent from the given list of candidates. This method chooses from the last
     * PARENT_SELECTION_WINDOW events in the list.
     *
     * @param parentCandidates the list of candidates
     * @return the chosen other parent
     */
    private PlatformEvent chooseOtherParent(@NonNull final List<PlatformEvent> parentCandidates) {
        final int startIndex = Math.max(0, parentCandidates.size() - PARENT_SELECTION_WINDOW);
        return parentCandidates.get(
                startIndex + random.nextInt(Math.min(PARENT_SELECTION_WINDOW, parentCandidates.size())));
    }

    @BeforeEach
    void setup() {
        random = getRandomPrintSeed();

        final List<PlatformEvent> parentCandidates = new ArrayList<>();
        final Map<NodeId, PlatformEvent> tips = new HashMap<>();

        intakeEvents = new ArrayList<>();

        // Add a bootstrap/genesis event for each node
        for (int i = 0; i < NODE_ID_COUNT; i++) {
            final NodeId nodeId = NodeId.of(i);
            final PlatformEvent bootstrapEvent =
                    new TestingEventBuilder(random).setCreatorId(nodeId).build();

            intakeEvents.add(bootstrapEvent);
            parentCandidates.add(bootstrapEvent);
            tips.put(nodeId, bootstrapEvent);
        }

        // Create events on top of the bootstrap events
        for (long i = 0; i < TEST_EVENT_COUNT - NODE_ID_COUNT; i++) {
            final PlatformEvent newEvent = createRandomEvent(parentCandidates, tips);
            parentCandidates.add(newEvent);
            intakeEvents.add(newEvent);
        }

        Collections.shuffle(intakeEvents, random);

        eventsExitedIntakePipeline = new AtomicLong(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Test standard orphan buffer operation")
    void standardOperation(final boolean useBirthRoundForAncient) {

        final IntakeEventCounter intakeEventCounter = mock(IntakeEventCounter.class);
        doAnswer(invocation -> {
                    eventsExitedIntakePipeline.incrementAndGet();
                    return null;
                })
                .when(intakeEventCounter)
                .eventExitedIntakePipeline(any());
        final DefaultOrphanBuffer orphanBuffer = new DefaultOrphanBuffer(
                TestPlatformContextBuilder.create()
                        .withConfiguration(new TestConfigBuilder()
                                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, useBirthRoundForAncient)
                                .getOrCreateConfig())
                        .build(),
                intakeEventCounter);

        long minimumGenerationNonAncient = 0;
        long latestConsensusRound = ConsensusConstants.ROUND_FIRST;

        // increase minimum generation non-ancient at the approximate rate that event generations are increasing
        // this means that roughly half of the events will be ancient before they are received from intake
        final float averageGenerationAdvancement = (float) maxGeneration / TEST_EVENT_COUNT;

        // events that have been emitted from the orphan buffer
        final Collection<Hash> emittedEventHashes = new HashSet<>();
        final List<PlatformEvent> emittedEvents = new ArrayList<>();

        for (final PlatformEvent intakeEvent : intakeEvents) {

            final List<PlatformEvent> unorphanedEvents = new ArrayList<>(orphanBuffer.handleEvent(intakeEvent));
            assertValidNgen(unorphanedEvents);

            // add some randomness to step size, so minimumGenerationNonAncient doesn't always just increase by 1
            final int stepRandomness = Math.round(random.nextFloat() * MAX_GENERATION_STEP);
            if (random.nextFloat() < averageGenerationAdvancement / stepRandomness) {
                minimumGenerationNonAncient += stepRandomness;
            }
            // simulate advancing consensus rounds periodically
            latestConsensusRound += maybeAdvanceRound.apply(random);
            final AncientMode ancientMode =
                    useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;
            final long ancientThreshold =
                    useBirthRoundForAncient ? Math.max(1, latestConsensusRound - 26 + 1) : minimumGenerationNonAncient;
            final EventWindow eventWindow = new EventWindow(
                    latestConsensusRound, ancientThreshold, 1 /* ignored in this context */, ancientMode);
            unorphanedEvents.addAll(orphanBuffer.setEventWindow(eventWindow));

            for (final PlatformEvent unorphanedEvent : unorphanedEvents) {
                assertValidParents(unorphanedEvent, eventWindow, emittedEventHashes);
                emittedEventHashes.add(unorphanedEvent.getHash());
            }
            emittedEvents.addAll(unorphanedEvents);
        }

        // either events exit the pipeline in the orphan buffer and are never emitted, or they are emitted and exit
        // the pipeline at a later stage
        assertEquals(TEST_EVENT_COUNT, eventsExitedIntakePipeline.get() + emittedEvents.size());
        assertThat(orphanBuffer.getCurrentOrphanCount()).isEqualTo(0);
    }

    private void assertValidNgen(final List<PlatformEvent> unorphanedEvents) {
        for (final PlatformEvent unorphanedEvent : unorphanedEvents) {
            assertThat(unorphanedEvent.getNGen())
                    .withFailMessage(
                            "Invalid nGen value {} assigned to event {}",
                            unorphanedEvent.getNGen(),
                            unorphanedEvent.getHash())
                    .isGreaterThanOrEqualTo(EventConstants.FIRST_GENERATION);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Test that events sorted by nGen result in a valid topological ordering")
    void topologicalOrderByNGen(final boolean useBirthRoundForAncient) {
        final IntakeEventCounter intakeEventCounter = mock(IntakeEventCounter.class);
        final DefaultOrphanBuffer orphanBuffer = new DefaultOrphanBuffer(
                TestPlatformContextBuilder.create()
                        .withConfiguration(new TestConfigBuilder()
                                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, useBirthRoundForAncient)
                                .getOrCreateConfig())
                        .build(),
                intakeEventCounter);

        final List<PlatformEvent> emittedEvents = new ArrayList<>();
        for (final PlatformEvent intakeEvent : intakeEvents) {
            final List<PlatformEvent> unorphanedEvents = new ArrayList<>(orphanBuffer.handleEvent(intakeEvent));
            assertValidNgen(unorphanedEvents);
            emittedEvents.addAll(unorphanedEvents);
        }

        // The orphan buffer should be empty now, since the event window was never shifted and all events were sent.
        assertThat(orphanBuffer.getCurrentOrphanCount()).isEqualTo(0);
        assertThat(emittedEvents.size()).isEqualTo(intakeEvents.size());

        // Verify that when nGen is assigned such that children always have higher values than parents by
        // shuffling the list, then sorting by ngen and checking that parents are always before children.
        Collections.shuffle(emittedEvents, random);
        emittedEvents.sort(Comparator.comparingLong(PlatformEvent::getNGen));

        final Set<Hash> parentHashes = new HashSet<>();
        for (final PlatformEvent event : emittedEvents) {
            if (event.getAllParents().isEmpty()) {
                parentHashes.add(event.getHash());
            } else {
                for (final EventDescriptorWrapper parentDescriptor : event.getAllParents()) {
                    // In this test, the event window is never advanced, so no events are discarded as ancient.
                    // Every event sent to the orphan buffer should have been returned, therefore an event's parents
                    // should always be encountered before the child.
                    assertThat(parentHashes)
                            .withFailMessage(
                                    "Parent event {} was not before the child, indicating that child {} does not have a higher nGen value.",
                                    Mnemonics.generateMnemonic(parentDescriptor.hash()),
                                    Mnemonics.generateMnemonic(event.getHash()))
                            .contains(parentDescriptor.hash());
                }
                parentHashes.add(event.getHash());
            }
        }
    }

    @Test
    @DisplayName("Test All Parents Iterator")
    void testParentIterator() {
        final Random random = Randotron.create();

        final PlatformEvent selfParent =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(0)).build();
        final PlatformEvent otherParent1 =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(1)).build();
        final PlatformEvent otherParent2 =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(2)).build();
        final PlatformEvent otherParent3 =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(3)).build();

        final PlatformEvent event = new TestingEventBuilder(random)
                .setSelfParent(selfParent)
                .setOtherParents(List.of(otherParent1, otherParent2, otherParent3))
                .build();

        final List<EventDescriptorWrapper> otherParents = new ArrayList<>();
        otherParents.add(otherParent1.getDescriptor());
        otherParents.add(otherParent2.getDescriptor());
        otherParents.add(otherParent3.getDescriptor());

        final Iterator<EventDescriptorWrapper> iterator = event.getAllParents().iterator();

        assertThat(iterator.next())
                .withFailMessage("The first parent should be the self parent")
                .isEqualTo(selfParent.getDescriptor());
        int index = 0;
        while (iterator.hasNext()) {
            assertThat(iterator.next())
                    .withFailMessage("The next parent should be the next other parent")
                    .isEqualTo(otherParents.get(index++));
        }
    }

    @DisplayName("Verify the assignment of nGen for genesis events")
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testNGenValueForGenesisEvent(final boolean useBirthRoundForAncient) {
        final PlatformEvent genesisEvent =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(0)).build();

        final DefaultOrphanBuffer orphanBuffer = new DefaultOrphanBuffer(
                TestPlatformContextBuilder.create()
                        .withConfiguration(new TestConfigBuilder()
                                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, useBirthRoundForAncient)
                                .getOrCreateConfig())
                        .build(),
                mock(IntakeEventCounter.class));

        final List<PlatformEvent> unorphanedEvents = orphanBuffer.handleEvent(genesisEvent);
        assertThat(unorphanedEvents.size())
                .withFailMessage("One event was added, and one event should be returned.")
                .isEqualTo(1);
        assertThat(unorphanedEvents.getFirst().getNGen())
                .withFailMessage("nGen for genesis events should be the first generation possible.")
                .isEqualTo(EventConstants.FIRST_GENERATION);
    }

    @DisplayName("Verify the assignment of nGen for events with ancient parents")
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testNGenValueWithAncientParents(final boolean useBirthRoundForAncient) {

        final AncientMode ancientMode =
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;

        final long minimumGenerationNonAncient = 100;
        final long latestConsensusRound = 30;
        final long minimumBirthRoundNonAncient = latestConsensusRound - 26 + 1;
        final long ancientThreshold = ancientMode == AncientMode.BIRTH_ROUND_THRESHOLD
                ? minimumBirthRoundNonAncient
                : minimumGenerationNonAncient;
        final EventWindow eventWindow =
                new EventWindow(latestConsensusRound, ancientThreshold, 1 /* ignored in this context */, ancientMode);

        // Create two ancient events to serve as parents
        final PlatformEvent selfParent =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(0)).build();
        final PlatformEvent otherParent =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(1)).build();

        // Create an event that is non-ancient but whose parents are all ancient.
        // The parent generations must be overridden in order to set the generation of
        // this event to a non-ancient value, whereas the birthround can be set outright.
        final PlatformEvent event = new TestingEventBuilder(random)
                .setSelfParent(selfParent)
                .setOtherParent(otherParent)
                .setBirthRound(minimumBirthRoundNonAncient)
                .overrideOtherParentGeneration(minimumGenerationNonAncient - 1)
                .overrideSelfParentGeneration(minimumGenerationNonAncient - 1)
                .build();

        final DefaultOrphanBuffer orphanBuffer = new DefaultOrphanBuffer(
                TestPlatformContextBuilder.create()
                        .withConfiguration(new TestConfigBuilder()
                                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, useBirthRoundForAncient)
                                .getOrCreateConfig())
                        .build(),
                mock(IntakeEventCounter.class));
        orphanBuffer.setEventWindow(eventWindow);

        final List<PlatformEvent> unorphanedEvents = new ArrayList<>();
        unorphanedEvents.addAll(orphanBuffer.handleEvent(selfParent));
        unorphanedEvents.addAll(orphanBuffer.handleEvent(otherParent));
        unorphanedEvents.addAll(orphanBuffer.handleEvent(event));

        assertThat(unorphanedEvents.size())
                .withFailMessage("One event should be returned by the orphan buffer.")
                .isEqualTo(1);
        assertThat(unorphanedEvents.getFirst().getNGen())
                .withFailMessage(
                        "nGen for events with unknown ancient parents should be the first generation possible.")
                .isEqualTo(EventConstants.FIRST_GENERATION);
    }

    @DisplayName("Verify the assignment of nGen for events one ancient and one non-ancient parent")
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testNGenValueWithAncientAndNonAncientParents(final boolean useBirthRoundForAncient) {

        final AncientMode ancientMode =
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;

        // Pick some values to use. These are arbitrary.
        final long minimumGenerationNonAncient = 100;
        final long latestConsensusRound = 30;
        final long minimumBirthRoundNonAncient = latestConsensusRound - 26 + 1;
        final long ancientThreshold = ancientMode == AncientMode.BIRTH_ROUND_THRESHOLD
                ? minimumBirthRoundNonAncient
                : minimumGenerationNonAncient;
        final EventWindow eventWindow =
                new EventWindow(latestConsensusRound, ancientThreshold, 1 /* ignored in this context */, ancientMode);

        // Genesis event, ancient
        final PlatformEvent node0AncientEvent =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(0)).build();

        // Genesis event, ancient
        final PlatformEvent node1AncientEvent =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(1)).build();

        // A non-ancient event with all ancient parents.
        // The parent generations must be overridden in order to set the generation of
        // this event to a non-ancient value, whereas the birthround can be set outright.
        final PlatformEvent node1NonAncientEvent = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(1))
                .setOtherParent(node0AncientEvent)
                .setSelfParent(node1AncientEvent)
                .setBirthRound(minimumBirthRoundNonAncient)
                .overrideOtherParentGeneration(minimumGenerationNonAncient - 1)
                .overrideSelfParentGeneration(minimumGenerationNonAncient - 1)
                .build();

        // An event that is non-ancient with a barely ancient self-parent and a barely non-ancient other-parent
        final PlatformEvent node0NonAncientEvent = new TestingEventBuilder(random)
                .setSelfParent(node0AncientEvent)
                .setOtherParent(node1NonAncientEvent)
                .setBirthRound(minimumBirthRoundNonAncient)
                .overrideOtherParentGeneration(minimumGenerationNonAncient)
                .overrideSelfParentGeneration(minimumGenerationNonAncient - 1)
                .build();

        final DefaultOrphanBuffer orphanBuffer = new DefaultOrphanBuffer(
                TestPlatformContextBuilder.create()
                        .withConfiguration(new TestConfigBuilder()
                                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, useBirthRoundForAncient)
                                .getOrCreateConfig())
                        .build(),
                mock(IntakeEventCounter.class));
        orphanBuffer.setEventWindow(eventWindow);

        final List<PlatformEvent> unorphanedEvents = new ArrayList<>();

        unorphanedEvents.addAll(orphanBuffer.handleEvent(node0AncientEvent));
        assertThat(unorphanedEvents.isEmpty())
                .withFailMessage("Ancient events should not be returned by the orphan buffer")
                .isTrue();
        assertThat(node0AncientEvent.getNGen())
                .withFailMessage("Ancient events should not be assigned an nGen value")
                .isEqualTo(EventConstants.GENERATION_UNDEFINED);

        unorphanedEvents.addAll(orphanBuffer.handleEvent(node1AncientEvent));
        assertThat(unorphanedEvents.isEmpty())
                .withFailMessage("Ancient events should not be returned by the orphan buffer")
                .isTrue();
        assertThat(node1AncientEvent.getNGen())
                .withFailMessage("Ancient events should not be assigned an nGen value")
                .isEqualTo(EventConstants.GENERATION_UNDEFINED);

        unorphanedEvents.addAll(orphanBuffer.handleEvent(node1NonAncientEvent));
        assertThat(unorphanedEvents.size())
                .withFailMessage("Events with only ancient parents should be returned by the orphan buffer")
                .isEqualTo(1);
        assertThat(node1NonAncientEvent.getNGen())
                .withFailMessage("Events with only ancient parents should have the first possible nGen value")
                .isEqualTo(EventConstants.FIRST_GENERATION);
        unorphanedEvents.clear();

        unorphanedEvents.addAll(orphanBuffer.handleEvent(node0NonAncientEvent));
        assertThat(unorphanedEvents.size())
                .withFailMessage(
                        "Events whose parents are all either ancient or already passed through should be returned by the orphan buffer")
                .isEqualTo(1);
        assertThat(node0NonAncientEvent.getNGen())
                .withFailMessage("Events should have an nGen value 1 higher than all non ancient parents.")
                .isEqualTo(EventConstants.FIRST_GENERATION + 1);
    }

    @DisplayName("Verify the assignment of nGen for events non-ancient parents with different nGen values")
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testNGenValueWithNonAncientParents(final boolean useBirthRoundForAncient) {

        final AncientMode ancientMode =
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;

        // Pick some values to use. These are arbitrary.
        final long minimumGenerationNonAncient = 100;
        final long latestConsensusRound = 30;
        final long minimumBirthRoundNonAncient = latestConsensusRound - 26 + 1;
        final long ancientThreshold = ancientMode == AncientMode.BIRTH_ROUND_THRESHOLD
                ? minimumBirthRoundNonAncient
                : minimumGenerationNonAncient;
        final EventWindow eventWindow =
                new EventWindow(latestConsensusRound, ancientThreshold, 1 /* ignored in this context */, ancientMode);

        // Genesis events, ancient
        final PlatformEvent node0AncientEvent =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(0)).build();
        final PlatformEvent node1AncientEvent =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(1)).build();

        // Non-ancient events with different generation values
        final PlatformEvent node1NonAncientEvent = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(1))
                .setOtherParent(node0AncientEvent)
                .setSelfParent(node1AncientEvent)
                .setBirthRound(minimumBirthRoundNonAncient)
                .overrideOtherParentGeneration(minimumGenerationNonAncient - 1)
                .overrideSelfParentGeneration(minimumGenerationNonAncient - 1)
                .build();
        final PlatformEvent node0NonAncientEvent = new TestingEventBuilder(random)
                .setSelfParent(node0AncientEvent)
                .setOtherParent(node1NonAncientEvent)
                .setBirthRound(minimumBirthRoundNonAncient)
                .build();

        // Non-ancient event whose parents have different generations
        final PlatformEvent node0NonAncientEvent2 = new TestingEventBuilder(random)
                .setSelfParent(node0NonAncientEvent)
                .setOtherParent(node1NonAncientEvent)
                .setBirthRound(minimumBirthRoundNonAncient)
                .build();

        final DefaultOrphanBuffer orphanBuffer = new DefaultOrphanBuffer(
                TestPlatformContextBuilder.create()
                        .withConfiguration(new TestConfigBuilder()
                                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, useBirthRoundForAncient)
                                .getOrCreateConfig())
                        .build(),
                mock(IntakeEventCounter.class));
        orphanBuffer.setEventWindow(eventWindow);

        final List<PlatformEvent> unorphanedEvents = new ArrayList<>(orphanBuffer.handleEvent(node0AncientEvent));
        assertThat(unorphanedEvents.isEmpty())
                .withFailMessage("Ancient events should not be returned by the orphan buffer")
                .isTrue();
        assertThat(node0AncientEvent.getNGen())
                .withFailMessage("Ancient events should not be assigned an nGen value")
                .isEqualTo(EventConstants.GENERATION_UNDEFINED);

        unorphanedEvents.addAll(orphanBuffer.handleEvent(node1AncientEvent));
        assertThat(unorphanedEvents.isEmpty())
                .withFailMessage("Ancient events should not be returned by the orphan buffer")
                .isTrue();
        assertThat(node1AncientEvent.getNGen())
                .withFailMessage("Ancient events should not be assigned an nGen value")
                .isEqualTo(EventConstants.GENERATION_UNDEFINED);

        unorphanedEvents.addAll(orphanBuffer.handleEvent(node1NonAncientEvent));
        assertThat(unorphanedEvents.size())
                .withFailMessage("Events with only ancient parents should be returned by the orphan buffer")
                .isEqualTo(1);
        assertThat(node1NonAncientEvent.getNGen())
                .withFailMessage("Events with only ancient parents should have the first possible nGen value")
                .isEqualTo(EventConstants.FIRST_GENERATION);
        unorphanedEvents.clear();

        unorphanedEvents.addAll(orphanBuffer.handleEvent(node0NonAncientEvent));
        assertThat(unorphanedEvents.size())
                .withFailMessage(
                        "Events whose parents are all either ancient or already passed through should be returned by the orphan buffer")
                .isEqualTo(1);
        assertThat(node0NonAncientEvent.getNGen())
                .withFailMessage("Events should have an nGen value 1 higher than all non ancient parents.")
                .isEqualTo(EventConstants.FIRST_GENERATION + 1);
        unorphanedEvents.clear();

        unorphanedEvents.addAll(orphanBuffer.handleEvent(node0NonAncientEvent2));
        assertThat(unorphanedEvents.size())
                .withFailMessage(
                        "Events whose parents are all either ancient or already passed through should be returned by the orphan buffer")
                .isEqualTo(1);
        assertThat(node0NonAncientEvent2.getNGen())
                .withFailMessage("Events should have an nGen value 1 higher than all non ancient parents.")
                .isEqualTo(EventConstants.FIRST_GENERATION + 2);
    }
}

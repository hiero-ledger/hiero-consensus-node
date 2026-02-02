// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.linking;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import com.swirlds.platform.test.fixtures.graph.SimpleGraph;
import com.swirlds.platform.test.fixtures.graph.SimpleGraphs;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@link ConsensusLinker} class.
 */
class ConsensusLinkerTests {
    private Random random;

    private ConsensusLinker linker;

    private PlatformEvent cr0genesis;
    private PlatformEvent cr1genesis;

    private FakeTime time;

    private final NodeId cr0 = NodeId.of(0);
    private final NodeId cr1 = NodeId.of(1);

    /**
     * Set up the in order linker for testing
     * <p>
     * This method creates 2 genesis events and submits them to the linker, as a foundation for the tests.
     */
    @BeforeEach
    void setup() {
        random = getRandomPrintSeed();
        time = new FakeTime();
    }

    private void inOrderLinkerSetup() {
        linker = new ConsensusLinker(NoOpLinkerLogsAndMetrics.getInstance());

        time.tick(Duration.ofSeconds(1));
        cr0genesis = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setBirthRound(ROUND_FIRST)
                .setTimeCreated(time.now())
                .build();

        linker.linkEvent(cr0genesis);

        time.tick(Duration.ofSeconds(1));
        cr1genesis = new TestingEventBuilder(random)
                .setCreatorId(cr1)
                .setBirthRound(ROUND_FIRST)
                .setTimeCreated(time.now())
                .build();

        linker.linkEvent(cr1genesis);

        time.tick(Duration.ofSeconds(1));
    }

    /**
     * Choose an event window that will cause all given events to be considered ancient.
     *
     * @param ancientEvents the events that will be considered ancient
     * @return the event window that will cause the given events to be considered ancient
     */
    private static EventWindow chooseEventWindow(final PlatformEvent... ancientEvents) {

        long ancientValue = 0;
        for (final PlatformEvent ancientEvent : ancientEvents) {
            ancientValue = Math.max(ancientValue, ancientEvent.getBirthRound());
        }

        final EventWindow eventWindow = EventWindowBuilder.builder()
                /* one more than the ancient value, so that the events are ancient */
                .setAncientThreshold(ancientValue + 1)
                .build();

        for (final PlatformEvent ancientEvent : ancientEvents) {
            assertTrue(eventWindow.isAncient(ancientEvent));
        }

        return eventWindow;
    }

    @Test
    @DisplayName("Test standard operation of the in order linker")
    void standardOperation() {
        inOrderLinkerSetup();

        // In the following test events are created with increasing birth round numbers.
        // The linking should fail to occur based on the advancing event window.
        // The values used for birthRound are just for this test and do not reflect real world values.

        final PlatformEvent cr0br2 = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setSelfParent(cr0genesis)
                .setOtherParent(cr1genesis)
                .setBirthRound(cr0genesis.getBirthRound() + 1)
                .build();

        assertParents(linker.linkEvent(cr0br2), cr0genesis, cr1genesis);

        // cause genesis events to become ancient
        EventWindow eventWindow = chooseEventWindow(cr1genesis);
        assertFalse(eventWindow.isAncient(cr0br2));
        linker.setEventWindow(eventWindow);

        final PlatformEvent cr0br3 = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setSelfParent(cr0br2)
                .setOtherParent(cr1genesis)
                .setBirthRound(cr0br2.getBirthRound() + 1)
                .build();

        assertParents(linker.linkEvent(cr0br3), cr0br2, null);

        // create an OP to be used later
        final PlatformEvent cr1br4 = new TestingEventBuilder(random)
                .setCreatorId(cr1)
                .setSelfParent(cr1genesis)
                .setBirthRound(cr0br3.getBirthRound() + 1)
                .build();
        assertParents(linker.linkEvent(cr1br4), null, null);

        // cause cr0br2 to become ancient
        eventWindow = chooseEventWindow(cr0br2);
        assertFalse(eventWindow.isAncient(cr0br3));
        linker.setEventWindow(eventWindow);

        final PlatformEvent cr0br4 = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setSelfParent(cr0br2)
                .setOtherParent(cr1br4)
                .setBirthRound(cr1br4.getBirthRound() + 1)
                .build();

        assertParents(linker.linkEvent(cr0br4), null, cr1br4);

        // make both parents ancient.
        eventWindow = chooseEventWindow(cr0br3, cr0br4);
        linker.setEventWindow(eventWindow);

        final PlatformEvent cr0br5 = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setSelfParent(cr0br3)
                .setOtherParent(cr0br4)
                .setBirthRound(cr0br4.getBirthRound() + 1)
                .build();

        assertParents(linker.linkEvent(cr0br5), null, null);
    }

    @Test
    @DisplayName("Tests linking with multiple other parents")
    void multipleOtherParents() {
        inOrderLinkerSetup();

        final SimpleGraph graph = SimpleGraphs.mopGraph(random);
        assertParentsMop(linker.linkEvent(graph.event(0)), null, List.of());
        assertParentsMop(linker.linkEvent(graph.event(1)), null, List.of());
        assertParentsMop(linker.linkEvent(graph.event(2)), null, List.of());
        assertParentsMop(linker.linkEvent(graph.event(3)), null, List.of());
        assertParentsMop(linker.linkEvent(graph.event(4)), graph.event(0), List.of());
        assertParentsMop(linker.linkEvent(graph.event(5)), graph.event(1), graph.events(0, 2));
        assertParentsMop(linker.linkEvent(graph.event(6)), graph.event(2), graph.events(1, 3));
        assertParentsMop(linker.linkEvent(graph.event(7)), graph.event(3), List.of());
        assertParentsMop(linker.linkEvent(graph.event(8)), graph.event(4), List.of());
        assertParentsMop(linker.linkEvent(graph.event(9)), graph.event(5), graph.events(4, 6));
        assertParentsMop(linker.linkEvent(graph.event(10)), graph.event(6), graph.events(5, 7));
        assertParentsMop(linker.linkEvent(graph.event(11)), graph.event(7), List.of());
    }

    @Test
    @DisplayName("Missing self parent should not be linked")
    void missingSelfParent() {
        inOrderLinkerSetup();

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setOtherParent(cr1genesis)
                .setTimeCreated(time.now())
                .build();

        assertParents(linker.linkEvent(child), null, cr1genesis);
    }

    @Test
    @DisplayName("Missing other parent should not be linked")
    void missingOtherParent() {
        inOrderLinkerSetup();

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setSelfParent(cr0genesis)
                .setTimeCreated(time.now())
                .build();

        assertParents(linker.linkEvent(child), cr0genesis, null);
    }

    @Test
    @DisplayName("Ancient events should not be linked")
    void ancientEvent() {
        inOrderLinkerSetup();

        linker.setEventWindow(
                EventWindowBuilder.builder().setAncientThreshold(3).build());

        final PlatformEvent child1 = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setSelfParent(cr0genesis)
                .setOtherParent(cr1genesis)
                .setBirthRound(1)
                .setTimeCreated(time.now())
                .build();

        time.tick(Duration.ofSeconds(1));

        assertNull(linker.linkEvent(child1));

        final PlatformEvent child2 = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setSelfParent(child1)
                .setOtherParent(cr1genesis)
                .setBirthRound(2)
                .setTimeCreated(time.now())
                .build();

        assertNull(linker.linkEvent(child2));
    }

    @Test
    @DisplayName("Self parent with mismatched birth round should not be linked")
    void selfParentBirthRoundMismatch() {
        inOrderLinkerSetup();

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setSelfParent(cr0genesis)
                .setOtherParent(cr1genesis)
                .overrideSelfParentBirthRound(cr0genesis.getBirthRound() + 1) // birth round doesn't match actual
                .build();

        assertParents(linker.linkEvent(child), null, cr1genesis);
    }

    @Test
    @DisplayName("Other parent with mismatched birth round should not be linked")
    void otherParentBirthRoundMismatch() {
        inOrderLinkerSetup();
        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setSelfParent(cr0genesis)
                .setOtherParent(cr1genesis)
                .overrideOtherParentBirthRound(cr1genesis.getBirthRound() + 1) // birth round doesn't match actual
                .build();
        assertParents(linker.linkEvent(child), cr0genesis, null);
    }

    @Test
    @DisplayName("Self parent with mismatched time created should not be linked")
    void selfParentTimeCreatedMismatch() {
        inOrderLinkerSetup();

        final PlatformEvent lateParent = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setSelfParent(cr0genesis)
                .setOtherParent(cr1genesis)
                .setTimeCreated(time.now().plus(Duration.ofSeconds(10)))
                .build();

        linker.linkEvent(lateParent);

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setSelfParent(lateParent)
                .setOtherParent(cr1genesis)
                .setTimeCreated(time.now())
                .build();

        assertParents(linker.linkEvent(child), null, cr1genesis);
    }

    @Test
    @DisplayName("Other parent with mismatched time created SHOULD be linked")
    void otherParentTimeCreatedMismatch() {
        inOrderLinkerSetup();
        final PlatformEvent lateParent = new TestingEventBuilder(random)
                .setCreatorId(cr1)
                .setSelfParent(cr1genesis)
                .setOtherParent(cr0genesis)
                .setTimeCreated(time.now().plus(Duration.ofSeconds(10)))
                .build();

        linker.linkEvent(lateParent);

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(cr0)
                .setSelfParent(cr0genesis)
                .setOtherParent(lateParent)
                .setTimeCreated(time.now())
                .build();

        assertParents(linker.linkEvent(child), cr0genesis, lateParent);
    }

    @Test
    void eventsAreUnlinkedTest() {
        final Random random = getRandomPrintSeed();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        final StandardGraphGenerator generator = new StandardGraphGenerator(
                platformContext,
                random.nextLong(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource());

        final List<EventImpl> linkedEvents = new LinkedList<>();
        final ConsensusLinker linker = new ConsensusLinker(NoOpLinkerLogsAndMetrics.getInstance());

        EventWindow eventWindow = EventWindow.getGenesisEventWindow();

        for (int i = 0; i < 10_000; i++) {

            final PlatformEvent event = generator.generateEvent();

            // Verify correct behavior when added to the linker.

            if (eventWindow.isAncient(event)) {
                // Event is ancient before we add it and should be discarded.
                assertNull(linker.linkEvent(event));
            } else {
                // Event is currently non-ancient. Verify that it is properly linked.

                final EventImpl linkedEvent = linker.linkEvent(event);
                assertNotNull(linkedEvent);
                linkedEvents.add(linkedEvent);
                assertSame(event, linkedEvent.getBaseEvent());

                final Set<Hash> linkedParents = linkedEvent.getAllParents().stream()
                        .map(EventImpl::getBaseHash)
                        .collect(Collectors.toSet());
                for (final EventDescriptorWrapper parent : event.getAllParents()) {
                    if (eventWindow.isAncient(parent)) {
                        // Ancient parents should not be linked.
                        assertFalse(linkedParents.contains(parent.hash()));
                    } else {
                        // Non-ancient parents should be linked.
                        assertTrue(linkedParents.contains(parent.hash()));
                    }
                }
            }

            // Once in a while, advance the ancient window so that the most recent event is barely non-ancient.
            if (random.nextDouble() < 0.01) {
                if (event.getBirthRound() <= eventWindow.ancientThreshold()) {
                    // Advancing the window any further would make the most recent event ancient. Skip.
                    continue;
                }

                eventWindow = EventWindowBuilder.builder()
                        .setAncientThreshold(event.getBirthRound())
                        .build();
                linker.setEventWindow(eventWindow);

                // All ancient events should have their parents nulled out
                final Iterator<EventImpl> iterator = linkedEvents.iterator();
                while (iterator.hasNext()) {
                    final EventImpl linkedEvent = iterator.next();
                    if (eventWindow.isAncient(linkedEvent.getBaseEvent())) {
                        assertNull(linkedEvent.getSelfParent());
                        assertTrue(linkedEvent.getOtherParents().isEmpty());
                        assertTrue(linkedEvent.getAllParents().isEmpty());
                        iterator.remove();
                    }
                }
            }
        }
    }

    /**
     * Asserts that the given event has the expected parents linked. This method assumes there is at most one other
     * parent.
     *
     * @param toAssert            the event to assert the parents of
     * @param expectedSelfParent  the expected self parent
     * @param expectedOtherParent the expected other parent
     */
    private static void assertParents(
            @Nullable final EventImpl toAssert,
            @Nullable final PlatformEvent expectedSelfParent,
            @Nullable final PlatformEvent expectedOtherParent) {
        assertParentsMop(
                toAssert, expectedSelfParent, expectedOtherParent == null ? List.of() : List.of(expectedOtherParent));
    }

    /**
     * Asserts that the given event has the expected parents linked. This method supports multiple other parents.
     *
     * @param toAssert             the event to assert the parents of
     * @param expectedSelfParent   the expected self parent
     * @param expectedOtherParents the expected other parents
     */
    private static void assertParentsMop(
            @Nullable final EventImpl toAssert,
            @Nullable final PlatformEvent expectedSelfParent,
            @NonNull final List<PlatformEvent> expectedOtherParents) {
        assertNotNull(toAssert, "The linker event should not be null");
        if (expectedSelfParent == null) {
            assertNull(toAssert.getSelfParent(), "Self parent should be null");
        } else {
            assertNotNull(toAssert.getSelfParent(), "Self parent should not be null");
            assertSame(expectedSelfParent, toAssert.getSelfParent().getBaseEvent(), "Self parent does not match");
        }
        if (expectedOtherParents.isEmpty()) {
            assertEquals(0, toAssert.getOtherParents().size(), "Other parents list should be empty");
        } else {
            assertEquals(
                    expectedOtherParents.size(),
                    toAssert.getOtherParents().size(),
                    "Other parents list should be same size");
            for (int i = 0; i < expectedOtherParents.size(); i++) {
                assertSame(
                        expectedOtherParents.get(i),
                        toAssert.getOtherParents().get(i).getBaseEvent(),
                        "Other parent at index " + i + " does not match");
            }
        }
        for (final EventImpl otherParent : toAssert.getOtherParents()) {
            assertNotNull(otherParent, "The list of other-parents should not contain nulls");
        }
        assertEquals(
                expectedOtherParents,
                toAssert.getOtherParents().stream().map(EventImpl::getBaseEvent).toList(),
                "Other parents list does not match");
        for (final EventImpl otherParent : toAssert.getAllParents()) {
            assertNotNull(otherParent, "The list of all parents should not contain nulls");
        }
        assertEquals(
                Stream.concat(Stream.of(expectedSelfParent), expectedOtherParents.stream())
                        .filter(Objects::nonNull)
                        .toList(),
                toAssert.getAllParents().stream().map(EventImpl::getBaseEvent).toList(),
                "All parents list does not match");
    }
}

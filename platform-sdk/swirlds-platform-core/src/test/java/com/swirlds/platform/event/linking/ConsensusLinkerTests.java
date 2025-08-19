// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.linking;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
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

    private PlatformEvent genesisSelfParent;
    private PlatformEvent genesisOtherParent;

    private FakeTime time;

    private final NodeId selfId = NodeId.of(0);
    private final NodeId otherId = NodeId.of(1);

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
        linker = new ConsensusLinker(
                TestPlatformContextBuilder.create().withTime(time).build());

        time.tick(Duration.ofSeconds(1));
        genesisSelfParent = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setBirthRound(ROUND_FIRST)
                .setTimeCreated(time.now())
                .build();

        linker.linkEvent(genesisSelfParent);

        time.tick(Duration.ofSeconds(1));
        genesisOtherParent = new TestingEventBuilder(random)
                .setCreatorId(otherId)
                .setBirthRound(ROUND_FIRST)
                .setTimeCreated(time.now())
                .build();

        linker.linkEvent(genesisOtherParent);

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

        // In the following test events are created with increasing generation and birth round numbers.
        // The linking should fail to occur based on the advancing event window.
        // The values used for birthRound and generation are just for this test and do not reflect real world values.

        final PlatformEvent child1 = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(genesisOtherParent)
                .setBirthRound(genesisSelfParent.getBirthRound() + 1)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent1 = linker.linkEvent(child1);
        assertNotEquals(null, linkedEvent1);
        assertNotEquals(null, linkedEvent1.getSelfParent(), "Self parent is non-ancient, and should not be null");
        assertNotEquals(null, linkedEvent1.getOtherParent(), "Other parent is non-ancient, and should not be null");

        time.tick(Duration.ofSeconds(1));

        // cause genesisOtherParent to become ancient
        EventWindow eventWindow = chooseEventWindow(genesisOtherParent);
        assertFalse(eventWindow.isAncient(child1));
        linker.setEventWindow(eventWindow);

        final PlatformEvent child2 = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(child1)
                .setOtherParent(genesisOtherParent)
                .setBirthRound(child1.getBirthRound() + 1)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent2 = linker.linkEvent(child2);
        assertNotEquals(null, linkedEvent2);
        assertNotEquals(null, linkedEvent2.getSelfParent(), "Self parent is non-ancient, and should not be null");
        assertNull(linkedEvent2.getOtherParent(), "Other parent is ancient, and should be null");

        time.tick(Duration.ofSeconds(1));

        // cause child1 to become ancient
        eventWindow = chooseEventWindow(child1);
        assertFalse(eventWindow.isAncient(child2));
        linker.setEventWindow(eventWindow);

        final PlatformEvent child3 = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(child1)
                .setOtherParent(child2)
                .setBirthRound(child2.getBirthRound() + 1)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent3 = linker.linkEvent(child3);
        assertNotEquals(null, linkedEvent3);
        assertNull(linkedEvent3.getSelfParent(), "Self parent is ancient, and should be null");
        assertNotEquals(null, linkedEvent3.getOtherParent(), "Other parent is non-ancient, and should not be null");

        time.tick(Duration.ofSeconds(1));
        // make both parents ancient.
        eventWindow = chooseEventWindow(child2, child3);
        linker.setEventWindow(eventWindow);

        final PlatformEvent child4 = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(child2)
                .setOtherParent(child3)
                .setBirthRound(child3.getBirthRound() + 1)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent4 = linker.linkEvent(child4);
        assertNotEquals(null, linkedEvent4);
        assertNull(linkedEvent4.getSelfParent(), "Self parent is ancient, and should be null");
        assertNull(linkedEvent4.getOtherParent(), "Other parent is ancient, and should be null");
    }

    @Test
    @DisplayName("Missing self parent should not be linked")
    void missingSelfParent() {
        inOrderLinkerSetup();

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setOtherParent(genesisOtherParent)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent = linker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNull(linkedEvent.getSelfParent(), "Self parent is missing, and should be null");
        assertNotEquals(null, linkedEvent.getOtherParent(), "Other parent is not missing, and should not be null");
    }

    @Test
    @DisplayName("Missing other parent should not be linked")
    void missingOtherParent() {
        inOrderLinkerSetup();

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent = linker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNotEquals(null, linkedEvent.getSelfParent(), "Self parent is not missing, and should not be null");
        assertNull(linkedEvent.getOtherParent(), "Other parent is missing, and should be null");
    }

    @Test
    @DisplayName("Ancient events should not be linked")
    void ancientEvent() {
        inOrderLinkerSetup();

        linker.setEventWindow(
                EventWindowBuilder.builder().setAncientThreshold(3).build());

        final PlatformEvent child1 = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(genesisOtherParent)
                .setBirthRound(1)
                .setTimeCreated(time.now())
                .build();

        time.tick(Duration.ofSeconds(1));

        assertNull(linker.linkEvent(child1));

        final PlatformEvent child2 = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(child1)
                .setOtherParent(genesisOtherParent)
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
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(genesisOtherParent)
                .overrideSelfParentBirthRound(genesisSelfParent.getBirthRound() + 1) // birth round doesn't match actual
                .build();

        final EventImpl linkedEvent = linker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNull(linkedEvent.getSelfParent(), "Self parent has mismatched birth round, and should be null");
        assertNotEquals(null, linkedEvent.getOtherParent(), "Other parent should not be null");
    }

    @Test
    @DisplayName("Other parent with mismatched birth round should not be linked")
    void otherParentBirthRoundMismatch() {
        inOrderLinkerSetup();
        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(genesisOtherParent)
                .overrideOtherParentBirthRound(
                        genesisOtherParent.getBirthRound() + 1) // birth round doesn't match actual
                .build();

        final EventImpl linkedEvent = linker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNotEquals(null, linkedEvent.getSelfParent(), "Self parent should not be null");
        assertNull(linkedEvent.getOtherParent(), "Other parent has mismatched birth round, and should be null");
    }

    @Test
    @DisplayName("Self parent with mismatched time created should not be linked")
    void selfParentTimeCreatedMismatch() {
        inOrderLinkerSetup();

        final PlatformEvent lateParent = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(genesisOtherParent)
                .setTimeCreated(time.now().plus(Duration.ofSeconds(10)))
                .build();

        linker.linkEvent(lateParent);

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(lateParent)
                .setOtherParent(genesisOtherParent)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent = linker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNull(linkedEvent.getSelfParent(), "Self parent has mismatched time created, and should be null");
        assertNotEquals(null, linkedEvent.getOtherParent(), "Other parent should not be null");
    }

    @Test
    @DisplayName("Other parent with mismatched time created SHOULD be linked")
    void otherParentTimeCreatedMismatch() {
        inOrderLinkerSetup();
        final PlatformEvent lateParent = new TestingEventBuilder(random)
                .setCreatorId(otherId)
                .setSelfParent(genesisOtherParent)
                .setOtherParent(genesisSelfParent)
                .setTimeCreated(time.now().plus(Duration.ofSeconds(10)))
                .build();

        linker.linkEvent(lateParent);

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(lateParent)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent = linker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNotEquals(null, linkedEvent.getSelfParent(), "Self parent should not be null");
        assertNotEquals(null, linkedEvent.getOtherParent(), "Other parent should not be null");
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
        final ConsensusLinker linker = new ConsensusLinker(platformContext);

        EventWindow eventWindow = EventWindow.getGenesisEventWindow();

        for (int i = 0; i < 10_000; i++) {

            final PlatformEvent event = generator.generateEvent().getBaseEvent();

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

                final EventDescriptorWrapper selfParent = event.getSelfParent();
                if (selfParent == null || eventWindow.isAncient(selfParent)) {
                    assertNull(linkedEvent.getSelfParent());
                } else {
                    assertNotNull(linkedEvent.getSelfParent());
                    assertEquals(
                            event.getSelfParent(),
                            linkedEvent.getSelfParent().getBaseEvent().getDescriptor());
                }

                final List<EventDescriptorWrapper> otherParents = event.getOtherParents();
                if (otherParents.isEmpty()) {
                    assertNull(linkedEvent.getOtherParent());
                } else {
                    final EventDescriptorWrapper otherParent = otherParents.getFirst();
                    if (eventWindow.isAncient(otherParent)) {
                        assertNull(linkedEvent.getOtherParent());
                    } else {
                        assertNotNull(linkedEvent.getOtherParent());
                        assertEquals(
                                otherParents.getFirst(),
                                linkedEvent.getOtherParent().getBaseEvent().getDescriptor());
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
                        assertNull(linkedEvent.getOtherParent());
                        iterator.remove();
                    }
                }
            }
        }
    }
}

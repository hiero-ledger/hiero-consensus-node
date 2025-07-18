// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.linking;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@link ConsensusLinker} class. Aside from metrics, the only difference between an {@link InOrderLinker} and
 * a {@link ConsensusLinker} is that the consensus linker also unlinks events as they become ancient.
 */
class ConsensusEventLinkerTests {

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
        final InOrderLinker linker = new ConsensusLinker(platformContext);

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

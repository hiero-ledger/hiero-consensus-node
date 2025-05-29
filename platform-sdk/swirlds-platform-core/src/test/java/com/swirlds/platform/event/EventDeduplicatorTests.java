// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import static com.swirlds.platform.test.fixtures.event.EventUtils.serializePlatformEvent;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomSignatureBytes;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.event.deduplication.EventDeduplicator;
import com.swirlds.platform.event.deduplication.StandardEventDeduplicator;
import com.swirlds.platform.gossip.IntakeEventCounter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@link EventDeduplicator} class
 */
class EventDeduplicatorTests {
    private Random random;

    /**
     * Number of possible nodes in the universe
     */
    private static final int NODE_ID_COUNT = 100;

    /**
     * The number of events to be created for testing
     */
    private static final long TEST_EVENT_COUNT = 1000;

    @BeforeEach
    void setup() {
        random = getRandomPrintSeed();
    }

    /**
     * Create a test platform event
     *
     * @param creatorId  the creator of the event
     * @param birthRound the birth round of the event
     * @return the mocked platform event
     */
    private PlatformEvent createPlatformEvent(@NonNull final NodeId creatorId, final long birthRound) {

        final PlatformEvent selfParent = new TestingEventBuilder(random)
                .setCreatorId(creatorId)
                .setBirthRound(birthRound - 1)
                .build();

        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(creatorId)
                .setBirthRound(birthRound)
                .setSelfParent(selfParent)
                .build();

        return event;
    }

    private static void validateEmittedEvent(
            @Nullable final PlatformEvent event,
            final long minimumRoundNonAncient,
            @NonNull final Set<ByteBuffer> emittedEvents) {
        if (event != null) {
            assertFalse(
                    event.getDescriptor().eventDescriptor().birthRound() < minimumRoundNonAncient,
                    "Ancient events shouldn't be emitted");
            assertTrue(emittedEvents.add(ByteBuffer.wrap(serializePlatformEvent(event))), "Event was emitted twice");
        }
    }

    @Test
    @DisplayName("Test standard event deduplicator operation")
    void standardOperation() {
        long minimumRoundNonAncient = ConsensusConstants.ROUND_FIRST;

        // events that have been emitted from the deduplicator
        // contents of the set are the serialized events
        final Set<ByteBuffer> emittedEvents = new HashSet<>();

        // events that have been submitted to the deduplicator
        final List<PlatformEvent> submittedEvents = new ArrayList<>();

        final AtomicLong eventsExitedIntakePipeline = new AtomicLong(0);
        final IntakeEventCounter intakeEventCounter = mock(IntakeEventCounter.class);
        doAnswer(invocation -> {
                    eventsExitedIntakePipeline.incrementAndGet();
                    return null;
                })
                .when(intakeEventCounter)
                .eventExitedIntakePipeline(any());

        final EventDeduplicator deduplicator = new StandardEventDeduplicator(
                TestPlatformContextBuilder.create().build(), intakeEventCounter);

        int duplicateEventCount = 0;
        int ancientEventCount = 0;

        for (int i = 0; i < TEST_EVENT_COUNT; i++) {
            if (submittedEvents.isEmpty() || random.nextBoolean()) {
                // submit a brand new event half the time
                final NodeId creatorId = NodeId.of(random.nextInt(NODE_ID_COUNT));
                final long eventBirthRound =
                        Math.max(ConsensusConstants.ROUND_FIRST, minimumRoundNonAncient + random.nextLong(-1, 4));

                if (eventBirthRound < minimumRoundNonAncient) {
                    ancientEventCount++;
                }

                final PlatformEvent newEvent = createPlatformEvent(creatorId, eventBirthRound);

                validateEmittedEvent(deduplicator.handleEvent(newEvent), minimumRoundNonAncient, emittedEvents);

                submittedEvents.add(newEvent);
            } else if (random.nextBoolean()) {
                // submit a duplicate event 25% of the time
                duplicateEventCount++;

                validateEmittedEvent(
                        deduplicator.handleEvent(submittedEvents.get(random.nextInt(submittedEvents.size()))),
                        minimumRoundNonAncient,
                        emittedEvents);
            } else {
                // submit a duplicate event with a different signature 25% of the time
                final PlatformEvent platformEvent = submittedEvents.get(random.nextInt(submittedEvents.size()));
                final PlatformEvent duplicateEvent = new PlatformEvent(new GossipEvent.Builder()
                        .eventCore(platformEvent.getGossipEvent().eventCore())
                        .signature(randomSignatureBytes(random)) // randomize the signature
                        .transactions(platformEvent.getGossipEvent().transactions())
                        .build());
                duplicateEvent.setHash(platformEvent.getHash());

                if (duplicateEvent.getDescriptor().eventDescriptor().birthRound() < minimumRoundNonAncient) {
                    ancientEventCount++;
                }

                validateEmittedEvent(deduplicator.handleEvent(duplicateEvent), minimumRoundNonAncient, emittedEvents);
            }

            if (random.nextBoolean()) {
                minimumRoundNonAncient++;
                deduplicator.setEventWindow(EventWindowBuilder.builder()
                        .setAncientThreshold(minimumRoundNonAncient)
                        .build());
            }
        }

        assertEquals(TEST_EVENT_COUNT, eventsExitedIntakePipeline.get() + emittedEvents.size());
        assertEquals(TEST_EVENT_COUNT, emittedEvents.size() + ancientEventCount + duplicateEventCount);
    }
}

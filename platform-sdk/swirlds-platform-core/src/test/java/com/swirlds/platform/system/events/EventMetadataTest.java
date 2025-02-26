// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.events;

import static com.swirlds.common.test.fixtures.RandomUtils.getRandomPrintSeed;
import static com.swirlds.common.test.fixtures.RandomUtils.randomHash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.platform.event.EventDescriptor;
import com.swirlds.common.platform.NodeId;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EventMetadataTest {

    @Test
    void testBirthRoundOverride() {
        final Random random = getRandomPrintSeed(0);
        final EventDescriptorWrapper baseSelfParent = new EventDescriptorWrapper(
                new EventDescriptor(randomHash(random).getBytes(), 0, 1, 100));
        final EventDescriptorWrapper baseOtherParent = new EventDescriptorWrapper(
                new EventDescriptor(randomHash(random).getBytes(), 1, 1, 100));
        final EventMetadata metadata =
                new EventMetadata(NodeId.of(0), baseSelfParent, List.of(baseOtherParent), Instant.now(), List.of(), 1);

        // validate that everything works as expected before the birth round override
        {
            final EventDescriptorWrapper selfParent = metadata.getSelfParent();
            assertNotNull(selfParent);
            assertEquals(1, selfParent.eventDescriptor().birthRound());
            assertEquals(0, selfParent.eventDescriptor().creatorNodeId());
            assertEquals(100, selfParent.eventDescriptor().generation());

            final List<EventDescriptorWrapper> otherParents = metadata.getOtherParents();
            assertEquals(1, otherParents.size());
            final EventDescriptorWrapper otherParent = otherParents.getFirst();
            assertNotNull(otherParent);
            assertEquals(1, otherParent.eventDescriptor().birthRound());
            assertEquals(1, otherParent.eventDescriptor().creatorNodeId());
            assertEquals(100, otherParent.eventDescriptor().generation());
        }

        // override the birth round
        metadata.setBirthRoundOverride(10);

        // validate that the birth round has been overridden with all other properties unchanged
        {
            final EventDescriptorWrapper selfParent = metadata.getSelfParent();
            assertNotNull(selfParent);
            assertEquals(10, selfParent.eventDescriptor().birthRound());
            assertEquals(0, selfParent.eventDescriptor().creatorNodeId());
            assertEquals(100, selfParent.eventDescriptor().generation());

            final List<EventDescriptorWrapper> otherParents = metadata.getOtherParents();
            assertEquals(1, otherParents.size());
            final EventDescriptorWrapper otherParent = otherParents.getFirst();
            assertNotNull(otherParent);
            assertEquals(10, otherParent.eventDescriptor().birthRound());
            assertEquals(1, otherParent.eventDescriptor().creatorNodeId());
            assertEquals(100, otherParent.eventDescriptor().generation());
        }
    }

    @Test
    void testMultipleBirthRoundOverrides() {
        final Random random = getRandomPrintSeed(0);
        final EventDescriptorWrapper baseSelfParent = new EventDescriptorWrapper(
                new EventDescriptor(randomHash(random).getBytes(), 0, 1, 100));
        final EventDescriptorWrapper baseOtherParent = new EventDescriptorWrapper(
                new EventDescriptor(randomHash(random).getBytes(), 1, 1, 100));
        final EventMetadata metadata =
                new EventMetadata(NodeId.of(0), baseSelfParent, List.of(baseOtherParent), Instant.now(), List.of(), 1);

        metadata.setBirthRoundOverride(150);

        // trying to override it again should fail
        assertThrows(IllegalStateException.class, () -> metadata.setBirthRoundOverride(200));

        // validate that the birth round has been overridden with the first call only
        {
            final EventDescriptorWrapper selfParent = metadata.getSelfParent();
            assertNotNull(selfParent);
            assertEquals(150, selfParent.eventDescriptor().birthRound());
            assertEquals(0, selfParent.eventDescriptor().creatorNodeId());
            assertEquals(100, selfParent.eventDescriptor().generation());

            final List<EventDescriptorWrapper> otherParents = metadata.getOtherParents();
            assertEquals(1, otherParents.size());
            final EventDescriptorWrapper otherParent = otherParents.getFirst();
            assertNotNull(otherParent);
            assertEquals(150, otherParent.eventDescriptor().birthRound());
            assertEquals(1, otherParent.eventDescriptor().creatorNodeId());
            assertEquals(100, otherParent.eventDescriptor().generation());
        }
    }
}

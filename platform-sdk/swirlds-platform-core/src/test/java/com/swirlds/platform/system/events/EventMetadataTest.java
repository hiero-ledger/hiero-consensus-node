// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.events;

import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHash;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.platform.event.EventDescriptor;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.EventMetadata;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

class EventMetadataTest {

    @Test
    void testBirthRoundOverride() {
        final Random random = getRandomPrintSeed(0);
        final EventDescriptorWrapper selfParent = new EventDescriptorWrapper(
                new EventDescriptor(randomHash(random).getBytes(), 0, 1, 100));
        final EventDescriptorWrapper otherParent = new EventDescriptorWrapper(
                new EventDescriptor(randomHash(random).getBytes(), 1, 1, 100));
        final EventMetadata metadata =
                new EventMetadata(NodeId.of(0), selfParent, List.of(otherParent), Instant.now(), List.of(), 1);

        // validate that everything works as expected before the birth round override
        verifyEvent(metadata, 0, 101, 1);
        verifyEvent(metadata.getSelfParent(), 0, 100, 1);
        final List<EventDescriptorWrapper> otherParentsBeforeOverride = metadata.getOtherParents();
        assertEquals(1, otherParentsBeforeOverride.size());
        verifyEvent(otherParentsBeforeOverride.getFirst(), 1, 100, 1);

        // override the birth round
        final long newBirthRound = 100L;
        metadata.setBirthRoundOverride(newBirthRound, 100);

        // validate that the birth round has been overridden with all other properties unchanged
        verifyEvent(metadata, 0, 101, newBirthRound);
        verifyEvent(metadata.getSelfParent(), 0, 100, newBirthRound);
        final List<EventDescriptorWrapper> otherParentsAfterOverride = metadata.getOtherParents();
        assertEquals(1, otherParentsAfterOverride.size());
        verifyEvent(otherParentsAfterOverride.getFirst(), 1, 100, newBirthRound);
    }

    @Test
    void testMultipleBirthRoundOverrides() {
        final Random random = getRandomPrintSeed(0);
        final EventDescriptorWrapper selfParent = new EventDescriptorWrapper(
                new EventDescriptor(randomHash(random).getBytes(), 0, 1, 100));
        final EventDescriptorWrapper otherParent = new EventDescriptorWrapper(
                new EventDescriptor(randomHash(random).getBytes(), 1, 1, 100));
        final EventMetadata metadata =
                new EventMetadata(NodeId.of(0), selfParent, List.of(otherParent), Instant.now(), List.of(), 1);

        final long newBirthRound = 150;
        metadata.setBirthRoundOverride(newBirthRound, 100);

        // trying to override it again should fail
        assertThrows(IllegalStateException.class, () -> metadata.setBirthRoundOverride(200, 100));

        // validate that the birth round has been overridden with the first call only
        verifyEvent(metadata, 0, 101, newBirthRound);
        verifyEvent(metadata.getSelfParent(), 0, 100, newBirthRound);
        final List<EventDescriptorWrapper> otherParentsAfterOverride = metadata.getOtherParents();
        assertEquals(1, otherParentsAfterOverride.size());
        verifyEvent(otherParentsAfterOverride.getFirst(), 1, 100, newBirthRound);
    }

    @Test
    void testBirthRoundOverrideWithAncientParents() {
        final Random random = getRandomPrintSeed(0);
        final EventDescriptorWrapper selfParent = new EventDescriptorWrapper(
                new EventDescriptor(randomHash(random).getBytes(), 0, 1, 100));
        final EventDescriptorWrapper otherParent = new EventDescriptorWrapper(
                new EventDescriptor(randomHash(random).getBytes(), 1, 1, 90));
        final EventMetadata metadata =
                new EventMetadata(NodeId.of(0), selfParent, List.of(otherParent), Instant.now(), List.of(), 1);

        final long newBirthRound = 50;
        metadata.setBirthRoundOverride(newBirthRound, 100);

        // the self parent is not ancient so its birth round should be updated, but the other parent should not
        verifyEvent(metadata, 0, 101, newBirthRound);
        verifyEvent(metadata.getSelfParent(), 0, 100, newBirthRound);
        final List<EventDescriptorWrapper> otherParentsAfterOverride = metadata.getOtherParents();
        assertEquals(1, otherParentsAfterOverride.size());
        verifyEvent(otherParentsAfterOverride.getFirst(), 1, 90, 1);
    }

    private void verifyEvent(
            final EventMetadata metadata,
            final long expectedCreatorNodeId,
            final long expectedGeneration,
            final long expectedBirthRound) {
        assertNotNull(metadata);

        assertEquals(expectedCreatorNodeId, metadata.getCreatorId().id());
        assertEquals(expectedGeneration, metadata.getGeneration());
        assertEquals(expectedBirthRound, metadata.getBirthRound());
    }

    private void verifyEvent(
            final EventDescriptorWrapper actualDescriptorWrapper,
            final long expectedCreatorNodeId,
            final long expectedGeneration,
            final long expectedBirthRound) {
        assertNotNull(actualDescriptorWrapper);

        final EventDescriptor actualDescriptor = actualDescriptorWrapper.eventDescriptor();
        assertNotNull(actualDescriptor);

        assertEquals(expectedCreatorNodeId, actualDescriptor.creatorNodeId());
        assertEquals(expectedGeneration, actualDescriptor.generation());
        assertEquals(expectedBirthRound, actualDescriptor.birthRound());
    }
}

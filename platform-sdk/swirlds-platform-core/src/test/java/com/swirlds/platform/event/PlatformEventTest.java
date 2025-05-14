// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.test.fixtures.utils.EqualsVerifier;
import java.io.IOException;
import java.util.Random;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformEventTest {

    @Test
    @DisplayName("Serialize and deserialize event with 2 app payloads")
    void serializeDeserializeAppPayloads() throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformEvent platformEvent = new TestingEventBuilder(random).build();
        final PlatformEvent copy = EventSerializationUtils.serializeDeserializePlatformEvent(platformEvent);
        assertEquals(platformEvent, copy, "deserialized version should be the same");
    }

    @Test
    @DisplayName("Serialize and deserialize event with no payloads")
    void serializeDeserializeNoPayloads() throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformEvent platformEvent = new TestingEventBuilder(random)
                .setSystemTransactionCount(0)
                .setAppTransactionCount(0)
                .build();
        final PlatformEvent copy = EventSerializationUtils.serializeDeserializePlatformEvent(platformEvent);
        assertEquals(platformEvent, copy, "deserialized version should be the same");
    }

    @Test
    @DisplayName("Serialize and deserialize event with 2 system payloads")
    void serializeDeserializeSystemPayloads() throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformEvent platformEvent = new TestingEventBuilder(random)
                .setAppTransactionCount(0)
                .setSystemTransactionCount(2)
                .build();
        final PlatformEvent copy = EventSerializationUtils.serializeDeserializePlatformEvent(platformEvent);
        assertEquals(platformEvent, copy, "deserialized version should be the same");
    }

    @Test
    @DisplayName("Serialize and deserialize event with 2 system payloads and 2 app payloads")
    void serializeDeserializeAppAndSystemPayloads() throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformEvent platformEvent = new TestingEventBuilder(random)
                .setAppTransactionCount(2)
                .setSystemTransactionCount(2)
                .build();
        final PlatformEvent copy = EventSerializationUtils.serializeDeserializePlatformEvent(platformEvent);
        assertEquals(platformEvent, copy, "deserialized version should be the same");
    }

    @Test
    void validateEqualsHashCode() {
        assertTrue(EqualsVerifier.verify(random -> new TestingEventBuilder(random).build()));
    }

    @Test
    void validateDescriptor() {
        final Randotron r = Randotron.create();
        final PlatformEvent event = new TestingEventBuilder(r).build();
        event.invalidateHash();
        assertThrows(
                IllegalStateException.class,
                event::getDescriptor,
                "When the descriptor is not set, an exception should be thrown");
        event.setHash(r.nextHash());
        assertNotNull(event.getDescriptor(), "When the hash is set, the descriptor should be returned");
    }
}

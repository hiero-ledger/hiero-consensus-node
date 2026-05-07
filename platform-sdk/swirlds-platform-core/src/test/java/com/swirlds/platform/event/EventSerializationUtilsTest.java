// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Random;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventSerializationUtilsTest {

    private Random random;

    @BeforeEach
    void setUp() {
        random = RandomUtils.getRandomPrintSeed();
    }

    @Test
    void serializeDeserializePlatformEvent_succeeds() throws IOException {
        final PlatformEvent original = new TestingEventBuilder(random)
                .setOrigin(EventOrigin.GOSSIP)
                .setAppTransactionCount(2)
                .build();

        final PlatformEvent copy = EventSerializationUtils.serializeDeserializePlatformEvent(original);

        assertEquals(original.getGossipEvent(), copy.getGossipEvent());
        assertEquals(original.getOrigin(), copy.getOrigin());
        assertEquals(original, copy);
    }
}

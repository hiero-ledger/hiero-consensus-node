// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import java.io.IOException;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class CesEventTest {
    @BeforeAll
    public static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.platform");
    }

    @Test
    public void serializeAndDeserializeConsensusEvent() throws IOException {
        CesEvent consensusEvent = generateConsensusEvent();
        try (final InputOutputStream io = new InputOutputStream()) {
            io.getOutput().writeSerializable(consensusEvent, true);
            io.startReading();

            final CesEvent deserialized = io.getInput().readSerializable();
            assertEquals(consensusEvent, deserialized);
        }
    }

    private CesEvent generateConsensusEvent() {
        final Randotron random = Randotron.create(68651684861L);
        final PlatformEvent platformEvent = new TestingEventBuilder(random)
                .setConsensusTimestamp(random.nextInstant())
                .build();

        return new CesEvent(platformEvent, random.nextPositiveLong(), random.nextBoolean());
    }
}

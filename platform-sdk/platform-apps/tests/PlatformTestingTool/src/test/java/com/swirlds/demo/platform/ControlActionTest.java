// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.demo.platform.actions.QuorumResult;
import com.swirlds.demo.platform.fs.stresstest.proto.ControlType;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ControlActionTest {
    @BeforeAll
    public static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds");
        registry.registerConstructables("org.hiero.consensus");
    }

    @Test
    void SerializeControlActionTest() throws IOException {
        ControlAction controlAction = new ControlAction(Instant.now(), ControlType.ENTER_VALIDATION);

        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeSerializable(controlAction, true);
        io.startReading();
        ControlAction fromStream = io.getInput().readSerializable();

        assertEquals(controlAction, fromStream);
    }

    @Test
    void SerializeQuorumResultTest() throws IOException {
        ControlAction controlAction = new ControlAction(Instant.now(), ControlType.ENTER_VALIDATION);

        ControlAction controlActionExit = new ControlAction(Instant.now().minusSeconds(5), ControlType.EXIT_SYNC);

        AtomicReferenceArray<ControlAction> lastResultValues = new AtomicReferenceArray<>(4);
        lastResultValues.set(0, controlAction);
        lastResultValues.set(1, controlAction);
        lastResultValues.set(2, controlActionExit);

        QuorumResult<ControlAction> quorumResult =
                new QuorumResult<ControlAction>(false, controlAction, lastResultValues);

        InputOutputStream streamInput = new InputOutputStream();
        streamInput.getOutput().writeSerializable(quorumResult, true);
        streamInput.startReading();
        QuorumResult<ControlAction> quorumResultFromStream =
                streamInput.getInput().readSerializable();
        assertEquals(quorumResult, quorumResultFromStream);
    }
}

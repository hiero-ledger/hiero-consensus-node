// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.test.fixtures.dummy.Value;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import java.io.IOException;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ValueTests {

    @BeforeAll
    public static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.merkle.map");
        registry.registerConstructables("com.swirlds.merkle.tree");
        registry.registerConstructables("com.swirlds.common.test.dummy");
    }

    @Test
    void serializeAndDeserializeTest() throws IOException {
        final Value value = Value.buildRandomValue();
        try (final InputOutputStream io = new InputOutputStream()) {
            io.getOutput().writeSerializable(value, true);
            io.startReading();

            final Value deserializedValue = io.getInput().readSerializable();
            assertEquals(value, deserializedValue, "expected deserialized value to match original");
        }
    }
}

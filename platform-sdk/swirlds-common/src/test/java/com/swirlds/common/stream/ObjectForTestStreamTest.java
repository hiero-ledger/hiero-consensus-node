// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.test.fixtures.io.SerializationUtils;
import com.swirlds.common.test.fixtures.stream.ObjectForTestStream;
import java.time.Instant;
import org.hiero.base.constructable.ConstructableRegistry;
import org.junit.jupiter.api.Test;

class ObjectForTestStreamTest {
    private static final int PAYLOAD_SIZE = 4;
    private static final Instant TIMESTAMP = Instant.now();
    private static ObjectForTestStream object = new ObjectForTestStream(PAYLOAD_SIZE, TIMESTAMP);

    @Test
    void getTest() {
        assertEquals(TIMESTAMP, object.getTimestamp(), "timestamp should match expected");
    }

    @Test
    void toStringTest() {
        final String expectedString =
                String.format("ObjectForTestStream[payload size: %d, time: %s]", PAYLOAD_SIZE, TIMESTAMP);
        assertEquals(expectedString, object.toString(), "string should match expected");
    }

    @Test
    void serializeDeserializeTest() throws Exception {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds.common.test.fixtures.stream");
        ObjectForTestStream deserialized = SerializationUtils.serializeDeserialize(object);
        assertEquals(object, deserialized, "deserialized object should equal to original object");
    }
}

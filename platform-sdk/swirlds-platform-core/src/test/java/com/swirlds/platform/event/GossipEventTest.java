// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.Test;

public class GossipEventTest {

    /**
     * Tests the serialization of a {@link GossipEvent} object alonside legacy
     * {@link SelfSerializable} objects.
     */
    @Test
    void pbjSerializationTest() throws IOException {
        final Randotron r = Randotron.create();
        final Hash serializable = r.nextHash();
        final GossipEvent original = new TestingEventBuilder(r)
                .setAppTransactionCount(2)
                .setSystemTransactionCount(1)
                .setSelfParent(new TestingEventBuilder(r).build())
                .setOtherParent(new TestingEventBuilder(r).build())
                .build()
                .getGossipEvent();

        try (final InputOutputStream io = new InputOutputStream()) {
            io.getOutput().writeSerializable(serializable, true);
            io.getOutput().writePbjRecord(original, GossipEvent.PROTOBUF);
            io.getOutput().writeSerializable(serializable, false);
            io.getOutput().writePbjRecord(original, GossipEvent.PROTOBUF);

            io.startReading();

            final Hash readSer1 = io.getInput().readSerializable(true, Hash::new);
            final GossipEvent deserialized1 = io.getInput().readPbjRecord(GossipEvent.PROTOBUF);
            final Hash readSer2 = io.getInput().readSerializable(false, Hash::new);
            final GossipEvent deserialized2 = io.getInput().readPbjRecord(GossipEvent.PROTOBUF);

            assertEquals(serializable, readSer1, "the serializable object should be the same as the one written");
            assertEquals(original, deserialized1, "the event should be the same as the one written");
            assertEquals(serializable, readSer2, "the serializable object should be the same as the one written");
            assertEquals(original, deserialized2, "the event should be the same as the one written");
        }
    }

    /**
     * Serializes a {@link GossipEvent} object and truncates the serialized data at various points to ensure that
     * the data truncated at any point will throw an {@link EOFException} when deserialized.
     * <br>
     * This was introduced because the PBJ Codec does not throw an exception when the data is truncated in some
     * instances. Specifically, when the event was truncated at the signature, the codec would return an instance that
     * was only partially populated but would not throw an exception. The signature would be the desired length, but
     * only a part of it was read, the rest was left as zeros.
     */
    @Test
    void truncatedDataTest() throws IOException {
        final Randotron r = Randotron.create(1);
        final GossipEvent original = new TestingEventBuilder(r)
                .setAppTransactionCount(2)
                .setSystemTransactionCount(1)
                .setSelfParent(new TestingEventBuilder(r).build())
                .setOtherParent(new TestingEventBuilder(r).build())
                .build()
                .getGossipEvent();

        final byte[] byteArray;

        try (final ByteArrayOutputStream bs = new ByteArrayOutputStream();
                final SerializableDataOutputStream ss = new SerializableDataOutputStream(bs)) {
            ss.writePbjRecord(original, GossipEvent.PROTOBUF);
            byteArray = bs.toByteArray();
        }
        for (int i = 0; i < byteArray.length; i++) {
            final byte[] truncated = Arrays.copyOf(byteArray, i);
            try (final ByteArrayInputStream bs = new ByteArrayInputStream(truncated);
                    final SerializableDataInputStream ss = new SerializableDataInputStream(bs)) {
                assertThrows(EOFException.class, () -> ss.readPbjRecord(GossipEvent.PROTOBUF));
            }
        }
    }
}

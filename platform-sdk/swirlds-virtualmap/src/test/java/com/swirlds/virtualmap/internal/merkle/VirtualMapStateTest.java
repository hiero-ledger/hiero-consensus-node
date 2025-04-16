// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.internal.merkle.VirtualMapState.MAX_LABEL_CHARS;
import static org.hiero.base.utility.test.fixtures.RandomUtils.nextInt;
import static org.hiero.base.utility.test.fixtures.RandomUtils.randomString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.internal.Path;
import java.io.UncheckedIOException;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VirtualMapStateTest {

    public static final Random RANDOM = new Random();

    @Test
    void testDefaultConstructor() {
        VirtualMapState state = new VirtualMapState("test");
        assertEquals(-1, state.getFirstLeafPath(), "Default firstLeafPath should be -1");
        assertEquals(-1, state.getLastLeafPath(), "Default lastLeafPath should be -1");
        assertEquals(0, state.getSize(), "Size should be 0 when no leaves");

        assertThrows(NullPointerException.class, () -> new VirtualMapState((String) null));
    }

    @Test
    void testConstructorWithBytesValid() {
        // Arrange: create raw protobuf-encoded data
        // Field #1 (firstLeafPath): wire type = FIXED64 => (1 << 3) + 1 = 0x09
        // Then 8 bytes for firstLeafPath = 1
        // Field #2 (lastLeafPath): wire type = FIXED64 => (2 << 3) + 1 = 0x11
        // Then 8 bytes for lastLeafPath = 2
        // Field #3 (label): wire type = DELIMITED => (3 << 3) + 2 = 0x1A
        // Then length of the label (4 for "test"), then the bytes for "test"
        byte[] rawProtobuf = {
            (byte) 0x09, // Tag: firstLeafPath, fixed64
            0x01,
            0,
            0,
            0,
            0,
            0,
            0,
            0, // firstLeafPath = 1
            (byte) 0x11, // Tag: lastLeafPath, fixed64
            0x02,
            0,
            0,
            0,
            0,
            0,
            0,
            0, // lastLeafPath = 2
            (byte) 0x1A, // Tag: label, delimited
            0x04, // length of the label = 4
            0x74,
            0x65,
            0x73,
            0x74 // UTF-8 bytes for "test"
        };

        // Wrap into a Bytes object
        Bytes bytes = Bytes.wrap(rawProtobuf);

        // Act: construct the VirtualMapState from the Bytes
        VirtualMapState state = new VirtualMapState(bytes);

        // Assert: verify fields set as expected
        assertEquals(1L, state.getFirstLeafPath(), "firstLeafPath should be 1");
        assertEquals(2L, state.getLastLeafPath(), "lastLeafPath should be 2");
        assertEquals("test", state.getLabel(), "Label should be 'test'");
        assertEquals(2L, state.getSize(), "Size should be (2 - 1 + 1) = 2");
    }

    /**
     * Truncated varint: The field tag is a varint. If we provide a partial varint
     * (e.g., 0x80 with no subsequent byte), it will fail when reading the field number.
     */
    @Test
    void truncatedVarintTagTest() {
        // 0x80 => This indicates continuation (since the MSB is set),
        // but we provide no subsequent byte, causing a truncated varint parse.
        byte[] rawProtobuf = {(byte) 0x80};
        Bytes bytes = Bytes.wrap(rawProtobuf);

        assertThrows(
                RuntimeException.class,
                () -> new VirtualMapState(bytes),
                "Expected parsing to fail due to truncated varint tag");
    }

    /**
     * Unknown field number: If we provide a field number (e.g., #4) that the parser
     * doesn't recognize, `VirtualMapState` throws `IllegalArgumentException`.
     *
     *   - Tag for field #4, wire type = 1 (fixed64) => (4 << 3) + 1 = 0x21
     *   - Then 8 bytes for the alleged fixed64 field.
     */
    @Test
    void unknownFieldNumberTest() {
        byte[] rawProtobuf = {
            (byte) 0x21, // field #4, wire type 1 (fixed64)
            0x01,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00
        };

        Bytes bytes = Bytes.wrap(rawProtobuf);

        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualMapState(bytes),
                "Expected failure due to unknown field #4");
    }

    /**
     * Wire-type mismatch for `firstLeafPath`:
     *    The constructor expects field #1 to use wire type = 1 (fixed64).
     *    Here we intentionally give wire type = 2 (length-delimited).
     *    - Tag for field #1 with wire type = 2 => (1 << 3) + 2 = 0x0A
     */
    @Test
    void wireTypeMismatchFirstLeafPathTest() {
        // field #1 => expecting fixed64 => but supply wire type = 2
        byte[] rawProtobuf = {
            (byte) 0x0A, // field #1, wire type=2
            0x01, // length = 1
            0x42 // some random content
        };

        Bytes bytes = Bytes.wrap(rawProtobuf);

        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualMapState(bytes),
                "Expected failure when 'firstLeafPath' field uses the wrong wire type");
    }

    /**
     *Wire-type mismatch** for `lastLeafPath`:
     *    The constructor expects field #2 to use wire type = 1 (fixed64).
     *    Here we intentionally give wire type = 2 (length-delimited).
     *    - Tag for field #2 with wire type = 2 => (2 << 3) + 2 = 0x12
     */
    @Test
    void wireTypeMismatchLastLeafPathTest() {
        // field #2 => expecting fixed64 => but supply wire type = 2
        byte[] rawProtobuf = {
            (byte) 0x12, // field #2, wire type=2
            0x01, // length = 1
            0x42 // random
        };

        Bytes bytes = Bytes.wrap(rawProtobuf);

        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualMapState(bytes),
                "Expected failure when 'lastLeafPath' field uses the wrong wire type");
    }

    /**
     * Wire-type mismatch for `label`:
     *    The constructor expects field #3 to use wire type = 2 (length-delimited).
     *    Here we intentionally give wire type = 1 (fixed64).
     *    - Tag for field #3 with wire type = 1 => (3 << 3) + 1 = 0x19
     */
    @Test
    void wireTypeMismatchLabelTest() {
        // field #3 => expecting length-delimited => but supply wire type = 1
        byte[] rawProtobuf = {
            (byte) 0x19, // field #3, wire type=1
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00 // 8 bytes
        };

        Bytes bytes = Bytes.wrap(rawProtobuf);

        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualMapState(bytes),
                "Expected failure when 'label' field uses the wrong wire type");
    }

    /**
     * Truncated firstLeafPath:
     *    For a fixed64 field, we need 8 bytes. Here we only provide 4.
     *    - Tag for field #1 => 0x09
     */
    @Test
    void truncatedFixed64FirstLeafPathTest() {
        byte[] rawProtobuf = {
            (byte) 0x09, // field #1, wire type = 1 (fixed64)
            0x01,
            0x02,
            0x03,
            0x04 // only 4 bytes => not enough for a 64-bit value
        };

        Bytes bytes = Bytes.wrap(rawProtobuf);

        assertThrows(
                RuntimeException.class,
                () -> new VirtualMapState(bytes),
                "Expected failure due to truncated fixed64 (firstLeafPath)");
    }

    /**
     * Truncated lastLeafPath:
     *    For a fixed64 field, we need 8 bytes. Here we only provide 5.
     *    - Tag for field #2 => 0x11
     */
    @Test
    void truncatedFixed64LastLeafPathTest() {
        byte[] rawProtobuf = {
            (byte) 0x11, // field #2, wire type=1 (fixed64)
            0x01,
            0x02,
            0x03,
            0x04,
            0x05 // only 5 bytes => not enough for a 64-bit value
        };

        Bytes bytes = Bytes.wrap(rawProtobuf);

        assertThrows(
                RuntimeException.class,
                () -> new VirtualMapState(bytes),
                "Expected failure due to truncated fixed64 (lastLeafPath)");
    }

    /**
     * Truncated labe:
     *    The label is length-delimited (wire type=2). Suppose we say length=4,
     *    but only provide 2 bytes of actual data => truncated string.
     *    - Tag for field #3 => (3 << 3) + 2 = 0x1A
     *    - length = 4 => actual bytes = 2 => mismatch
     */
    @Test
    void truncatedLabelTest() {
        byte[] rawProtobuf = {
            (byte) 0x1A, // field #3, wire type=2 (length-delimited)
            0x04, // declared length of 4
            0x41,
            0x42 // only 2 bytes ("AB")
        };

        Bytes bytes = Bytes.wrap(rawProtobuf);

        assertThrows(
                RuntimeException.class, () -> new VirtualMapState(bytes), "Expected failure due to truncated label");
    }

    /**
     * Invalid UTF-8 sequence for the label field.
     * Field #3 (label): wire type = length-delimited => (3 << 3) + 2 = 0x1A.
     * We say length = 2, then provide two bytes that form an invalid UTF-8 sequence:
     *   0xC3 0x28
     */
    @Test
    void invalidUtf8LabelTest() {
        // 0x1A => field #3, wire type = 2 (length-delimited)
        // 0x02 => length = 2
        // 0xC3, 0x28 => invalid UTF-8 sequence
        byte[] rawProtobuf = {(byte) 0x1A, 0x02, (byte) 0xC3, (byte) 0x28};

        Bytes bytes = Bytes.wrap(rawProtobuf);

        // The VirtualMapState constructor ultimately calls readString(), which:
        //   1) Attempts to decode 0xC3 0x28 as UTF-8.
        //   2) Fails with MalformedProtobufException (subclass of ParseException).
        //   3) readString() rethrows as UncheckedParseException.
        assertThrows(
                UncheckedIOException.class,
                () -> new VirtualMapState(bytes),
                "Expected the constructor to fail due to invalid UTF-8 in the label");
    }

    @Test
    void testConstructorWithLabel() {
        String label = "TestLabel";
        VirtualMapState state = new VirtualMapState(label);
        assertEquals(-1, state.getFirstLeafPath(), "Expected firstLeafPath to be -1 by default");
        assertEquals(-1, state.getLastLeafPath(), "Expected lastLeafPath to be -1 by default");
        assertEquals(label, state.getLabel(), "Expected label to match constructor argument");
        assertEquals(0, state.getSize(), "Size should be 0 when no leaves");
    }

    @Test
    void testValidPaths() {
        VirtualMapState state = new VirtualMapState("test");
        int firstLeafPath = nextInt(1, 100);
        int lastLeafPath = nextInt(firstLeafPath + 1, firstLeafPath * 2);
        state.setLastLeafPath(lastLeafPath);
        state.setFirstLeafPath(firstLeafPath);
        assertEquals(firstLeafPath, state.getFirstLeafPath());
        assertEquals(lastLeafPath, state.getLastLeafPath());
    }

    @Test
    void testInvalidFirstLeafPath() {
        VirtualMapState state = new VirtualMapState("test");
        int firstLeafPath = nextInt(1, 100);
        int lastLeafPath = firstLeafPath * 2;
        state.setLastLeafPath(lastLeafPath);
        state.setFirstLeafPath(firstLeafPath);

        assertThrows(
                IllegalArgumentException.class,
                () -> state.setFirstLeafPath(0),
                "Setting firstLeafPath to 0 should throw an exception");
        assertThrows(
                IllegalArgumentException.class,
                () -> state.setFirstLeafPath(nextInt(Integer.MIN_VALUE, -2)),
                "Setting firstLeafPath to a negative value should throw an exception");
        assertThrows(
                IllegalArgumentException.class,
                () -> state.setFirstLeafPath(lastLeafPath + 1),
                "Setting firstLeafPath to a path greater than lastLeafPath should throw an exception");

        // Path.INVALID_PATH is allowed
        state.setFirstLeafPath(Path.INVALID_PATH);
    }

    @Test
    void testInvalidLastLeafPath() {
        VirtualMapState state = new VirtualMapState("test");
        int firstLeafPath = nextInt(1, 100);
        int lastLeafPath = firstLeafPath * 2;
        state.setLastLeafPath(lastLeafPath);
        state.setFirstLeafPath(firstLeafPath);

        assertThrows(
                IllegalArgumentException.class,
                () -> state.setLastLeafPath(0),
                "Setting lastLeafPath to 0 should throw an exception");
        assertThrows(
                IllegalArgumentException.class,
                () -> state.setLastLeafPath(nextInt(Integer.MIN_VALUE, -2)),
                "Setting lastLeafPath to a negative value should throw an exception");
        assertThrows(
                IllegalArgumentException.class,
                () -> state.setLastLeafPath(firstLeafPath - 1),
                "Setting lastLeafPath lesser than firstLeafPath should throw an exception");

        // Path.INVALID_PATH is allowed
        state.setLastLeafPath(Path.INVALID_PATH);
    }

    @Test
    void testSetLabel() {
        final String initLabel = randomString(RANDOM, 7);
        VirtualMapState state = new VirtualMapState(initLabel);

        assertEquals(initLabel, state.getLabel());

        final String newLabel = randomString(RANDOM, 7);
        state.setLabel(newLabel);
        assertEquals(newLabel, state.getLabel());

        assertThrows(
                NullPointerException.class, () -> state.setLabel(null), "Setting null label should throw an exception");

        assertThrows(
                IllegalArgumentException.class,
                () -> state.setLabel(randomString(RANDOM, MAX_LABEL_CHARS + 1)),
                "Setting label with legth greater than " + MAX_LABEL_CHARS + " should throw an exception");
    }

    @Test
    void testGetSize() {
        VirtualMapState state = new VirtualMapState("test");

        int firstLeafPath = nextInt(1, 100);
        int lastLeafPath = firstLeafPath * 2;
        state.setLastLeafPath(lastLeafPath);
        state.setFirstLeafPath(firstLeafPath);

        assertEquals(lastLeafPath - firstLeafPath + 1, state.getSize());
    }

    @Test
    void testToBytes() {
        // Arrange: create and configure a VirtualMapState
        VirtualMapState original = new VirtualMapState("testLabel");
        original.setLastLeafPath(200);
        original.setFirstLeafPath(100);

        // Act: serialize to Bytes
        Bytes bytes = original.toBytes();

        // Assert: deserialize into a new VirtualMapState and compare fields
        VirtualMapState deserialized = new VirtualMapState(bytes);
        assertEquals(100, deserialized.getFirstLeafPath(), "firstLeafPath should match after round-trip");
        assertEquals(200, deserialized.getLastLeafPath(), "lastLeafPath should match after round-trip");
        assertEquals("testLabel", deserialized.getLabel(), "Label should match after round-trip");
        assertEquals(101, deserialized.getSize(), "Size should be (200 - 100 + 1) = 101");
    }
}

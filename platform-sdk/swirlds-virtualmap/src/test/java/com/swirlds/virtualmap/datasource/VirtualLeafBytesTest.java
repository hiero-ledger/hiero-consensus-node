// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class VirtualLeafBytesTest {

    private static final long FAKE_KEY_NUM = -1000;
    private static final long DIFFERENT_KEY_NUM = -2000;
    private static final Random RANDOM = new Random(49);

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Keys may not be null")
    void createLeafBytesWithNullKey() {
        assertThrows(
                NullPointerException.class,
                () -> new VirtualLeafBytes<>(1, null, new TestValue("s"), TestValueCodec.INSTANCE));
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Value codec may not be null when value is not null")
    void createLeafBytesWithNullCodec() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualLeafBytes<>(1, TestKey.longToKey(FAKE_KEY_NUM), new TestValue("s"), null));
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Using the constructor with value and codec works")
    void createLeafFromValue() {
        final Bytes key = TestKey.longToKey(FAKE_KEY_NUM);
        final TestValue value = new TestValue("Fake value");
        final VirtualLeafBytes<TestValue> leafBytes = new VirtualLeafBytes<>(102, key, value, TestValueCodec.INSTANCE);
        assertEquals(key, leafBytes.keyBytes(), "key should match original");
        assertEquals(value.toBytes(), leafBytes.valueBytes(), "value bytes should match original");
        assertEquals(value, leafBytes.value(TestValueCodec.INSTANCE), "value should match original");
        assertEquals(102, leafBytes.path(), "path should match value set");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Using the constructor with value bytes works")
    void createLeafFromValueBytes() {
        final Bytes key = TestKey.longToKey(FAKE_KEY_NUM);
        final TestValue value = new TestValue("Fake value");
        final Bytes valueBytes = value.toBytes();
        final VirtualLeafBytes<TestValue> leafBytes = new VirtualLeafBytes<>(103, key, valueBytes);
        assertEquals(key, leafBytes.keyBytes(), "key should match original");
        assertEquals(valueBytes, leafBytes.valueBytes(), "value bytes should match original");
        assertEquals(value, leafBytes.value(TestValueCodec.INSTANCE), "value should match original");
        assertEquals(103, leafBytes.path(), "path should match value set");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Create with null value bytes works")
    void createLeafFromNullValueBytes() {
        final Bytes key = TestKey.longToKey(FAKE_KEY_NUM);
        final VirtualLeafBytes<TestValue> leafBytes = new VirtualLeafBytes<>(104, key, null);
        assertEquals(key, leafBytes.keyBytes(), "key should match original");
        assertNull(leafBytes.valueBytes(), "value bytes should be null");
        assertNull(leafBytes.value(TestValueCodec.INSTANCE), "value should be null");
        assertEquals(104, leafBytes.path(), "path should match value set");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Create with empty value bytes works")
    void createLeafFromEmptyValueBytes() {
        final Bytes key = TestKey.longToKey(FAKE_KEY_NUM);
        final Bytes valueBytes = Bytes.EMPTY;
        final VirtualLeafBytes<TestValue> leafBytes = new VirtualLeafBytes<>(104, key, valueBytes);
        assertEquals(key, leafBytes.keyBytes(), "key should match original");
        assertEquals(Bytes.EMPTY, valueBytes, "value bytes should be empty");
        assertEquals(104, leafBytes.path(), "path should match value set");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("toString with a null value is OK")
    void toStringWithNullValueDoesNotThrow() {
        final VirtualLeafBytes<TestValue> leafBytes = new VirtualLeafBytes<>(11, TestKey.longToKey(11), null, null);
        final String str = leafBytes.toString();
        assertNotNull(str, "value should not be null");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Identity equals")
    void identityEqualsWorks() {
        final Bytes key = TestKey.longToKey(FAKE_KEY_NUM);
        final TestValue value = new TestValue("Fake value");
        final VirtualLeafBytes<TestValue> leafBytes = new VirtualLeafBytes<>(102, key, value, TestValueCodec.INSTANCE);
        assertEquals(leafBytes, leafBytes, "leaf bytes should be equal");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Equal instances")
    void equalInstances() {
        final Bytes key = TestKey.longToKey(FAKE_KEY_NUM);
        final TestValue value = new TestValue("Fake value");
        final VirtualLeafBytes<TestValue> leafBytes = new VirtualLeafBytes<>(102, key, value, TestValueCodec.INSTANCE);
        final VirtualLeafBytes<TestValue> leafBytes2 = new VirtualLeafBytes<>(102, key, value, TestValueCodec.INSTANCE);
        assertEquals(leafBytes, leafBytes2, "leaf bytes should be equal");
        assertEquals(leafBytes2, leafBytes, "leaf bytes should be equal");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Equal instances 2")
    void equalInstances2() {
        final Bytes key = TestKey.longToKey(FAKE_KEY_NUM);
        final TestValue value = new TestValue("Fake value");
        final Bytes valueBytes = value.toBytes();
        final VirtualLeafBytes<TestValue> leafBytes = new VirtualLeafBytes<>(102, key, value, TestValueCodec.INSTANCE);
        final VirtualLeafBytes<TestValue> leafBytes2 = new VirtualLeafBytes<>(102, key, valueBytes);
        assertEquals(leafBytes, leafBytes2, "leaf bytes should be equal");
        assertEquals(leafBytes2, leafBytes, "leaf bytes should be equal");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Unequal instances")
    void unequalInstances() {
        final Bytes key = TestKey.longToKey(FAKE_KEY_NUM);
        final TestValue value = new TestValue("Fake value");
        final int path = 102;
        final VirtualLeafBytes<TestValue> first = new VirtualLeafBytes<>(path, key, value, TestValueCodec.INSTANCE);

        // Test with null
        //noinspection ConstantConditions,SimplifiableAssertion
        assertFalse(first.equals(null), "should not be equal with null");

        // Test with a different path
        final int diffrenetPath = 988;
        VirtualLeafBytes<TestValue> second = new VirtualLeafBytes<>(diffrenetPath, key, value, TestValueCodec.INSTANCE);
        assertNotEquals(first, second, "leaf bytes should not be equal");
        assertNotEquals(second, first, "leaf bytes should not be equal");

        // Test with a different key
        final Bytes differentKey = TestKey.longToKey(DIFFERENT_KEY_NUM);
        second = new VirtualLeafBytes<>(path, differentKey, value, TestValueCodec.INSTANCE);
        assertNotEquals(first, second, "leaf bytes should not be equal");
        assertNotEquals(second, first, "leaf bytes should not be equal");

        // Test with an empty key
        second = new VirtualLeafBytes<>(path, Bytes.EMPTY, value, TestValueCodec.INSTANCE);
        assertNotEquals(first, second, "leaf bytes should not be equal");
        assertNotEquals(second, first, "leaf bytes should not be equal");

        // Test with a different value
        final TestValue differentValue = new TestValue("Different value");
        second = new VirtualLeafBytes<>(path, key, differentValue, TestValueCodec.INSTANCE);
        assertNotEquals(first, second, "leaf bytes should not be equal");
        assertNotEquals(second, first, "leaf bytes should not be equal");

        // Test with a null value
        second = new VirtualLeafBytes<>(path, key, null, null);
        assertNotEquals(first, second, "leaf bytes should not be equal");
        assertNotEquals(second, first, "leaf bytes should not be equal");

        // Test with some random object
        final String random = "Random!";
        //noinspection AssertBetweenInconvertibleTypes
        assertNotEquals(random, first, "leaf bytes should not be equal");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("hashCode")
    void testHashCode() {
        final Bytes key = TestKey.longToKey(FAKE_KEY_NUM);
        final TestValue value = new TestValue("Fake value");
        final int firstPath = 102;
        final VirtualLeafBytes<TestValue> rec = new VirtualLeafBytes<>(firstPath, key, value, TestValueCodec.INSTANCE);
        final int hash1 = rec.hashCode();

        // Test the identity
        VirtualLeafBytes<TestValue> second = new VirtualLeafBytes<>(firstPath, key, value, TestValueCodec.INSTANCE);
        assertEquals(hash1, second.hashCode(), "hash should match original");

        // Create a variant with a different path and assert the hashCode is different
        final int secondPath = 988;
        second = new VirtualLeafBytes<>(secondPath, key, value, TestValueCodec.INSTANCE);
        assertNotEquals(hash1, second.hashCode(), "hash should not be the same");

        // Test with a different key
        final Bytes differentKey = TestKey.longToKey(DIFFERENT_KEY_NUM);
        second = new VirtualLeafBytes<>(firstPath, differentKey, value, TestValueCodec.INSTANCE);
        assertNotEquals(hash1, second.hashCode(), "hash should not be the same");

        // Test with an empty key
        second = new VirtualLeafBytes<>(firstPath, Bytes.EMPTY, value, TestValueCodec.INSTANCE);
        assertNotEquals(hash1, second.hashCode(), "hash should not be the same");

        // Test with a different value
        final TestValue differentValue = new TestValue("Different value");
        second = new VirtualLeafBytes<>(firstPath, key, differentValue, TestValueCodec.INSTANCE);
        assertNotEquals(hash1, second.hashCode(), "hash should not be the same");

        // Test with a null value
        second = new VirtualLeafBytes<>(firstPath, key, null, null);
        assertNotEquals(hash1, second.hashCode(), "hash should not be the same");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Serialization and deserialization")
    void serializesAndDeserializes() {
        final long keyId = RANDOM.nextLong();
        final Bytes key = TestKey.longToKey(keyId);
        final TestValue value = new TestValue("This is a custom value");
        final long path = 1329;
        final VirtualLeafBytes<TestValue> leafBytes = new VirtualLeafBytes<>(path, key, value, TestValueCodec.INSTANCE);

        final byte[] bytes = new byte[leafBytes.getSizeInBytes()];
        leafBytes.writeTo(BufferedData.wrap(bytes));

        final VirtualLeafBytes deserialized = VirtualLeafBytes.parseFrom(BufferedData.wrap(bytes));
        assertEquals(leafBytes, deserialized, "Deserialized leaf should match original");
        assertEquals(deserialized, leafBytes, "Original leaf should match deserialized");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("writeToForHashing writes 0x00 + Protobuf-tagged key and value bytes")
    void writeToForHashing_withValue() {
        final long keyId = RANDOM.nextLong();
        final Bytes key = TestKey.longToKey(keyId);
        final TestValue value = new TestValue("Hash me!");
        final VirtualLeafBytes<TestValue> leafBytes = new VirtualLeafBytes<>(777, key, value, TestValueCodec.INSTANCE);

        final Bytes kb = leafBytes.keyBytes();
        final Bytes vb = leafBytes.valueBytes();

        final int keySize =
                ProtoWriterTools.sizeOfDelimited(VirtualLeafBytes.FIELD_LEAFRECORD_KEY, Math.toIntExact(kb.length()));
        final int valueSize =
                ProtoWriterTools.sizeOfDelimited(VirtualLeafBytes.FIELD_LEAFRECORD_VALUE, Math.toIntExact(vb.length()));
        final int len = 1 + keySize + valueSize;

        final byte[] actual = new byte[len];
        leafBytes.writeToForHashing(BufferedData.wrap(actual));

        final byte[] expected = new byte[len];
        final var out = BufferedData.wrap(expected);
        out.writeByte((byte) 0x00);
        ProtoWriterTools.writeTag(out, VirtualLeafBytes.FIELD_LEAFRECORD_KEY);
        out.writeVarInt(Math.toIntExact(kb.length()), false);
        kb.writeTo(out);

        ProtoWriterTools.writeTag(out, VirtualLeafBytes.FIELD_LEAFRECORD_VALUE);
        out.writeVarInt(Math.toIntExact(vb.length()), false);
        vb.writeTo(out);

        assertEquals(len, actual.length, "output length should match expected");
        assertArrayEquals(expected, actual, "hashing bytes should match");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("writeToForHashing writes 0x00 + Protobuf-tagged key when value is null")
    void writeToForHashing_withNullValue() {
        final long keyId = RANDOM.nextLong();
        final Bytes key = TestKey.longToKey(keyId);
        final VirtualLeafBytes<TestValue> leafBytes = new VirtualLeafBytes<>(888, key, null);

        final Bytes kb = leafBytes.keyBytes();
        final int keySize =
                ProtoWriterTools.sizeOfDelimited(VirtualLeafBytes.FIELD_LEAFRECORD_KEY, Math.toIntExact(kb.length()));
        final int len = 1 + keySize;

        final byte[] actual = new byte[len];
        leafBytes.writeToForHashing(BufferedData.wrap(actual));

        final byte[] expected = new byte[len];
        final var out = BufferedData.wrap(expected);
        out.writeByte((byte) 0x00);
        ProtoWriterTools.writeTag(out, VirtualLeafBytes.FIELD_LEAFRECORD_KEY);
        out.writeVarInt(Math.toIntExact(kb.length()), false);
        kb.writeTo(out);

        assertArrayEquals(expected, actual, "hashing bytes should match");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("writeToForHashing includes tagged empty value bytes")
    void writeToForHashing_withEmptyValueBytes() {
        final Bytes key = TestKey.longToKey(RANDOM.nextLong());
        final VirtualLeafBytes<TestValue> leafBytes = new VirtualLeafBytes<>(999, key, Bytes.EMPTY);

        final Bytes kb = leafBytes.keyBytes();
        final Bytes vb = leafBytes.valueBytes();

        final int keySize =
                ProtoWriterTools.sizeOfDelimited(VirtualLeafBytes.FIELD_LEAFRECORD_KEY, Math.toIntExact(kb.length()));
        final int valueSize =
                ProtoWriterTools.sizeOfDelimited(VirtualLeafBytes.FIELD_LEAFRECORD_VALUE, Math.toIntExact(vb.length()));
        final int len = 1 + keySize + valueSize;

        final byte[] actual = new byte[len];
        leafBytes.writeToForHashing(BufferedData.wrap(actual));

        final byte[] expected = new byte[len];
        final var out = BufferedData.wrap(expected);
        out.writeByte((byte) 0x00);
        ProtoWriterTools.writeTag(out, VirtualLeafBytes.FIELD_LEAFRECORD_KEY);
        out.writeVarInt(Math.toIntExact(kb.length()), false);
        kb.writeTo(out);

        ProtoWriterTools.writeTag(out, VirtualLeafBytes.FIELD_LEAFRECORD_VALUE);
        out.writeVarInt(0, false);
        // empty value bytes add no extra bytes but length 0 is written

        assertArrayEquals(expected, actual, "hashing bytes should match");
    }

    @Test
    void isNewOrMovedIsTrueByDefault() {
        final Bytes key = TestKey.longToKey(RANDOM.nextLong());
        final VirtualLeafBytes<TestValue> leaf = new VirtualLeafBytes<>(1, key, Bytes.EMPTY);
        assertTrue(leaf.isNewOrMoved(), "Newly created leaves should be new/moved");
    }

    @Test
    void withPathPreservesPathOnDisk() {
        final Bytes key = TestKey.longToKey(RANDOM.nextLong());
        final VirtualLeafBytes<TestValue> leaf = new VirtualLeafBytes<>(1, key, Bytes.EMPTY);
        final VirtualLeafBytes<TestValue> updated = leaf.withPath(12);
        assertTrue(updated.isNewOrMoved(), "withPath should mark the leaf as new/moved");
    }

    @Test
    void withValuePreservesPathOnDisk() {
        final Bytes key = TestKey.longToKey(RANDOM.nextLong());
        final VirtualLeafBytes<TestValue> leaf = new VirtualLeafBytes<>(1, key, Bytes.EMPTY);
        final VirtualLeafBytes<TestValue> updated = leaf.withPath(12);
        final VirtualLeafBytes<TestValue> updatedUpdated =
                updated.withValue(new TestValue("New!"), TestValueCodec.INSTANCE);
        assertTrue(updatedUpdated.isNewOrMoved(), "withValue should mark the leaf as new/moved");
    }

    @Test
    void isNewOrMovedIsFalseWhenDeserialized() {
        final Bytes key = TestKey.longToKey(RANDOM.nextLong());
        final TestValue value = new TestValue("Value");
        final VirtualLeafBytes<TestValue> leaf = new VirtualLeafBytes<>(11, key, value, TestValueCodec.INSTANCE);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        leaf.writeTo(new WritableStreamingData(out));

        final byte[] bytes = out.toByteArray();
        final BufferedData in = BufferedData.wrap(bytes);
        final VirtualLeafBytes<TestValue> deserialized = VirtualLeafBytes.parseFrom(in);
        assertFalse(deserialized.isNewOrMoved(), "Deserialized leaf should not be new/moved");

        final TestValue updatedValue = new TestValue("Updated");
        final VirtualLeafBytes<TestValue> updated = deserialized.withValue(updatedValue, TestValueCodec.INSTANCE);
        assertFalse(updated.isNewOrMoved(), "withValue should not mark the leaf as new/moved");

        final VirtualLeafBytes<TestValue> moved = deserialized.withPath(12);
        assertTrue(moved.isNewOrMoved(), "withPath should mark the leaf as new/moved");

        final VirtualLeafBytes<TestValue> movedBack = moved.withPath(deserialized.path());
        assertFalse(movedBack.isNewOrMoved(), "Should not be new/moved when moved to the original path");
    }

    @Test
    void isNewOrUpdatedIsTrueForInvalidPaths() {
        final Bytes key = TestKey.longToKey(RANDOM.nextLong());
        final VirtualLeafBytes<TestValue> leaf = new VirtualLeafBytes<>(Path.INVALID_PATH, key, Bytes.EMPTY);
        assertTrue(leaf.isNewOrMoved(), "Leaf with invalid path should be new/moved");
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.merkle.utility.MerkleLong;
import com.swirlds.common.test.fixtures.dummy.Key;
import com.swirlds.common.test.fixtures.dummy.Value;
import com.swirlds.merkle.map.internal.MerkleMapEntry;
import java.io.IOException;
import org.hiero.base.exceptions.ReferenceCountException;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("MerkleMapEntry Tests")
class MerkleMapEntryTests {

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Equals With Key Test")
    void equalsWithKeyTest() {

        final MerkleMapEntry<Key, Value> entry1 = new MerkleMapEntry<>(
                new Key(new long[] {1, 1, 1}), Value.newBuilder().build());

        final MerkleMapEntry<Key, Value> entry2 = new MerkleMapEntry<>(
                new Key(new long[] {1, 1, 1}),
                Value.newBuilder()
                        .setBalance(100)
                        .setReceiveThresholdValue(50)
                        .setSendThresholdvalue(75)
                        .setReceiveSignatureRequired(true)
                        .build());

        // Equal with entry2
        final MerkleMapEntry<Key, Value> entry3 = new MerkleMapEntry<>(
                new Key(new long[] {1, 1, 1}),
                Value.newBuilder()
                        .setBalance(100)
                        .setReceiveThresholdValue(50)
                        .setSendThresholdvalue(75)
                        .setReceiveSignatureRequired(true)
                        .build());

        // value only, key will be null
        final MerkleMapEntry<Key, Value> entry4 = new MerkleMapEntry<>(Value.newBuilder()
                .setBalance(100)
                .setReceiveThresholdValue(50)
                .setSendThresholdvalue(75)
                .setReceiveSignatureRequired(true)
                .build());

        // null value
        final MerkleMapEntry<Key, Value> entry5 = new MerkleMapEntry<>(new Key(new long[] {1, 1, 1}), null);

        // null key and value
        final MerkleMapEntry<Key, Value> entry6 = new MerkleMapEntry<>(null, null);

        final String equals = "expected entries to be equal";
        assertEquals(entry1, entry1, equals);
        assertEquals(entry2, entry2, equals);
        assertEquals(entry2, entry3, equals);
        assertEquals(entry3, entry2, equals);
        assertEquals(entry3, entry3, equals);
        assertEquals(entry4, entry4, equals);
        assertEquals(entry5, entry5, equals);
        assertEquals(entry6, entry6, equals);

        final String notEquals = "expected entries not to be equal";

        assertNotEquals(entry1, entry2, notEquals);
        assertNotEquals(entry1, entry4, notEquals);
        assertNotEquals(entry1, entry5, notEquals);
        assertNotEquals(entry1, entry6, notEquals);

        assertNotEquals(entry2, entry1, notEquals);
        assertNotEquals(entry2, entry4, notEquals);
        assertNotEquals(entry2, entry5, notEquals);
        assertNotEquals(entry2, entry6, notEquals);

        assertNotEquals(entry3, entry1, notEquals);
        assertNotEquals(entry3, entry4, notEquals);
        assertNotEquals(entry3, entry5, notEquals);
        assertNotEquals(entry3, entry6, notEquals);

        assertNotEquals(entry4, entry1, notEquals);
        assertNotEquals(entry4, entry2, notEquals);
        assertNotEquals(entry4, entry3, notEquals);
        assertNotEquals(entry4, entry5, notEquals);
        assertNotEquals(entry4, entry6, notEquals);

        assertNotEquals(entry5, entry1, notEquals);
        assertNotEquals(entry5, entry2, notEquals);
        assertNotEquals(entry5, entry3, notEquals);
        assertNotEquals(entry5, entry4, notEquals);
        assertNotEquals(entry5, entry6, notEquals);

        assertNotEquals(entry6, entry1, notEquals);
        assertNotEquals(entry6, entry2, notEquals);
        assertNotEquals(entry6, entry3, notEquals);
        assertNotEquals(entry6, entry4, notEquals);
        assertNotEquals(entry6, entry5, notEquals);
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Leaf With Null Value")
    void leafWithNullValue() {
        final MerkleMapEntry<Key, Value> leaf01 = new MerkleMapEntry<>(new Key(new long[] {1, 1, 1}), null);

        assertNull(leaf01.getValue(), "Par was initialized with a null value");
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Copy Test")
    void copyTest() {
        final MerkleMapEntry<Key, Value> entry = new MerkleMapEntry<>(
                new Key(new long[] {1, 1, 1}), Value.newBuilder().build());

        final MerkleMapEntry<Key, Value> copy = entry.copy();
        assertNotSame(entry, copy, "copy should return a new object");
        assertSame(entry.getRoute(), copy.getRoute(), "copy should recycle original route");
        assertEquals(0, copy.getReservationCount(), "copy should not have any references");
        assertEquals(entry, copy, "expected new value to be equal");
        assertFalse(copy.isDestroyed(), "copy should not be destroyed");
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Copy Throws If Deleted Test")
    void copyThrowsIfDeletedTest() {
        final MerkleMapEntry<Key, Value> leaf01 = new MerkleMapEntry<>(
                new Key(new long[] {1, 1, 1}), Value.newBuilder().build());
        leaf01.release();

        assertThrows(ReferenceCountException.class, leaf01::copy, "expected this method to fail");
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("setKey fails after being destroyed")
    void setKeyAfterDestroyTest() {
        final MerkleMapEntry<Key, Value> leaf = new MerkleMapEntry<>();
        leaf.release();
        final Exception exception = assertThrows(
                ReferenceCountException.class, () -> leaf.setKey(null), "MutabilityException should have been thrown");
        assertTrue(
                exception.getMessage().startsWith("Can not set child on destroyed parent."),
                "The error message should match the one from AbstractMerkleInternal");
    }

    // Dummy class, ensures that the key is properly destroyed as per interface requirements
    private class DummyReleasableKey implements SelfSerializable, FastCopyable {

        boolean destroyed = false;

        @Override
        public FastCopyable copy() {
            return null;
        }

        @Override
        public boolean release() {
            destroyed = true;
            return true;
        }

        @Override
        public boolean isDestroyed() {
            return destroyed;
        }

        @Override
        public long getClassId() {
            return 0;
        }

        @Override
        public void serialize(final SerializableDataOutputStream out) throws IOException {}

        @Override
        public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {}

        @Override
        public int getVersion() {
            return 0;
        }
    }

    /**
     * Even though it is highly unlikely that any key type will actually need to be destroyed,
     * we promise to release it in the contract and so we need to verify that we release it in a test.
     */
    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("setKey fails after being destroyed")
    void keyIsReleased() {
        final DummyReleasableKey key = new DummyReleasableKey();

        final MerkleMapEntry<DummyReleasableKey, MerkleLong> entry = new MerkleMapEntry<>(key, null);
        entry.release();

        assertTrue(key.isDestroyed(), "key should have been destroyed");
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.map;

import static com.swirlds.merkle.test.fixtures.map.util.ConfigUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.utility.KeyedMerkleLong;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.merkle.map.internal.MerkleMapEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("MerkleMapEntry Tests")
class MerkleMapEntryTests {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Getter And Setter Test")
    void getterAndSetterTest() {

        // Use default constructor
        final MerkleMapEntry<SerializableLong, KeyedMerkleLong<SerializableLong>> entry1 = new MerkleMapEntry<>();
        assertNull(entry1.getKey(), "key should not yet be set");
        assertNull(entry1.getValue(), "value should not yet be set");

        final SerializableLong key1 = new SerializableLong(1);
        entry1.setKey(key1);
        assertSame(key1, entry1.getKey(), "should return key that was set");

        final KeyedMerkleLong<SerializableLong> value1 = new KeyedMerkleLong<>(1);
        entry1.setValue(value1);
        assertSame(value1, entry1.getValue(), "should return value that was set");

        // Pass just the value to the constructor
        final MerkleMapEntry<SerializableLong, KeyedMerkleLong<SerializableLong>> entry2 = new MerkleMapEntry<>(value1);

        assertNull(entry2.getKey(), "key should not yet be set");
        assertSame(value1, entry2.getValue(), "value should match value from constructor");

        final SerializableLong key2 = new SerializableLong(2);
        entry2.setKey(key2);
        assertSame(key2, entry2.getKey(), "should return key that was set");

        final KeyedMerkleLong<SerializableLong> value2 = new KeyedMerkleLong<>(2);
        entry2.setValue(value2);
        assertSame(value2, entry2.getValue(), "should return value that was set");

        // Pass the key and the value to the constructor
        final MerkleMapEntry<SerializableLong, KeyedMerkleLong<SerializableLong>> entry3 =
                new MerkleMapEntry<>(key1, value1);

        assertSame(key1, entry3.getKey(), "key should match key from constructor");
        assertSame(value1, entry3.getValue(), "value should match value from constructor");

        final SerializableLong key3 = new SerializableLong(3);
        entry3.setKey(key3);
        assertSame(key3, entry3.getKey(), "should return key that was set");

        final KeyedMerkleLong<SerializableLong> value3 = new KeyedMerkleLong<>(3);
        entry3.setValue(value3);
        assertSame(value3, entry3.getValue(), "should return value that was set");
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Equals and Hash Test")
    void equalsAndHashTest() {
        final MerkleMapEntry<SerializableLong, KeyedMerkleLong<SerializableLong>> entry1 =
                new MerkleMapEntry<>(new SerializableLong(1), new KeyedMerkleLong<>(1));

        final MerkleMapEntry<SerializableLong, KeyedMerkleLong<SerializableLong>> entry2 =
                new MerkleMapEntry<>(new SerializableLong(1), new KeyedMerkleLong<>(1));

        final MerkleMapEntry<SerializableLong, KeyedMerkleLong<SerializableLong>> entry3 =
                new MerkleMapEntry<>(new SerializableLong(2), new KeyedMerkleLong<>(1));

        final MerkleMapEntry<SerializableLong, KeyedMerkleLong<SerializableLong>> entry4 =
                new MerkleMapEntry<>(new SerializableLong(1), new KeyedMerkleLong<>(2));

        final String equals = "expected objects to be equal";
        final String notEquals = "expected objects to not be equal";

        assertEquals(entry1, entry1, equals);
        assertEquals(entry1, entry2, equals);
        assertNotEquals(entry1, entry3, notEquals);
        assertNotEquals(entry1, entry4, notEquals);

        assertEquals(entry2, entry1, equals);
        assertEquals(entry2, entry2, equals);
        assertNotEquals(entry2, entry3, notEquals);
        assertNotEquals(entry2, entry4, notEquals);

        assertNotEquals(entry3, entry1, notEquals);
        assertNotEquals(entry3, entry2, notEquals);
        assertEquals(entry3, entry3, equals);
        assertNotEquals(entry3, entry4, notEquals);

        assertNotEquals(entry4, entry1, notEquals);
        assertNotEquals(entry4, entry2, notEquals);
        assertNotEquals(entry4, entry3, notEquals);
        assertEquals(entry4, entry4, equals);

        final String hashEquals = "expected hash to be equal";
        final String hashNotEquals = "expected hash to not be equal";

        TestMerkleCryptoFactory.getInstance().digestTreeSync(entry1);
        TestMerkleCryptoFactory.getInstance().digestTreeSync(entry2);
        TestMerkleCryptoFactory.getInstance().digestTreeSync(entry3);
        TestMerkleCryptoFactory.getInstance().digestTreeSync(entry4);

        assertEquals(entry1.getHash(), entry1.getHash(), hashEquals);
        assertEquals(entry1.getHash(), entry2.getHash(), hashEquals);
        assertNotEquals(entry1.getHash(), entry3.getHash(), hashNotEquals);
        assertNotEquals(entry1.getHash(), entry4.getHash(), hashNotEquals);

        assertEquals(entry2.getHash(), entry1.getHash(), hashEquals);
        assertEquals(entry2.getHash(), entry2.getHash(), hashEquals);
        assertNotEquals(entry2.getHash(), entry3.getHash(), hashNotEquals);
        assertNotEquals(entry2.getHash(), entry4.getHash(), hashNotEquals);

        assertNotEquals(entry3.getHash(), entry1.getHash(), hashNotEquals);
        assertNotEquals(entry3.getHash(), entry2.getHash(), hashNotEquals);
        assertEquals(entry3.getHash(), entry3.getHash(), hashEquals);
        assertNotEquals(entry3.getHash(), entry4.getHash(), hashNotEquals);

        assertNotEquals(entry4.getHash(), entry1.getHash(), hashNotEquals);
        assertNotEquals(entry4.getHash(), entry2.getHash(), hashNotEquals);
        assertNotEquals(entry4.getHash(), entry3.getHash(), hashNotEquals);
        assertEquals(entry4.getHash(), entry4.getHash(), hashEquals);
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Serialization Test")
    void serializationTest() throws IOException, ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.merkle.map");
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("org.hiero");

        final MerkleMapEntry<SerializableLong, KeyedMerkleLong<SerializableLong>> entry =
                new MerkleMapEntry<>(new SerializableLong(1), new KeyedMerkleLong<>(1));

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final MerkleDataOutputStream merkleOut = new MerkleDataOutputStream(byteOut);

        merkleOut.writeMerkleTree(testDirectory, entry);

        final MerkleDataInputStream merkleIn =
                new MerkleDataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));

        final MerkleMapEntry<SerializableLong, KeyedMerkleLong<SerializableLong>> deserializedEntry =
                merkleIn.readMerkleTree(CONFIGURATION, testDirectory, Integer.MAX_VALUE);

        assertEquals(entry, deserializedEntry, "deserialized entry should match");
        assertNotSame(
                entry, deserializedEntry, "there is no way that the new object should be the same as the old one");
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Copy Test")
    void copyTest() {
        final MerkleMapEntry<SerializableLong, KeyedMerkleLong<SerializableLong>> entry =
                new MerkleMapEntry<>(new SerializableLong(1), new KeyedMerkleLong<>(1));

        final MerkleMapEntry<SerializableLong, KeyedMerkleLong<SerializableLong>> copy = entry.copy();

        assertEquals(entry, copy, "deserialized entry should match");
        assertNotSame(entry, copy, "object should not be the same");
        assertNotSame(entry.getKey(), copy.getKey(), "object should not be the same");
        assertNotSame(entry.getValue(), copy.getValue(), "object should not be the same");
    }
}

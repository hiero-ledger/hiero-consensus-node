// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyProvider;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.merkle.map.internal.MerkleMapEntryKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("MerkleMapEntryKey Tests")
class MerkleMapEntryKeyTests {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @BeforeAll
    static void setup() throws ConstructableRegistryException {
        ConstructableRegistry.getInstance().registerConstructables("*");
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Getter And Setter Test")
    void getterAndSetterTest() {
        // Default constructor
        final MerkleMapEntryKey<SerializableLong> key1 = new MerkleMapEntryKey<>();
        assertNull(key1.getKey(), "key has not yet been set");

        final SerializableLong innerKey1 = new SerializableLong(1);
        key1.setKey(innerKey1);
        assertSame(innerKey1, key1.getKey(), "key should be the object that was set");

        // Key in constructor
        final MerkleMapEntryKey<SerializableLong> key2 = new MerkleMapEntryKey<>(innerKey1);
        assertSame(innerKey1, key2.getKey(), "key should be the object set in the constructor");

        final SerializableLong innerKey2 = new SerializableLong(2);
        key2.setKey(innerKey2);
        assertSame(innerKey2, key2.getKey(), "key should be the object that was set");
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Equals and Hash Test")
    void equalsAndHashTest() {
        final Cryptography cryptography = CryptographyProvider.getInstance();
        final MerkleMapEntryKey<SerializableLong> key1 = new MerkleMapEntryKey<>(new SerializableLong(1));
        final MerkleMapEntryKey<SerializableLong> key2 = new MerkleMapEntryKey<>(new SerializableLong(1));
        final MerkleMapEntryKey<SerializableLong> key3 = new MerkleMapEntryKey<>(new SerializableLong(2));

        final String equals = "expected objects to be equal";
        final String notEquals = "expected objects to not be equal";

        assertEquals(key1, key1, equals);
        assertEquals(key1, key2, equals);
        assertNotEquals(key1, key3, notEquals);

        assertEquals(key2, key1, equals);
        assertEquals(key2, key2, equals);
        assertNotEquals(key2, key3, notEquals);

        assertNotEquals(key3, key1, notEquals);
        assertNotEquals(key3, key2, notEquals);
        assertEquals(key3, key3, equals);

        final String hashEquals = "expected hash to be equal";
        final String hashNotEquals = "expected hash to not be equal";

        cryptography.digestSync(key1);
        cryptography.digestSync(key2);
        cryptography.digestSync(key3);

        assertEquals(key1.getHash(), key1.getHash(), hashEquals);
        assertEquals(key1.getHash(), key2.getHash(), hashEquals);
        assertNotEquals(key1.getHash(), key3.getHash(), hashNotEquals);

        assertEquals(key2.getHash(), key1.getHash(), hashEquals);
        assertEquals(key2.getHash(), key2.getHash(), hashEquals);
        assertNotEquals(key2.getHash(), key3.getHash(), hashNotEquals);

        assertNotEquals(key3.getHash(), key1.getHash(), hashNotEquals);
        assertNotEquals(key3.getHash(), key2.getHash(), hashNotEquals);
        assertEquals(key3.getHash(), key3.getHash(), hashEquals);
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Serialization Test")
    void serializationTest() throws IOException, ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.merkle.map");
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("org.hiero.consensus");

        final MerkleMapEntryKey<SerializableLong> key = new MerkleMapEntryKey<>(new SerializableLong(1));

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final MerkleDataOutputStream merkleOut = new MerkleDataOutputStream(byteOut);

        merkleOut.writeMerkleTree(testDirectory, key);

        final MerkleDataInputStream merkleIn =
                new MerkleDataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));

        final MerkleMapEntryKey<SerializableLong> deserializedKey =
                merkleIn.readMerkleTree(testDirectory, Integer.MAX_VALUE);

        assertEquals(key, deserializedKey, "deserialized key should match");
        assertNotSame(key, deserializedKey, "there is no way that the new object should be the same as the old one");
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Copy Test")
    void copyTest() {
        final MerkleMapEntryKey<SerializableLong> key = new MerkleMapEntryKey<>(new SerializableLong(1));

        final MerkleMapEntryKey<SerializableLong> copy = key.copy();

        assertEquals(key, copy, "copy should be equal to original");
        assertNotSame(key, copy, "copy should be a new object");
        assertNotSame(key.getKey(), copy.getKey(), "inner key should be copied");
    }
}

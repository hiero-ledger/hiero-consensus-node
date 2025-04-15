// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import java.io.IOException;
import java.util.Random;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class VirtualLeafRecordTest {
    private static final long FAKE_KEY_NUM = -1000;
    private static final long DIFFERENT_KEY_NUM = -2000;
    private static final Random RANDOM = new Random(49);

    @BeforeAll
    public static void globalSetup() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructable(new ClassConstructorPair(VirtualLeafRecord.class, VirtualLeafRecord::new));
        registry.registerConstructable(new ClassConstructorPair(TestKey.class, TestKey::new));
        registry.registerConstructable(new ClassConstructorPair(TestValue.class, TestValue::new));
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Using the default Constructor works")
    void createLeafRecordUsingDefaultConstructor() {
        final VirtualLeafRecord<TestKey, TestValue> rec = new VirtualLeafRecord<>();
        assertNull(rec.getKey(), "key should be null");
        assertNull(rec.getValue(), "value should be null");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Using the full constructor works")
    void createLeafRecordUsingFullConstructor() {
        final TestKey key = new TestKey(FAKE_KEY_NUM);
        final TestValue value = new TestValue("Fake value");
        final VirtualLeafRecord<TestKey, TestValue> rec = new VirtualLeafRecord<>(102, key, value);
        assertEquals(key, rec.getKey(), "key should match original");
        assertEquals(value, rec.getValue(), "value should match original");
        assertEquals(102, rec.getPath(), "path should match value set");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("toString with a null elements is OK")
    void toStringWithNullElementsDoesNotThrow() {
        final VirtualLeafRecord<TestKey, TestValue> rec = new VirtualLeafRecord<>();
        final String str = rec.toString();
        assertNotNull(str, "value should not be null");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Identity equals")
    void identityEqualsWorks() {
        final TestKey key = new TestKey(FAKE_KEY_NUM);
        final TestValue value = new TestValue("Fake value");
        final VirtualLeafRecord<TestKey, TestValue> rec = new VirtualLeafRecord<>(102, key, value);
        assertEquals(rec, rec, "records should be equal");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Equal instances")
    void equalInstances() {
        final TestKey key = new TestKey(FAKE_KEY_NUM);
        final TestValue value = new TestValue("Fake value");
        final VirtualLeafRecord<TestKey, TestValue> rec = new VirtualLeafRecord<>(102, key, value);
        final VirtualLeafRecord<TestKey, TestValue> rec2 = new VirtualLeafRecord<>(102, key, value);
        assertEquals(rec, rec2, "records should be equal");
        assertEquals(rec2, rec, "records should be equal");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Unequal instances")
    void unequalInstances() {
        final TestKey key = new TestKey(FAKE_KEY_NUM);
        final TestValue value = new TestValue("Fake value");
        final VirtualLeafRecord<TestKey, TestValue> first = new VirtualLeafRecord<>(102, key, value);

        // Test with null
        //noinspection ConstantConditions,SimplifiableAssertion
        assertFalse(first.equals(null), "should not be equal with null");

        // Test with a different path
        VirtualLeafRecord<TestKey, TestValue> second = new VirtualLeafRecord<>(988, key, value);
        assertNotEquals(first, second, "records should not be equal");
        assertNotEquals(second, first, "records should not be equal");

        // Test with a different key
        final TestKey differentKey = new TestKey(DIFFERENT_KEY_NUM);
        second = new VirtualLeafRecord<>(102, differentKey, value);
        assertNotEquals(first, second, "records should not be equal");
        assertNotEquals(second, first, "records should not be equal");

        // Test with a null key
        second = new VirtualLeafRecord<>(102, null, value);
        assertNotEquals(first, second, "records should not be equal");
        assertNotEquals(second, first, "records should not be equal");

        // Test with a different value
        final TestValue differentValue = new TestValue("Different value");
        second = new VirtualLeafRecord<>(102, key, differentValue);
        assertNotEquals(first, second, "records should not be equal");
        assertNotEquals(second, first, "records should not be equal");

        // Test with a null value
        second = new VirtualLeafRecord<>(102, key, null);
        assertNotEquals(first, second, "records should not be equal");
        assertNotEquals(second, first, "records should not be equal");

        // Test with some random object
        final String random = "Random!";
        //noinspection AssertBetweenInconvertibleTypes
        assertNotEquals(first, random, "records should not be equal");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("hashCode")
    void testHashCode() {
        final TestKey key = new TestKey(FAKE_KEY_NUM);
        final TestValue value = new TestValue("Fake value");
        final VirtualLeafRecord<TestKey, TestValue> rec = new VirtualLeafRecord<>(102, key, value);
        final int hash1 = rec.hashCode();

        // Test the identity
        VirtualLeafRecord<TestKey, TestValue> second = new VirtualLeafRecord<>(102, key, value);
        assertEquals(hash1, second.hashCode(), "hash should match original");

        // Create a variant with a different path and assert the hashCode is different
        second = new VirtualLeafRecord<>(988, key, value);
        assertNotEquals(hash1, second.hashCode(), "hash should not be the same");

        // Test with a different key
        final TestKey differentKey = new TestKey(DIFFERENT_KEY_NUM);
        second = new VirtualLeafRecord<>(102, differentKey, value);
        assertNotEquals(hash1, second.hashCode(), "hash should not be the same");

        // Test with a null key
        second = new VirtualLeafRecord<>(102, null, value);
        assertNotEquals(hash1, second.hashCode(), "hash should not be the same");

        // Test with a different value
        final TestValue differentValue = new TestValue("Different value");
        second = new VirtualLeafRecord<>(102, key, differentValue);
        assertNotEquals(hash1, second.hashCode(), "hash should not be the same");

        // Test with a null value
        second = new VirtualLeafRecord<>(102, key, null);
        assertNotEquals(hash1, second.hashCode(), "hash should not be the same");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Test copy")
    void testCopy() {
        final long keyId = RANDOM.nextLong();
        final TestKey key = new TestKey(keyId);
        final TestValue value = new TestValue("This is a custom value");

        final VirtualLeafRecord<TestKey, TestValue> leafRecord = new VirtualLeafRecord<>(1329, key, value);

        assertEquals(leafRecord, leafRecord.copy(), "Copy should be equal to original");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Serialization and deserialization")
    void serializesAndDeserializes() throws IOException {
        final long keyId = RANDOM.nextLong();
        final TestKey key = new TestKey(keyId);
        final TestValue value = new TestValue("This is a custom value");
        final long path = 1329;
        final VirtualLeafRecord<TestKey, TestValue> leafRecord = new VirtualLeafRecord<>(path, key, value);

        try (final InputOutputStream ioStream = new InputOutputStream()) {
            ioStream.getOutput().writeSerializable(leafRecord, true);
            ioStream.startReading();
            final VirtualLeafRecord<TestKey, TestValue> deserializedLeafRecord =
                    ioStream.getInput().readSerializable();

            assertEquals(leafRecord, deserializedLeafRecord, "Deserialized leaf should match original");
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.test.fixtures.dummy.Key;
import com.swirlds.common.test.fixtures.dummy.Value;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.test.fixtures.merkle.util.MerkleSerializeUtils;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.util.KeyValueProvider;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MMSerializeTests {

    private static MerkleCryptography cryptography;
    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @BeforeAll
    public static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds");
        registry.registerConstructables("org.hiero.consensus");
        cryptography = TestMerkleCryptoFactory.getInstance();
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Add value to deserialized map")
    void serializeDeserializeAdd() throws IOException {
        final MerkleMap<Key, Value> fcm = new MerkleMap<>();
        KeyValueProvider.KEY_VALUE.insertIntoMap(0, 100, fcm);
        cryptography.digestTreeSync(fcm);
        final MerkleMap<Key, Value> serDes = MerkleSerializeUtils.serializeDeserialize(testDirectory, fcm);
        cryptography.digestTreeSync(serDes);
        KeyValueProvider.KEY_VALUE.insertIntoMap(100, 200, serDes);
        assertEquals(200, serDes.size(), "expected to deserialize 200 entries");
        final Value value = serDes.get(new Key(0, 0, 0));
        assertNotNull(value, "value should be present in the map");

        fcm.release();
        serDes.release();
    }
}

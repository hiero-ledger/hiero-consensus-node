// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map;

import static com.swirlds.merkle.test.fixtures.map.lifecycle.SaveExpectedMapHandler.deserialize;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.SaveExpectedMapHandler.serializeThrowing;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType;
import com.swirlds.merkle.test.fixtures.map.lifecycle.ExpectedValue;
import com.swirlds.merkle.test.fixtures.map.lifecycle.LifecycleStatus;
import com.swirlds.merkle.test.fixtures.map.lifecycle.SaveExpectedMapHandler;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test class to serialize and deserialize the ExpectedMap to/from JSON in the class SaveExpectedMapHandler
 */
public class SaveExpectedMapHandlerTest {
    private static Map<MapKey, ExpectedValue> expectedMap;
    private static Map<MapKey, ExpectedValue> deserializedMap;
    private static Random random = new Random();
    private static SaveExpectedMapHandler handler;
    private static final int contentSize = 48;
    private static final String expectedMapName = "ExpectedMap.json";
    private static final String expectedMapZip = "ExpectedMap.json.gz";

    /**
     * Temporary test directory.
     */
    @TempDir
    private Path tmpDir;

    @BeforeEach
    public void init() {
        expectedMap = new HashMap<>();
        deserializedMap = new HashMap<>();
        handler = new SaveExpectedMapHandler();
        addToMap();
    }

    // Add Keys to ExpectedMap
    private void addToMap() {
        for (int i = 0; i < 20; i++) {
            MapKey mk = new MapKey(0, 0, i);

            LifecycleStatus submitLS = new LifecycleStatus(
                    TransactionState.SUBMITTED, Update, Instant.now().getEpochSecond(), i);
            LifecycleStatus handleLS = new LifecycleStatus(
                    TransactionState.HANDLED, Update, Instant.now().getEpochSecond(), i);

            expectedMap.put(
                    mk,
                    new ExpectedValue(
                            EntityType.Crypto, new Hash(generateRandomContent()), true, submitLS, handleLS, null, 0));
        }
    }

    // Add invalid keys to expectedMap
    private void addInvalidKeysToMap() {
        for (int i = 20; i < 25; i++) {
            MapKey mk = new MapKey(0, 0, i);
            expectedMap.put(mk, new ExpectedValue(null, new Hash(generateRandomContent()), false, null, null, null, 0));
        }
    }

    // generate random bytes to generate Hash
    private byte[] generateRandomContent() {
        final byte[] content = new byte[contentSize];
        random.nextBytes(content);
        return content;
    }

    // Serializes and deserializes the expected map with all valid keys
    @Test
    public void serializeAndDeserializePositiveTest() throws IOException {
        final String jsonValue = serializeThrowing(expectedMap, new File(tmpDir.toString()), expectedMapName, true);
        for (int i = 0; i < 20; i++) {
            assertTrue(jsonValue.contains("[0,0," + i + "]"));
        }

        deserializedMap = deserialize(new File(tmpDir.toString(), expectedMapZip));

        assertEquals(
                expectedMap.size(),
                deserializedMap.size(),
                "Size of the maps should be equal, expected: %d, actual: %d"
                        .formatted(expectedMap.size(), deserializedMap.size()));
        expectedMap.entrySet().stream()
                .forEach(e -> assertEquals(
                        e.getValue(),
                        deserializedMap.get(e.getKey()),
                        "Expected value should be equal to deserialized value. Expected: %s, Actual: %s"
                                .formatted(e.getValue(), deserializedMap.get(e.getKey()))));
    }

    // serializes and deserializes expectedMap with null EntityType ExpectedValues.
    @Test
    public void DeserializeNullEntityTypeTest() throws IOException {
        addInvalidKeysToMap();
        final String jsonValue = serializeThrowing(expectedMap, new File(tmpDir.toString()), expectedMapName, true);

        for (int i = 20; i < 25; i++) {
            assertTrue(jsonValue.contains("MapKey[0,0," + i + "]"));
        }

        deserializedMap = deserialize(new File(tmpDir.toString(), expectedMapZip));
        assertEquals(null, deserializedMap.get(new MapKey(0, 0, 24)).getEntityType());
    }
}

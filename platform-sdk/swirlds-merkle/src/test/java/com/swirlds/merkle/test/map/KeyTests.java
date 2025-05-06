// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.swirlds.common.test.fixtures.dummy.Key;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
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

@DisplayName("Key Tests")
class KeyTests {

    private static final Random RANDOM = new Random();

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();

        registry.registerConstructables("com.swirlds.merkle.map");

        // FUTURE WORK this is a hack
        // It is required for when this test is run via an intellij configuration
        registry.registerConstructable(new ClassConstructorPair(Key.class, Key::new));
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Equals Compare To Null")
    void equalsCompareToNull() {
        final Key key = new Key(new long[] {1L, 2L, 3L});
        final Key nullKey = null;
        assertNotEquals(nullKey, key, "key should not equal null");
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Serialize And Deserialize Test")
    void serializeAndDeserializeTest() throws IOException {
        final long shardId = RANDOM.nextLong();
        final long realmId = RANDOM.nextLong();
        final long accountId = RANDOM.nextLong();

        final Key key = new Key(shardId, realmId, accountId);

        try (final InputOutputStream io = new InputOutputStream()) {
            io.getOutput().writeSerializable(key, true);
            io.startReading();

            final Key deserializedKey = io.getInput().readSerializable();
            assertEquals(key, deserializedKey, "deserialized key should match");
        }
    }
}

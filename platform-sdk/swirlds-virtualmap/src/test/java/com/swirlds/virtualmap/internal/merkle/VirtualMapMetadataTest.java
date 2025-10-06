// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata.MAX_LABEL_CHARS;
import static org.hiero.base.utility.test.fixtures.RandomUtils.nextInt;
import static org.hiero.base.utility.test.fixtures.RandomUtils.randomString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.virtualmap.internal.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VirtualMapMetadataTest {

    public static final Random RANDOM = new Random();

    @Test
    void testDefaultConstructor() {
        VirtualMapMetadata state = new VirtualMapMetadata("test");
        assertEquals(-1, state.getFirstLeafPath(), "Default firstLeafPath should be -1");
        assertEquals(-1, state.getLastLeafPath(), "Default lastLeafPath should be -1");
        assertEquals(0, state.getSize(), "Size should be 0 when no leaves");

        assertThrows(NullPointerException.class, () -> new VirtualMapMetadata((String) null));
    }

    @Test
    void testConstructorWithLabel() {
        String label = "TestLabel";
        VirtualMapMetadata state = new VirtualMapMetadata(label);
        assertEquals(-1, state.getFirstLeafPath(), "Expected firstLeafPath to be -1 by default");
        assertEquals(-1, state.getLastLeafPath(), "Expected lastLeafPath to be -1 by default");
        assertEquals(label, state.getLabel(), "Expected label to match constructor argument");
        assertEquals(0, state.getSize(), "Size should be 0 when no leaves");
    }

    @Test
    void testValidPaths() {
        VirtualMapMetadata state = new VirtualMapMetadata("test");
        int firstLeafPath = nextInt(1, 100);
        int lastLeafPath = nextInt(firstLeafPath + 1, firstLeafPath * 2);
        state.setLastLeafPath(lastLeafPath);
        state.setFirstLeafPath(firstLeafPath);
        assertEquals(firstLeafPath, state.getFirstLeafPath());
        assertEquals(lastLeafPath, state.getLastLeafPath());
    }

    @Test
    void testInvalidFirstLeafPath() {
        VirtualMapMetadata state = new VirtualMapMetadata("test");
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
        VirtualMapMetadata state = new VirtualMapMetadata("test");
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
        VirtualMapMetadata state = new VirtualMapMetadata(initLabel);

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
        VirtualMapMetadata state = new VirtualMapMetadata("test");

        int firstLeafPath = nextInt(1, 100);
        int lastLeafPath = firstLeafPath * 2;
        state.setLastLeafPath(lastLeafPath);
        state.setFirstLeafPath(firstLeafPath);

        assertEquals(lastLeafPath - firstLeafPath + 1, state.getSize());
    }
}

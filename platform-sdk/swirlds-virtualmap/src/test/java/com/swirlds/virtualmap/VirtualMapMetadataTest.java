// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static org.hiero.base.utility.test.fixtures.RandomUtils.nextInt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.virtualmap.internal.Path;
import org.junit.jupiter.api.Test;

class VirtualMapMetadataTest {

    @Test
    void testDefaultConstructor() {
        VirtualMap.Metadata metadata = new VirtualMap.Metadata();
        assertEquals(-1, metadata.getFirstLeafPath(), "Default firstLeafPath should be -1");
        assertEquals(-1, metadata.getLastLeafPath(), "Default lastLeafPath should be -1");
        assertEquals(0, metadata.getSize(), "Size should be 0 when no leaves");
    }

    @Test
    void testConstructorWithLabel() {
        VirtualMap.Metadata metadata = new VirtualMap.Metadata();
        assertEquals(-1, metadata.getFirstLeafPath(), "Expected firstLeafPath to be -1 by default");
        assertEquals(-1, metadata.getLastLeafPath(), "Expected lastLeafPath to be -1 by default");
        assertEquals(0, metadata.getSize(), "Size should be 0 when no leaves");
    }

    @Test
    void testValidPaths() {
        int firstLeafPath = nextInt(1, 100);
        int lastLeafPath = nextInt(firstLeafPath + 1, firstLeafPath * 2);
        VirtualMap.Metadata metadata = new VirtualMap.Metadata(firstLeafPath, lastLeafPath);

        assertEquals(firstLeafPath, metadata.getFirstLeafPath());
        assertEquals(lastLeafPath, metadata.getLastLeafPath());
    }

    @Test
    void testInvalidFirstLeafPath() {
        int firstLeafPath = nextInt(1, 100);
        int lastLeafPath = firstLeafPath * 2;
        VirtualMap.Metadata metadata = new VirtualMap.Metadata(firstLeafPath, lastLeafPath);

        assertThrows(
                IllegalArgumentException.class,
                () -> metadata.setFirstLeafPath(0),
                "Setting firstLeafPath to 0 should throw an exception");
        assertThrows(
                IllegalArgumentException.class,
                () -> metadata.setFirstLeafPath(nextInt(Integer.MIN_VALUE, -2)),
                "Setting firstLeafPath to a negative value should throw an exception");
        assertThrows(
                IllegalArgumentException.class,
                () -> metadata.setFirstLeafPath(lastLeafPath + 1),
                "Setting firstLeafPath to a path greater than lastLeafPath should throw an exception");

        // Path.INVALID_PATH is allowed
        metadata.setFirstLeafPath(Path.INVALID_PATH);
    }

    @Test
    void testInvalidLastLeafPath() {
        int firstLeafPath = nextInt(1, 100);
        int lastLeafPath = firstLeafPath * 2;
        VirtualMap.Metadata metadata = new VirtualMap.Metadata(firstLeafPath, lastLeafPath);

        assertThrows(
                IllegalArgumentException.class,
                () -> metadata.setLastLeafPath(0),
                "Setting lastLeafPath to 0 should throw an exception");
        assertThrows(
                IllegalArgumentException.class,
                () -> metadata.setLastLeafPath(nextInt(Integer.MIN_VALUE, -2)),
                "Setting lastLeafPath to a negative value should throw an exception");
        assertThrows(
                IllegalArgumentException.class,
                () -> metadata.setLastLeafPath(firstLeafPath - 1),
                "Setting lastLeafPath lesser than firstLeafPath should throw an exception");

        // Path.INVALID_PATH is allowed
        metadata.setLastLeafPath(Path.INVALID_PATH);
    }

    @Test
    void testGetSize() {
        int firstLeafPath = nextInt(1, 100);
        int lastLeafPath = firstLeafPath * 2;
        VirtualMap.Metadata metadata = new VirtualMap.Metadata(firstLeafPath, lastLeafPath);

        assertEquals(lastLeafPath - firstLeafPath + 1, metadata.getSize());
    }
}

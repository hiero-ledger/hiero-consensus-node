// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PullVirtualTreeResponseTest {

    @Test
    @DisplayName("Round-trip: root response with leaf path range")
    void roundTripRootResponse() {
        final PullVirtualTreeResponse original = new PullVirtualTreeResponse(Path.ROOT_PATH, false, 10, 99, null);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeResponse deserialized = PullVirtualTreeResponse.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertEquals(Path.ROOT_PATH, deserialized.getPath());
        assertFalse(deserialized.isClean());
        assertEquals(10, deserialized.getFirstLeafPath());
        assertEquals(99, deserialized.getLastLeafPath());
        assertNull(deserialized.getLeafData());
    }

    @Test
    @DisplayName("Round-trip: clean root response")
    void roundTripCleanRootResponse() {
        final PullVirtualTreeResponse original = new PullVirtualTreeResponse(Path.ROOT_PATH, true, 5, 20, null);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeResponse deserialized = PullVirtualTreeResponse.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertTrue(deserialized.isClean());
        assertEquals(5, deserialized.getFirstLeafPath());
        assertEquals(20, deserialized.getLastLeafPath());
    }

    @Test
    @DisplayName("Round-trip: clean internal node (no leaf data, no path range)")
    void roundTripCleanInternal() {
        final PullVirtualTreeResponse original = new PullVirtualTreeResponse(3, true, -1, -1, null);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeResponse deserialized = PullVirtualTreeResponse.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertEquals(3, deserialized.getPath());
        assertTrue(deserialized.isClean());
        assertEquals(-1, deserialized.getFirstLeafPath());
        assertEquals(-1, deserialized.getLastLeafPath());
        assertNull(deserialized.getLeafData());
    }

    @Test
    @DisplayName("Round-trip: dirty internal node (no leaf data)")
    void roundTripDirtyInternal() {
        final PullVirtualTreeResponse original = new PullVirtualTreeResponse(2, false, -1, -1, null);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeResponse deserialized = PullVirtualTreeResponse.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertEquals(2, deserialized.getPath());
        assertFalse(deserialized.isClean());
        assertNull(deserialized.getLeafData());
    }

    @Test
    @DisplayName("Round-trip: dirty leaf with key and value")
    void roundTripDirtyLeafWithKeyAndValue() {
        final Bytes key = Bytes.wrap("test-key".getBytes());
        final Bytes value = Bytes.wrap("test-value".getBytes());
        final VirtualLeafBytes<?> leafData = new VirtualLeafBytes<>(15, key, value);

        final PullVirtualTreeResponse original = new PullVirtualTreeResponse(15, false, -1, -1, leafData);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeResponse deserialized = PullVirtualTreeResponse.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertEquals(15, deserialized.getPath());
        assertFalse(deserialized.isClean());
        assertNotNull(deserialized.getLeafData());
        assertEquals(key, deserialized.getLeafData().keyBytes());
        assertEquals(value, deserialized.getLeafData().valueBytes());
    }

    @Test
    @DisplayName("Round-trip: dirty leaf with key and null value")
    void roundTripDirtyLeafWithNullValue() {
        final Bytes key = Bytes.wrap("key-only".getBytes());
        final VirtualLeafBytes<?> leafData = new VirtualLeafBytes<>(20, key, (Bytes) null);

        final PullVirtualTreeResponse original = new PullVirtualTreeResponse(20, false, -1, -1, leafData);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeResponse deserialized = PullVirtualTreeResponse.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertNotNull(deserialized.getLeafData());
        assertEquals(key, deserialized.getLeafData().keyBytes());
        assertNull(deserialized.getLeafData().valueBytes());
    }

    @Test
    @DisplayName("Round-trip: dirty leaf with key and empty value")
    void roundTripDirtyLeafWithEmptyValue() {
        final Bytes key = Bytes.wrap("some-key".getBytes());
        final VirtualLeafBytes<?> leafData = new VirtualLeafBytes<>(25, key, Bytes.EMPTY);

        final PullVirtualTreeResponse original = new PullVirtualTreeResponse(25, false, -1, -1, leafData);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeResponse deserialized = PullVirtualTreeResponse.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertNotNull(deserialized.getLeafData());
        assertEquals(key, deserialized.getLeafData().keyBytes());
        assertEquals(Bytes.EMPTY, deserialized.getLeafData().valueBytes());
    }

    @Test
    @DisplayName("Round-trip: clean leaf (no leaf data sent)")
    void roundTripCleanLeaf() {
        final PullVirtualTreeResponse original = new PullVirtualTreeResponse(50, true, -1, -1, null);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeResponse deserialized = PullVirtualTreeResponse.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertEquals(50, deserialized.getPath());
        assertTrue(deserialized.isClean());
        assertNull(deserialized.getLeafData());
    }

    @Test
    @DisplayName("getSizeInBytes matches actual serialized size for all variants")
    void sizeMatchesActualOutput() {
        final PullVirtualTreeResponse[] variants = {
            // Root
            new PullVirtualTreeResponse(Path.ROOT_PATH, true, 1, 100, null),
            // Clean internal
            new PullVirtualTreeResponse(5, true, -1, -1, null),
            // Dirty internal
            new PullVirtualTreeResponse(3, false, -1, -1, null),
            // Dirty leaf with data
            new PullVirtualTreeResponse(
                    10,
                    false,
                    -1,
                    -1,
                    new VirtualLeafBytes<>(10, Bytes.wrap("k".getBytes()), Bytes.wrap("v".getBytes()))),
            // Dirty leaf with null value
            new PullVirtualTreeResponse(
                    11, false, -1, -1, new VirtualLeafBytes<>(11, Bytes.wrap("k2".getBytes()), (Bytes) null)),
        };

        for (final PullVirtualTreeResponse response : variants) {
            final byte[] buf = new byte[response.getSizeInBytes()];
            final BufferedData out = BufferedData.wrap(buf);
            response.writeTo(out);
            assertEquals(
                    response.getSizeInBytes(),
                    out.position(),
                    "Size mismatch for response with path=" + response.getPath());
        }
    }

    @Test
    @DisplayName("Root response is larger than non-root due to leaf path fields")
    void rootResponseLargerThanNonRoot() {
        final PullVirtualTreeResponse root = new PullVirtualTreeResponse(Path.ROOT_PATH, true, 1, 100, null);
        final PullVirtualTreeResponse nonRoot = new PullVirtualTreeResponse(5, true, -1, -1, null);

        // Root includes two extra INT64 fields
        assertTrue(root.getSizeInBytes() > nonRoot.getSizeInBytes());
    }

    @Test
    @DisplayName("Large key and value bytes round-trip correctly")
    void largeKeyAndValue() {
        final byte[] largeKeyBytes = new byte[4096];
        final byte[] largeValueBytes = new byte[8192];
        for (int i = 0; i < largeKeyBytes.length; i++) {
            largeKeyBytes[i] = (byte) (i & 0xFF);
        }
        for (int i = 0; i < largeValueBytes.length; i++) {
            largeValueBytes[i] = (byte) ((i * 3) & 0xFF);
        }
        final Bytes key = Bytes.wrap(largeKeyBytes);
        final Bytes value = Bytes.wrap(largeValueBytes);
        final VirtualLeafBytes<?> leafData = new VirtualLeafBytes<>(100, key, value);

        final PullVirtualTreeResponse original = new PullVirtualTreeResponse(100, false, -1, -1, leafData);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeResponse deserialized = PullVirtualTreeResponse.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertNotNull(deserialized.getLeafData());
        assertEquals(key, deserialized.getLeafData().keyBytes());
        assertEquals(value, deserialized.getLeafData().valueBytes());
    }
}

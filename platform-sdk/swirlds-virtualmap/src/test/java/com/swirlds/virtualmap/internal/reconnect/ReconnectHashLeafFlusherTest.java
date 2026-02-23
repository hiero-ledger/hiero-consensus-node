// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.virtualmap.test.fixtures.TestKey.longToKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import com.swirlds.virtualmap.test.fixtures.InMemoryDataSource;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import java.nio.ByteBuffer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class ReconnectHashLeafFlusherTest {

    public static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .withConfigDataType(VirtualMapConfig.class)
            .withConfigDataType(TemporaryFileConfig.class)
            .withConfigDataType(StateCommonConfig.class)
            .build();

    public static final VirtualMapConfig VIRTUAL_MAP_CONFIG = CONFIGURATION.getConfigData(VirtualMapConfig.class);

    @Test
    void testNullDataSourceThrows() {
        final VirtualMapStatistics stats = new VirtualMapStatistics("testNadLeafPaths");
        assertThrows(
                NullPointerException.class,
                () -> new ReconnectHashLeafFlusher(null, VIRTUAL_MAP_CONFIG.reconnectFlushInterval(), stats));
    }

    @Test
    void testNullStatsThrows() {
        final VirtualDataSource ds = new InMemoryDataSource("testNullStatsThrows");
        assertThrows(
                NullPointerException.class,
                () -> new ReconnectHashLeafFlusher(ds, VIRTUAL_MAP_CONFIG.reconnectFlushInterval(), null));
    }

    @ParameterizedTest
    @CsvSource({
        "-2,  1", // Invalid negative first, good last
        " 1, -2", // Good first, invalid negative last
        " 0,  1", // Invalid zero first, good last
        " 1,  0", // Good first, invalid zero last
        " 0,  0", // Both invalid
        " 9, 8"
    }) // Invalid (both should be equal only if == 1
    @DisplayName("Illegal first and last leaf path combinations throw")
    void testNadLeafPaths(long firstLeafPath, long lastLeafPath) {
        final VirtualDataSource ds = new InMemoryDataSource("testNadLeafPaths");
        final VirtualMapStatistics stats = new VirtualMapStatistics("testNadLeafPaths");
        final ReconnectHashLeafFlusher flusher =
                new ReconnectHashLeafFlusher(ds, VIRTUAL_MAP_CONFIG.reconnectFlushInterval(), stats);
        assertThrows(IllegalArgumentException.class, () -> flusher.start(firstLeafPath, lastLeafPath));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 31})
    void testHashesFlushed(final int flushInterval) throws Exception {
        final VirtualDataSource ds = new InMemoryDataSource("testHashesFlushed");
        final VirtualMapStatistics stats = new VirtualMapStatistics("testHashesFlushed");
        final ReconnectHashLeafFlusher flusher = new ReconnectHashLeafFlusher(ds, flushInterval, stats);
        final int COUNT = 500;
        flusher.start(COUNT - 1, COUNT * 2 - 2);
        for (int i = 0; i < COUNT * 2 - 1; i++) {
            flusher.updateHash(i, hash(i + 2));
        }
        flusher.finish();
        assertEquals(COUNT - 1, ds.getFirstLeafPath());
        assertEquals(COUNT * 2 - 2, ds.getLastLeafPath());
        for (int i = 0; i < COUNT * 2 - 1; i++) {
            assertEquals(hash(i + 2), ds.loadHash(i));
        }
        assertNull(ds.loadHash(COUNT * 2));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 31})
    void testLeavesFlushed(final int flushInterval) throws Exception {
        final VirtualDataSource ds = new InMemoryDataSource("testLeavesFlushed");
        final VirtualMapStatistics stats = new VirtualMapStatistics("testLeavesFlushed");
        final ReconnectHashLeafFlusher flusher = new ReconnectHashLeafFlusher(ds, flushInterval, stats);
        final int COUNT = 500;
        flusher.start(COUNT - 1, COUNT * 2 - 2);
        for (int i = COUNT - 1; i < COUNT * 2 - 1; i++) {
            flusher.updateLeaf(leaf(i, i + 2, i * 2));
        }
        flusher.finish();
        assertEquals(COUNT - 1, ds.getFirstLeafPath());
        assertEquals(COUNT * 2 - 2, ds.getLastLeafPath());
        for (int i = COUNT - 1; i < COUNT * 2 - 1; i++) {
            VirtualLeafBytes bytes = ds.loadLeafRecord(i);
            assertNotNull(bytes);
            assertEquals(leaf(i, i + 2, i * 2), bytes);
            bytes = ds.loadLeafRecord(bytes.keyBytes());
            assertNotNull(bytes);
            assertEquals(i, bytes.path());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 31})
    void testLeavesDeleted(final int flushInterval) throws Exception {
        final VirtualDataSource ds = new InMemoryDataSource("testLeavesDeleted");
        final int COUNT = 500;
        ds.saveRecords(
                COUNT / 2 + 99,
                COUNT + 198,
                Stream.of(),
                IntStream.range(COUNT / 2 + 99, COUNT + 199).mapToObj(i -> leaf(i, i, i)),
                Stream.of(),
                false);
        final VirtualMapStatistics stats = new VirtualMapStatistics("testLeavesDeleted");
        final ReconnectHashLeafFlusher flusher = new ReconnectHashLeafFlusher(ds, flushInterval, stats);
        flusher.start(COUNT - 1, COUNT * 2 - 2);
        for (int i = COUNT / 2 + 99; i < COUNT - 1; i++) {
            flusher.deleteLeaf(leaf(i, i, i));
        }
        for (int i = COUNT - 1; i < COUNT * 2 - 1; i++) {
            flusher.updateLeaf(leaf(i, i + 2, i * 2));
        }
        flusher.finish();
        // I can't call loadLeafRecord(COUNT / 2 + 99), since it's outside the current ds path range. Let's
        // adjust the path range first, then check that the leaves are actually deleted
        ds.saveRecords(COUNT / 2 + 99, COUNT + 198, Stream.of(), Stream.of(), Stream.of(), false);
        for (int i = COUNT / 2 + 99; i < COUNT - 1; i++) {
            final int fi = i;
            // InMemoryDataSource.loadLeafRecord() throws an assertion error, when the record doesn't exist
            assertThrows(AssertionError.class, () -> ds.loadLeafRecord(fi));
        }
        for (int i = COUNT - 1; i < COUNT + 199; i++) {
            VirtualLeafBytes bytes = ds.loadLeafRecord(i);
            assertNotNull(bytes);
            assertEquals(leaf(i, i + 2, i * 2), bytes);
            bytes = ds.loadLeafRecord(bytes.keyBytes());
            assertNotNull(bytes);
            assertEquals(i, bytes.path());
        }
    }

    private static Hash hash(final int h) {
        final int len = DigestType.SHA_384.digestLength();
        final byte[] bytes = new byte[len];
        final ByteBuffer buf = ByteBuffer.wrap(bytes);
        for (int i = 0; i < len; i += Integer.BYTES) { // assuming len % Integer.BYTES == 0
            buf.putInt(h);
        }
        return new Hash(bytes);
    }

    private static VirtualLeafBytes leaf(final int path, final int k, final int v) {
        return new VirtualLeafBytes<>(path, longToKey(k), new TestValue(v).toBytes());
    }
}

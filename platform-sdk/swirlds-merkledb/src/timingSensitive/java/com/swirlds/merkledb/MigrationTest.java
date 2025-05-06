// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.merkledb.test.fixtures.ExampleFixedSizeVirtualValue;
import com.swirlds.merkledb.test.fixtures.ExampleFixedSizeVirtualValueSerializer;
import com.swirlds.merkledb.test.fixtures.ExampleLongKeyFixedSize;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapMigration;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// This test requires more memory than others, decide what to do with it
@Disabled("This test needs to be investigated")
class MigrationTest {

    private static final KeySerializer<ExampleLongKeyFixedSize> KEY_SERIALIZER =
            new ExampleLongKeyFixedSize.Serializer();

    private static final ValueSerializer<ExampleFixedSizeVirtualValue> VALUE_SERIALIZER =
            new ExampleFixedSizeVirtualValueSerializer();

    @Test
    @DisplayName("extractVirtualMapData() Test")
    void extractVirtualMapDataTest() throws IOException, InterruptedException {

        final int size = 5_000_000;

        // Build a virtual map.
        VirtualMap<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> map = new VirtualMap<>(
                "extractVirtualMapDataTest", KEY_SERIALIZER, VALUE_SERIALIZER, constructBuilder(), CONFIGURATION);
        for (int i = 0; i < size; i++) {
            if (((i + 1) % (size / 100) == 0)) {
                // Make a copy of the map in order to allow things to be flushed to disk
                VirtualMap<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> copy = map.copy();
                map.release();
                map = copy;
            }

            map.put(new ExampleLongKeyFixedSize(i), new ExampleFixedSizeVirtualValue(i * 2));
        }

        final List<Long> firstVisitOrder = new ArrayList<>(size);
        final List<Long> secondVisitOrder = new ArrayList<>(size);
        final Set<Long> visited = new HashSet<>();

        VirtualMapMigration.extractVirtualMapData(
                getStaticThreadManager(),
                map,
                (final Pair<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> pair) -> {
                    assertEquals(pair.key().getValue() * 2, pair.value().getId(), "key and value do not match");
                    firstVisitOrder.add(pair.key().getValue());
                    assertTrue(visited.add(pair.key().getValue()), "value should not have been already visited");
                },
                32);

        VirtualMapMigration.extractVirtualMapData(
                getStaticThreadManager(),
                map,
                (final Pair<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> pair) -> {
                    assertEquals(pair.key().getValue() * 2, pair.value().getId(), "key and value do not match");
                    secondVisitOrder.add(pair.key().getValue());
                    assertFalse(visited.add(pair.key().getValue()), "value should have already been visited");
                },
                31); // thread count should not matter for correctness

        assertEquals(size, firstVisitOrder.size(), "unexpected size");
        assertEquals(size, secondVisitOrder.size(), "unexpected size");

        for (int i = 0; i < size; i++) {
            assertEquals(firstVisitOrder.get(i), secondVisitOrder.get(i), "visitation order should be the same");
        }

        map.release();
    }

    private static long bytesToLong(byte[] bytes) {
        long result = 0L;
        for (byte b : bytes) {
            result = result * 256 + ((long) b & 0xff);
        }
        return result;
    }

    @Test
    @DisplayName("Extract VirtualMap Data Concurrently")
    void extractDataConcurrentlyTest() throws IOException, InterruptedException {

        final int size = 5_000_000;

        // Build a virtual map.
        VirtualMap<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> map = new VirtualMap<>(
                "extractDataConcurrentlyTest", KEY_SERIALIZER, VALUE_SERIALIZER, constructBuilder(), CONFIGURATION);

        final Random random = new Random(42);
        final byte[] value = new byte[ExampleFixedSizeVirtualValue.RANDOM_BYTES];
        long checkSum = 0L;
        for (int i = 0; i < size; i++) {
            if ((i + 1) % (size / 100) == 0) {
                // Make a copy of the map in order to allow things to be flushed to disk
                VirtualMap<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> copy = map.copy();
                map.release();
                map = copy;
            }

            random.nextBytes(value);
            map.put(new ExampleLongKeyFixedSize(i), new ExampleFixedSizeVirtualValue(i, value));
            checkSum += bytesToLong(value);
        }

        // Migrate the last copy concurrently
        final AtomicLong checkSum2 = new AtomicLong(0L);
        VirtualMapMigration.extractVirtualMapDataC(
                getStaticThreadManager(),
                map,
                (final Pair<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> pair) -> {
                    checkSum2.addAndGet(bytesToLong(pair.value().getData()));
                },
                32);
        assertEquals(checkSum, checkSum2.get());
    }

    /**
     * Create a new virtual map data source builder.
     */
    private static MerkleDbDataSourceBuilder constructBuilder() throws IOException {
        // The tests below create maps with identical names. They would conflict with each other in the default
        // MerkleDb instance, so let's use a new database location for every map
        final Path defaultVirtualMapPath =
                LegacyTemporaryFileBuilder.buildTemporaryFile("merkledb-source", CONFIGURATION);
        MerkleDb.setDefaultPath(defaultVirtualMapPath);
        final MerkleDbTableConfig tableConfig =
                new MerkleDbTableConfig((short) 1, DigestType.SHA_384, 1234, Long.MAX_VALUE);
        return new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
    }
}

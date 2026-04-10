// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.constructable.ConstructableRegistration;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MerkleDbBuilderTest {

    private static final long INITIAL_SIZE = 1_000_000;

    @BeforeAll
    static void setup() throws ConstructableRegistryException {
        ConstructableRegistration.registerAllConstructables();
    }

    @AfterEach
    public void afterCheckNoDbLeftOpen() {
        // check db count
        assertAllDatabasesClosed();
    }

    final MerkleDbDataSourceBuilder createDefaultBuilder() {
        return new MerkleDbDataSourceBuilder(CONFIGURATION, INITIAL_SIZE);
    }

    @ParameterizedTest
    @ValueSource(longs = {100L, 1000000L})
    @DisplayName("Test table config is passed to data source")
    public void testTableConfig(final long initialCapacity) throws IOException {
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(CONFIGURATION, initialCapacity);
        VirtualDataSource dataSource = builder.build("test1", null, false, false);
        try {
            assertInstanceOf(MerkleDbDataSource.class, dataSource);
            MerkleDbDataSource merkleDbDataSource = (MerkleDbDataSource) dataSource;
            assertEquals(initialCapacity, merkleDbDataSource.getInitialCapacity());
        } finally {
            dataSource.close(false);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Test compaction flag is passed to data source")
    public void testCompactionConfig(final boolean compactionEnabled) throws IOException {
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(CONFIGURATION, 1024);
        VirtualDataSource dataSource = builder.build("test2", null, compactionEnabled, false);
        try {
            assertInstanceOf(MerkleDbDataSource.class, dataSource);
            MerkleDbDataSource merkleDbDataSource = (MerkleDbDataSource) dataSource;
            assertEquals(compactionEnabled, merkleDbDataSource.isCompactionEnabled());
        } finally {
            dataSource.close(false);
        }
    }

    @Test
    void testSnapshot(@TempDir Path tmpDir) throws IOException {
        final String label = "testSnapshot";
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(CONFIGURATION, 1024);
        VirtualDataSource dataSource = builder.build(label, null, false, false);
        try {
            builder.snapshot(tmpDir, dataSource);
            assertTrue(Files.isDirectory(tmpDir.resolve("data").resolve(label)));
        } finally {
            dataSource.close(false);
        }
    }

    @Test
    void testSnapshotRestore(@TempDir Path tmpDir) throws IOException {
        final String label = "testSnapshotRestore";
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(CONFIGURATION, 10_000);
        VirtualDataSource dataSource = builder.build(label, null, false, false);
        try {
            builder.snapshot(tmpDir, dataSource);
            assertTrue(Files.isDirectory(tmpDir.resolve("data").resolve(label)));
            VirtualDataSource restored = builder.build(label, tmpDir, false, false);
            try {
                assertNotNull(restored);
                assertInstanceOf(MerkleDbDataSource.class, restored);
            } finally {
                restored.close(false);
            }
        } finally {
            dataSource.close(false);
        }
    }

    /*
     * This test simulates the following scenario. First, a signed state for round N is selected
     * to be flushed to disk (periodic snapshot). Before it's done, the node is disconnected from
     * network and starts a reconnect. Reconnect is successful for a different round M (M > N),
     * and snapshot for round M is written to disk. Now the node has all signatures for the old
     * round N, and that old signed state is finally written to disk.
     */
    @Test
    void testSnapshotAfterReconnect(@TempDir Path snapshotDir, @TempDir Path oldSnapshotDir) throws Exception {
        final MerkleDbDataSourceBuilder dsBuilder = createDefaultBuilder();
        final VirtualDataSource original = dsBuilder.build("vm", null, false, false);
        // Simulate reconnect as a learner
        final Path snapshotPath = dsBuilder.snapshot(null, original);
        final VirtualDataSource copy = dsBuilder.build("vm", snapshotPath, true, false);

        try {
            dsBuilder.snapshot(snapshotDir, copy);
            assertDoesNotThrow(() -> dsBuilder.snapshot(oldSnapshotDir, original));
        } finally {
            original.close(false);
            copy.close(false);
            FileUtils.deleteDirectory(snapshotPath);
        }
    }
}

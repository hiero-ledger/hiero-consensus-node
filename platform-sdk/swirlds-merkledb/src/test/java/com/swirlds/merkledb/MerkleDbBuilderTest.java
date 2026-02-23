// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.common.test.fixtures.ConstructableTestFixtures;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MerkleDbBuilderTest {

    private static final long INITIAL_SIZE = 1_000_000;

    @BeforeAll
    static void setup() throws ConstructableRegistryException {
        ConstructableTestFixtures.registerAllConstructables();
    }

    @AfterEach
    public void afterCheckNoDbLeftOpen() {
        // check db count
        assertEquals(0, MerkleDbDataSource.getCountOfOpenDatabases(), "Expected no open dbs");
    }

    final MerkleDbDataSourceBuilder createDefaultBuilder() {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        return new MerkleDbDataSourceBuilder(CONFIGURATION, INITIAL_SIZE, merkleDbConfig.hashesRamToDiskThreshold());
    }

    @ParameterizedTest
    @CsvSource({"100,0", "100,100", "1000000,1024", "1000000,9223372036854775807"})
    @DisplayName("Test table config is passed to data source")
    public void testTableConfig(final long initialCapacity, final long hashesRamToDiskThreshold) throws IOException {
        final MerkleDbDataSourceBuilder builder =
                new MerkleDbDataSourceBuilder(CONFIGURATION, initialCapacity, hashesRamToDiskThreshold);
        VirtualDataSource dataSource = null;
        try {
            dataSource = builder.build("test1", null, false, false);
            assertTrue(dataSource instanceof MerkleDbDataSource);
            MerkleDbDataSource merkleDbDataSource = (MerkleDbDataSource) dataSource;
            assertEquals(initialCapacity, merkleDbDataSource.getInitialCapacity());
            assertEquals(hashesRamToDiskThreshold, merkleDbDataSource.getHashesRamToDiskThreshold());
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Test compaction flag is passed to data source")
    public void testCompactionConfig(final boolean compactionEnabled) throws IOException {
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(CONFIGURATION, 1024, 0);
        VirtualDataSource dataSource = null;
        try {
            dataSource = builder.build("test2", null, compactionEnabled, false);
            assertInstanceOf(MerkleDbDataSource.class, dataSource);
            MerkleDbDataSource merkleDbDataSource = (MerkleDbDataSource) dataSource;
            assertEquals(compactionEnabled, merkleDbDataSource.isCompactionEnabled());
        } finally {
            dataSource.close();
        }
    }

    @Test
    void testSnapshot() throws IOException {
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(CONFIGURATION, 1024, 1024);
        VirtualDataSource dataSource = null;
        try {
            final String label = "testSnapshot";
            dataSource = builder.build(label, null, false, false);
            final Path tmpDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory("snapshot", CONFIGURATION);
            builder.snapshot(tmpDir, dataSource);
            assertTrue(Files.isDirectory(tmpDir.resolve("data").resolve(label)));
        } finally {
            dataSource.close();
        }
    }

    @Test
    void testSnapshotRestore() throws IOException {
        final int hashesRamToDiskThreshold = 4096;
        final MerkleDbDataSourceBuilder builder =
                new MerkleDbDataSourceBuilder(CONFIGURATION, 10_000, hashesRamToDiskThreshold);
        VirtualDataSource dataSource = null;
        try {
            final String label = "testSnapshotRestore";
            dataSource = builder.build(label, null, false, false);
            final Path tmpDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory("snapshot", CONFIGURATION);
            builder.snapshot(tmpDir, dataSource);
            assertTrue(Files.isDirectory(tmpDir.resolve("data").resolve(label)));
            VirtualDataSource restored = null;
            try {
                restored = builder.build(label, tmpDir, false, false);
                assertNotNull(restored);
                assertInstanceOf(MerkleDbDataSource.class, restored);
                final MerkleDbDataSource merkleDbRestored = (MerkleDbDataSource) restored;
                assertEquals(hashesRamToDiskThreshold, merkleDbRestored.getHashesRamToDiskThreshold());
            } finally {
                restored.close();
            }
        } finally {
            dataSource.close();
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
    void testSnapshotAfterReconnect() throws Exception {
        final MerkleDbDataSourceBuilder dsBuilder = createDefaultBuilder();
        final VirtualDataSource original = dsBuilder.build("vm", null, false, false);
        // Simulate reconnect as a learner
        final Path snapshotPath = dsBuilder.snapshot(null, original);
        final VirtualDataSource copy = dsBuilder.build("vm", snapshotPath, true, false);

        try {
            final Path snapshotDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory("snapshot", CONFIGURATION);
            dsBuilder.snapshot(snapshotDir, copy);

            final Path oldSnapshotDir =
                    LegacyTemporaryFileBuilder.buildTemporaryDirectory("oldSnapshot", CONFIGURATION);
            assertDoesNotThrow(() -> dsBuilder.snapshot(oldSnapshotDir, original));
        } finally {
            original.close();
            copy.close();
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.constructable.ConstructableRegistration;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.test.fixtures.TestFileSystemManager;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MerkleDbBuilderTest {

    private static final long INITIAL_SIZE = 1_000_000;

    private static FileSystemManager fileSystemManager;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setup() throws ConstructableRegistryException {
        ConstructableRegistration.registerAllConstructables();
        fileSystemManager = new TestFileSystemManager(tempDir);
    }

    @AfterEach
    public void afterCheckNoDbLeftOpen() {
        // check db count
        assertEquals(0, MerkleDbDataSource.getCountOfOpenDatabases(), "Expected no open dbs");
    }

    final MerkleDbDataSourceBuilder createDefaultBuilder() {
        return new MerkleDbDataSourceBuilder(CONFIGURATION, fileSystemManager, INITIAL_SIZE);
    }

    @ParameterizedTest
    @CsvSource({"100", "1000000"})
    @DisplayName("Test table config is passed to data source")
    public void testTableConfig(final long initialCapacity) throws IOException {
        final MerkleDbDataSourceBuilder builder =
                new MerkleDbDataSourceBuilder(CONFIGURATION, fileSystemManager, initialCapacity);
        VirtualDataSource dataSource = null;
        try {
            dataSource = builder.build("test1", null, false, false);
            assertInstanceOf(MerkleDbDataSource.class, dataSource);
            MerkleDbDataSource merkleDbDataSource = (MerkleDbDataSource) dataSource;
            assertEquals(initialCapacity, merkleDbDataSource.getInitialCapacity());
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
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(CONFIGURATION, fileSystemManager, 1024);
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
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(CONFIGURATION, fileSystemManager, 1024);
        VirtualDataSource dataSource = null;
        try {
            final String label = "testSnapshot";
            dataSource = builder.build(label, null, false, false);
            final Path tmpDir = fileSystemManager.resolveNewTemp("snapshot");
            Files.createDirectories(tmpDir);
            builder.snapshot(tmpDir, dataSource);
            assertTrue(Files.isDirectory(tmpDir.resolve("data").resolve(label)));
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    @Test
    void testSnapshotRestore() throws IOException {
        final MerkleDbDataSourceBuilder builder =
                new MerkleDbDataSourceBuilder(CONFIGURATION, fileSystemManager, 10_000);
        VirtualDataSource dataSource = null;
        try {
            final String label = "testSnapshotRestore";
            dataSource = builder.build(label, null, false, false);
            final Path tmpDir = fileSystemManager.resolveNewTemp("snapshot");
            Files.createDirectories(tmpDir);
            builder.snapshot(tmpDir, dataSource);
            assertTrue(Files.isDirectory(tmpDir.resolve("data").resolve(label)));
            VirtualDataSource restored = null;
            try {
                restored = builder.build(label, tmpDir, false, false);
                assertNotNull(restored);
                assertInstanceOf(MerkleDbDataSource.class, restored);
                final MerkleDbDataSource merkleDbRestored = (MerkleDbDataSource) restored;
            } finally {
                if (restored != null) {
                    restored.close();
                }
            }
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
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
            final Path snapshotDir = fileSystemManager.resolveNewTemp("snapshot");
            Files.createDirectories(snapshotDir);
            dsBuilder.snapshot(snapshotDir, copy);

            final Path oldSnapshotDir = fileSystemManager.resolveNewTemp("oldSnapshot");
            Files.createDirectories(oldSnapshotDir);
            assertDoesNotThrow(() -> dsBuilder.snapshot(oldSnapshotDir, original));
        } finally {
            original.close();
            copy.close();
        }
    }
}

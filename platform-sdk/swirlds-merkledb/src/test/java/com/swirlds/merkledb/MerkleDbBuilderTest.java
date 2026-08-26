// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.DEFAULT_CONFIGURATION;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.createDataSource;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.createHashChunkStream;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.virtualmap.datasource.VirtualDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.utility.test.fixtures.file.AbstractFileManagerAwareTest;
import org.hiero.consensus.constructable.ConstructableRegistration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MerkleDbBuilderTest extends AbstractFileManagerAwareTest {

    private static final long INITIAL_SIZE = 1_000_000;

    @BeforeAll
    static void setup() throws ConstructableRegistryException {
        ConstructableRegistration.registerAllConstructables();
    }

    @AfterEach
    public void afterCheckNoDbLeftOpen() {
        assertAllDatabasesClosed();
    }

    final MerkleDbDataSourceBuilder createDefaultBuilder() {
        return new MerkleDbDataSourceBuilder(DEFAULT_CONFIGURATION, fileSystemManager, INITIAL_SIZE);
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 1000000})
    @DisplayName("Test table config is passed to data source")
    public void testTableConfig(final int initialCapacity) throws IOException {
        final MerkleDbDataSource dataSource = createDataSource(fileSystemManager, initialCapacity, false, false);
        try {
            assertEquals(initialCapacity, dataSource.getInitialCapacity());
        } finally {
            dataSource.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Test compaction flag is passed to data source")
    public void testCompactionConfig(final boolean compactionEnabled) throws IOException {
        MerkleDbDataSource dataSource = createDataSource(fileSystemManager, 1024, compactionEnabled, false);
        try {
            assertEquals(compactionEnabled, dataSource.isCompactionEnabled());
        } finally {
            dataSource.close();
        }
    }

    @Test
    void testSnapshot() throws IOException {
        final String label = "testSnapshot";
        final MerkleDbDataSourceBuilder builder = createDefaultBuilder();
        VirtualDataSource dataSource = builder.build("testSnapshot", null, false, false);
        try {
            final Path snapshotDir = builder.snapshot(null, dataSource);
            assertTrue(Files.isDirectory(snapshotDir.resolve("data").resolve(label)));
        } finally {
            dataSource.close();
        }
    }

    @Test
    void testSnapshotRestore() throws IOException {
        final String label = "testSnapshotRestore";
        final MerkleDbDataSourceBuilder builder = createDefaultBuilder();
        VirtualDataSource dataSource = builder.build(label, null, false, false);
        try {
            final Path snapshotDir = builder.snapshot(null, dataSource);
            assertTrue(Files.isDirectory(snapshotDir.resolve("data").resolve(label)));
            VirtualDataSource restored = builder.build(label, snapshotDir, false, false);
            try {
                assertNotNull(restored);
                assertInstanceOf(MerkleDbDataSource.class, restored);
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
            dsBuilder.snapshot(null, copy);
            assertDoesNotThrow(() -> dsBuilder.snapshot(null, original));
        } finally {
            original.close();
            copy.close();
        }
    }

    @Test
    void canCreateDataSourceWithNoDefaultDir() throws Exception {
        final String defaultFolderName = "merkledb-state";
        final Path defaultPath = fileSystemManager.getTempPath().resolve(defaultFolderName);
        if (Files.isDirectory(defaultPath)) {
            Files.deleteIfExists(defaultPath);
        }
        final MerkleDbDataSourceBuilder builder =
                new MerkleDbDataSourceBuilder("merkledb-state", DEFAULT_CONFIGURATION, fileSystemManager, INITIAL_SIZE);
        final VirtualDataSource dataSource = builder.build("test", null, false, false);
        try {
            // Check the folder gets created
            Assertions.assertTrue(Files.isDirectory(defaultPath));
            // Check the data source is empty
            Assertions.assertEquals(-1, dataSource.getFirstLeafPath());
            Assertions.assertEquals(-1, dataSource.getLastLeafPath());
        } finally {
            dataSource.close();
        }
    }

    @Test
    void canCreateDataSourceWithDefaultDir() throws Exception {
        final String defaultFolderName = "merkledb-state";
        final Path defaultPath = fileSystemManager.getTempPath().resolve(defaultFolderName);
        if (Files.isDirectory(defaultPath)) {
            Files.deleteIfExists(defaultPath);
        }
        final MerkleDbDataSourceBuilder builder =
                new MerkleDbDataSourceBuilder("merkledb-state", DEFAULT_CONFIGURATION, fileSystemManager, INITIAL_SIZE);
        final VirtualDataSource dataSource1 = builder.build("test", null, false, false);
        try {
            dataSource1.saveRecords(100, 200, Stream.of(), Stream.of(), Stream.of(), false);
        } finally {
            dataSource1.close(true);
        }
        final VirtualDataSource dataSource2 = builder.build("test", null, false, false);
        try {
            Assertions.assertEquals(100, dataSource2.getFirstLeafPath());
            Assertions.assertEquals(200, dataSource2.getLastLeafPath());
        } finally {
            dataSource2.close();
        }
    }

    @Test
    void snapshotMetadataMustNotBeCorrupted() throws Exception {
        final String LABEL = "state";
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(
                "snapshotMetadataMustNotBeCorrupted", DEFAULT_CONFIGURATION, fileSystemManager, 100);

        final VirtualDataSource original = builder.build(LABEL, null, false, false);
        original.saveRecords(42, 84, createHashChunkStream(84, 2), Stream.of(), Stream.of(), false);
        final Path snapshotPath = fileSystemManager.resolveNewTemp("merkledb-snapshotMetadataMustNotBeCorrupted");
        builder.snapshot(snapshotPath, original);
        original.close();

        final Map<Path, byte[]> snapshotMetadataFiles = collectMetadataFiles(snapshotPath);

        final VirtualDataSource restored1 = builder.build(LABEL, snapshotPath, false, false);
        final long firstLeafPath = restored1.getFirstLeafPath();
        final long lastLeafPath = restored1.getLastLeafPath();
        restored1.saveRecords(85, 170, createHashChunkStream(170, 2), Stream.of(), Stream.of(), false);
        restored1.close();

        for (final Map.Entry<Path, byte[]> e : snapshotMetadataFiles.entrySet()) {
            final Path path = e.getKey();
            final byte[] wasContent = e.getValue();
            final byte[] nowContent = Files.readAllBytes(path);
            Assertions.assertArrayEquals(
                    wasContent, nowContent, "File must not be changed: " + snapshotPath.relativize(path));
        }

        // Restore from the same snapshot path. DB metadata must not be affected by restored1.saveRecords() above
        final VirtualDataSource restored2 = builder.build(LABEL, snapshotPath, false, false);
        try {
            Assertions.assertEquals(firstLeafPath, restored2.getFirstLeafPath());
            Assertions.assertEquals(lastLeafPath, restored2.getLastLeafPath());
        } finally {
            restored2.close();
        }
    }

    private static Map<Path, byte[]> collectMetadataFiles(final Path dataSourcePath) throws IOException {
        final Map<Path, byte[]> metadata = new HashMap<>();
        try (final Stream<Path> files = Files.walk(dataSourcePath)) {
            final List<Path> filesList = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains("metadata"))
                    .toList();
            for (final Path file : filesList) {
                metadata.put(file, Files.readAllBytes(file));
            }
        }
        // table metadata + 3 DataFileCollection metadata + HDHM metadata
        assertEquals(5, metadata.size(), "All metadata files must exist");
        return metadata;
    }
}

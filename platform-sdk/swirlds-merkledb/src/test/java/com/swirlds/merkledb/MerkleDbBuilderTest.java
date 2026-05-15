// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.createDataSource;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.virtualmap.datasource.VirtualDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.utility.test.fixtures.file.AbstractFileManagerAwareTest;
import org.hiero.consensus.constructable.ConstructableRegistration;
import org.junit.jupiter.api.AfterEach;
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
        return new MerkleDbDataSourceBuilder(CONFIGURATION, fileSystemManager, INITIAL_SIZE);
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
}

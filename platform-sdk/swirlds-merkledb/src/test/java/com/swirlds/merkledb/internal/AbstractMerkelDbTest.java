// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.internal;

import static com.swirlds.merkledb.internal.MerkleDbDataSourceTestUtils.assertDatabaseFolderDeleted;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.DEFAULT_CONFIGURATION;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertNoDatabaseFolders;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.checkDirectMemoryIsCleanedUpToLessThanBaseUsage;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.getDirectMemoryUsedBytes;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.base.function.CheckedConsumer;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.utility.test.fixtures.file.AbstractFileManagerAwareTest;
import org.hiero.consensus.constructable.ConstructableRegistration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

public abstract class AbstractMerkelDbTest extends AbstractFileManagerAwareTest {

    /**
     * Keep track of initial direct memory used already, so we can check if we leak over and above
     * what we started with
     */
    private long directMemoryUsedAtStart;

    @BeforeAll
    static void registerAllConstructables() throws ConstructableRegistryException {
        ConstructableRegistration.registerAllConstructables();
    }

    @BeforeEach
    void initializeDirectMemoryAtStart() {
        directMemoryUsedAtStart = getDirectMemoryUsedBytes();
    }

    @AfterEach
    void verifyNoDatabases() {
        checkDirectMemoryIsCleanedUpToLessThanBaseUsage(directMemoryUsedAtStart);
        assertAllDatabasesClosed();
        assertNoDatabaseFolders(fileSystemManager.getTempPath());
    }

    /** Takes a snapshot that {@link #restoreDataSource} can read back. */
    protected Path takeSnapshot(final MerkleDbDataSource dataSource, final Path snapshotDir) {
        return MerkleDbDataSourceTestUtils.takeSnapshot(fileSystemManager, dataSource, snapshotDir);
    }

    /** Restores a data source from a snapshot previously taken with {@link #takeSnapshot}. */
    protected MerkleDbDataSource restoreDataSource(
            final Configuration configuration,
            final Path snapshotDir,
            final String name,
            final boolean compactionEnabled) {
        return MerkleDbDataSourceTestUtils.restoreDataSource(
                configuration, fileSystemManager, snapshotDir, name, compactionEnabled);
    }

    protected MerkleDbDataSource restoreDataSource(
            final Path snapshotDir, final String name, final boolean compactionEnabled) {
        return restoreDataSource(DEFAULT_CONFIGURATION, snapshotDir, name, compactionEnabled);
    }

    protected MerkleDbDataSource createDataSource(
            final long size, final boolean compactionEnabled, boolean preferDiskBasedIndexes) {
        return createDataSource("test", size, compactionEnabled, preferDiskBasedIndexes);
    }

    protected MerkleDbDataSource createDataSource(
            final String name, final long size, final boolean compactionEnabled, boolean preferDiskBasedIndexes) {
        return MerkleDbDataSourceTestUtils.createDataSource(
                DEFAULT_CONFIGURATION, fileSystemManager, name, size, compactionEnabled, preferDiskBasedIndexes);
    }

    protected void createAndApplyDataSource(
            final int size, CheckedConsumer<MerkleDbDataSource, Exception> dataSourceConsumer) throws IOException {
        createAndApplyDataSource("test", size, dataSourceConsumer);
    }

    protected void createAndApplyDataSource(
            String tableName, final int size, CheckedConsumer<MerkleDbDataSource, Exception> dataSourceConsumer)
            throws IOException {
        long openedDatabasesBefore = MerkleDbDataSourceBuilder.getCountOfOpenDatabases();
        final MerkleDbDataSource dataSource = createDataSource(tableName, size, false, false);
        try {
            dataSourceConsumer.accept(dataSource);
        } catch (Throwable e) {
            fail("Failed to test MerkleDbDataSource", e);
        } finally {
            dataSource.close();
            assertEventuallyEquals(
                    openedDatabasesBefore,
                    MerkleDbDataSourceBuilder::getCountOfOpenDatabases,
                    Duration.of(3, ChronoUnit.SECONDS),
                    "Expected " + openedDatabasesBefore + " open databases.");
            assertDatabaseFolderDeleted(dataSource);
        }
    }
}

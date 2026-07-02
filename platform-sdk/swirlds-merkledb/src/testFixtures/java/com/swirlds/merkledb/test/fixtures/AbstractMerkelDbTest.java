// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.DEFAULT_CONFIGURATION;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.DEFAULT_MERKLE_DB_CONFIG;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertDatabaseFolderDeleted;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.checkDirectMemoryIsCleanedUpToLessThanBaseUsage;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.getDirectMemoryUsedBytes;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.base.function.CheckedConsumer;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
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
        assertDatabaseFolders(0);
    }

    protected void assertDatabaseFolders(int count) {
        final Path tempPath = fileSystemManager.getTempPath();
        final long actualCount;
        try (final Stream<Path> entries = Files.list(tempPath)) {
            actualCount = entries.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains(MerkleDbDataSourceBuilder.FOLDER_SUFFIX))
                    .count();
        } catch (final IOException e) {
            throw new RuntimeException("Failed to list temp directory: " + tempPath, e);
        }
        assertEquals(count, actualCount, "Wrong number of database folders found.");
    }

    protected MerkleDbDataSource restoreDataSource(
            final MerkleDbConfig configuration, final Path dbPath, final String name, final boolean compactionEnabled)
            throws IOException {
        return new MerkleDbDataSource(dbPath, configuration, fileSystemManager, name, compactionEnabled, false);
    }

    protected MerkleDbDataSource restoreDataSource(
            final Path dbPath, final String name, final boolean compactionEnabled) throws IOException {
        return restoreDataSource(DEFAULT_MERKLE_DB_CONFIG, dbPath, name, compactionEnabled);
    }

    protected MerkleDbDataSource createDataSource(
            final long size, final boolean compactionEnabled, boolean preferDiskBasedIndexes) {
        return createDataSource("test", size, compactionEnabled, preferDiskBasedIndexes);
    }

    protected MerkleDbDataSource createDataSource(
            final String name, final long size, final boolean compactionEnabled, boolean preferDiskBasedIndexes) {
        MerkleDbDataSourceBuilder dataSourceBuilder =
                new MerkleDbDataSourceBuilder(DEFAULT_CONFIGURATION, fileSystemManager, size);
        return (MerkleDbDataSource) dataSourceBuilder.build(name, null, compactionEnabled, preferDiskBasedIndexes);
    }

    protected void createAndApplyDataSource(
            final int size, CheckedConsumer<MerkleDbDataSource, Exception> dataSourceConsumer) throws IOException {
        createAndApplyDataSource("test", size, dataSourceConsumer);
    }

    protected void createAndApplyDataSource(
            String tableName, final int size, CheckedConsumer<MerkleDbDataSource, Exception> dataSourceConsumer)
            throws IOException {
        long openedDatabasesBefore = MerkleDbDataSource.getCountOfOpenDatabases();
        final MerkleDbDataSource dataSource = createDataSource(tableName, size, false, false);
        try {
            dataSourceConsumer.accept(dataSource);
        } catch (Throwable e) {
            fail("Failed to test MerkleDbDataSource", e);
        } finally {
            dataSource.close();
            assertEventuallyEquals(
                    openedDatabasesBefore,
                    MerkleDbDataSource::getCountOfOpenDatabases,
                    Duration.of(3, ChronoUnit.SECONDS),
                    "Expected " + openedDatabasesBefore + " open databases.");
            assertDatabaseFolderDeleted(dataSource);
        }
    }
}

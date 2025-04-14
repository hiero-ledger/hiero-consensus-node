// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.hiero.consensus.model.crypto.DigestType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class MerkleDbTest {

    @BeforeAll
    public static void setup() throws Exception {
        MerkleDb.resetDefaultInstancePath();
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds.merkledb");
    }

    @AfterEach
    public void shutdownTest() {
        // check db count
        assertEventuallyEquals(
                0L, MerkleDbDataSource::getCountOfOpenDatabases, Duration.ofSeconds(2), "Expected no open dbs");
    }

    private static MerkleDbTableConfig fixedConfig() {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        return new MerkleDbTableConfig(
                (short) 1,
                DigestType.SHA_384,
                merkleDbConfig.maxNumOfKeys(),
                merkleDbConfig.hashesRamToDiskThreshold());
    }

    @Test
    @DisplayName("Multiple calls to MerkleDb.getInstance()")
    public void testDoubleGetInstance() {
        final MerkleDb instance1 = MerkleDb.getDefaultInstance(CONFIGURATION);
        Assertions.assertNotNull(instance1);
        final MerkleDb instance2 = MerkleDb.getDefaultInstance(CONFIGURATION);
        Assertions.assertNotNull(instance2);
        Assertions.assertEquals(instance2, instance1);
    }

    @Test
    @DisplayName("Set custom default storage path")
    void testChangeDefaultPath() throws IOException {
        final MerkleDb instance1 = MerkleDb.getDefaultInstance(CONFIGURATION);
        Assertions.assertNotNull(instance1);
        final Path tempDir = LegacyTemporaryFileBuilder.buildTemporaryFile(CONFIGURATION);
        MerkleDb.setDefaultPath(tempDir);
        final MerkleDb instance2 = MerkleDb.getDefaultInstance(CONFIGURATION);
        Assertions.assertNotNull(instance2);
        Assertions.assertNotEquals(instance2, instance1);
        Assertions.assertEquals(tempDir, instance2.getStorageDir());
    }

    @Test
    @DisplayName("MerkleDb paths")
    void testMerkleDbDirs() throws IOException {
        final Path tempDir = LegacyTemporaryFileBuilder.buildTemporaryFile(CONFIGURATION);
        MerkleDb.setDefaultPath(tempDir);
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        Assertions.assertEquals(tempDir, instance.getStorageDir());
        // This is not black-box testing, but is it worth exposing MerkleDb.SHARED_DIR as public or
        // package-private just for testing purposes?
        Assertions.assertEquals(tempDir.resolve("shared"), instance.getSharedDir());
        // same here
        Assertions.assertEquals(tempDir.resolve("tables"), instance.getTablesDir());
        // and here
        Assertions.assertTrue(Files.exists(tempDir.resolve("database_metadata.pbj")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testLoadMetadata(final boolean sourceUsePbj) throws IOException {
        final Configuration sourceConfig = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("merkleDb.usePbj", sourceUsePbj))
                .withConfigDataType(MerkleDbConfig.class)
                .withConfigDataType(TemporaryFileConfig.class)
                .withConfigDataType(StateCommonConfig.class)
                .build();
        final Path dbDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory("testLoadMetadata", sourceConfig);
        final MerkleDb sourceDb = MerkleDb.getInstance(dbDir, sourceConfig);

        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSource sourceDs = sourceDb.createDataSource("table" + sourceUsePbj, tableConfig, false);

        for (final boolean snapshotUsePbj : new boolean[] {true, false}) {
            final Path snapshotDir =
                    LegacyTemporaryFileBuilder.buildTemporaryDirectory("testLoadMetadataSnapshot", sourceConfig);
            Files.delete(snapshotDir);
            // Don't call sourceDb.snapshot() as it would create and initialize an instance for snapshotDir
            FileUtils.hardLinkTree(dbDir, snapshotDir.resolve("db"));

            final Configuration snapshotConfig = ConfigurationBuilder.create()
                    .withSources(new SimpleConfigSource("merkleDb.usePbj", snapshotUsePbj))
                    .withConfigDataType(MerkleDbConfig.class)
                    .build();

            final MerkleDb snapshotDb = MerkleDb.getInstance(snapshotDir.resolve("db"), snapshotConfig);
            final MerkleDbDataSource snapshotDs = snapshotDb.getDataSource("table" + sourceUsePbj, false);
            Assertions.assertNotNull(snapshotDs);
            snapshotDs.close();
        }

        sourceDs.close();
    }

    @Test
    @DisplayName("MerkleDb data source paths")
    void testDataSourcePaths() throws IOException {
        final Path tempDir = LegacyTemporaryFileBuilder.buildTemporaryFile(CONFIGURATION);
        final MerkleDb instance = MerkleDb.getInstance(tempDir, CONFIGURATION);
        final Path dbDir = instance.getStorageDir();
        final String tableName = "tabley";
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSource dataSource = instance.createDataSource(tableName, tableConfig, false);
        Assertions.assertNotNull(dataSource);
        Assertions.assertTrue(Files.exists(dbDir.resolve("database_metadata.pbj")));
        final int tableId = dataSource.getTableId();
        Assertions.assertEquals(dbDir.resolve("tables/" + tableName + "-" + tableId), dataSource.getStorageDir());
        Assertions.assertEquals(
                dbDir.resolve("tables/" + tableName + "-" + tableId), instance.getTableDir(tableName, tableId));
        dataSource.close();
    }

    @Test
    @DisplayName("Call MerkleDb.createDataSource() twice")
    void testDoubleCreateDataSource() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final String tableName = "tablez";
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSource dataSource = instance.createDataSource(tableName, tableConfig, false);
        Assertions.assertNotNull(dataSource);
        Assertions.assertThrows(
                IllegalStateException.class, () -> instance.createDataSource(tableName, tableConfig, false));
        dataSource.close();
    }

    @Test
    @DisplayName("Load existing datasource")
    void testLoadExistingDatasource() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final String tableName = UUID.randomUUID().toString();
        final MerkleDbTableConfig tableConfig = fixedConfig();
        instance.createDataSource(tableName, tableConfig, false).close();

        // create datasource reusing existing metadata
        MerkleDbDataSource dataSource =
                new MerkleDbDataSource(instance, tableName, instance.getNextTableId() - 1, tableConfig, false, false);
        // This datasource cannot be properly closed because MerkleDb instance is not aware of this.
        // Assertion error is expected
        assertThrows(AssertionError.class, dataSource::close);
    }

    @Test
    @DisplayName("Create datasource with corrupted file structure - directory exists but metadata is missing")
    void testDataSourceUsingAbsentDir() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final String tableName = UUID.randomUUID().toString();
        final MerkleDbTableConfig tableConfig = fixedConfig();
        // creating empty table directory with no metadata
        Path tableStorageDir = instance.getTableDir(tableName, instance.getNextTableId() + 1);
        Files.createDirectories(tableStorageDir);
        assertThrows(IOException.class, () -> instance.createDataSource(tableName, tableConfig, false));
        Files.delete(tableStorageDir);
    }

    @Test
    void testCreateDataSource() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final String tableName = "tablea";
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSource dataSource = instance.createDataSource(tableName, tableConfig, false);
        Assertions.assertNotNull(dataSource);
        final int tableId = dataSource.getTableId();
        Assertions.assertEquals(tableConfig, dataSource.getTableConfig());
        Assertions.assertEquals(tableConfig, instance.getTableConfig(tableId));
        dataSource.close();
        // Table config deleted on data source close
        Assertions.assertNull(instance.getTableConfig(tableId));
    }

    @Test
    @DisplayName("Get table config with wrong ID")
    void testWrongTableConfig() {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        Assertions.assertNull(instance.getTableConfig(-1));
        // Needs to be updated, if we support more than 256 tables
        Assertions.assertNull(instance.getTableConfig(256));
    }

    @Test
    @DisplayName("Get and create data source")
    void testGetDataSource() throws IOException {
        final Path dbDir = LegacyTemporaryFileBuilder.buildTemporaryFile(CONFIGURATION);
        MerkleDb.setDefaultPath(dbDir);
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final String tableName = "tableb";
        Assertions.assertThrows(IllegalStateException.class, () -> instance.getDataSource(tableName, false));
    }

    @Test
    @DisplayName("Get data source after close")
    void testGetDataSourceAfterClose() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final String tableName = "tablec";
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSource dataSource = instance.createDataSource(tableName, tableConfig, false);
        Assertions.assertNotNull(dataSource);
        dataSource.close();
        Assertions.assertThrows(IllegalStateException.class, () -> instance.getDataSource(tableName, false));
    }

    @Test
    @DisplayName("Get data source after reload")
    void testGetDataSourceAfterReload() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final String tableName = "tablec";
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSource dataSource = instance.createDataSource(tableName, tableConfig, false);
        Assertions.assertNotNull(dataSource);

        final Path snapshotDir = LegacyTemporaryFileBuilder.buildTemporaryFile(CONFIGURATION);
        instance.snapshot(snapshotDir, dataSource);

        final MerkleDb instance2 = MerkleDb.getInstance(snapshotDir, CONFIGURATION);
        final MerkleDbDataSource dataSource2 = instance2.getDataSource(tableName, false);
        Assertions.assertNotNull(dataSource2);
        Assertions.assertNotEquals(dataSource2, dataSource);

        dataSource.close();
        dataSource2.close();
    }

    @Test
    @DisplayName("Test MerkleDb snapshot all tables")
    void testSnapshot() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final String tableName1 = "tabled1";
        final MerkleDbDataSource dataSource1 = instance.createDataSource(tableName1, tableConfig, false);
        Assertions.assertNotNull(dataSource1);
        final String tableName2 = "tabled2";
        final MerkleDbDataSource dataSource2 = instance.createDataSource(tableName2, tableConfig, false);
        Assertions.assertNotNull(dataSource2);

        final Path snapshotDir = LegacyTemporaryFileBuilder.buildTemporaryFile(CONFIGURATION);
        instance.snapshot(snapshotDir, dataSource1);
        instance.snapshot(snapshotDir, dataSource2);

        final MerkleDb instance2 = MerkleDb.getInstance(snapshotDir, CONFIGURATION);
        Assertions.assertTrue(Files.exists(instance2.getTableDir(tableName1, dataSource1.getTableId())));
        Assertions.assertFalse(Files.exists(instance2.getTableDir("tabled", dataSource1.getTableId())));
        Assertions.assertTrue(Files.exists(instance2.getTableDir(tableName2, dataSource2.getTableId())));
        Assertions.assertFalse(Files.exists(instance2.getTableDir("tabled", dataSource2.getTableId())));
        final MerkleDbDataSource restored1 = instance2.getDataSource(tableName1, false);
        Assertions.assertEquals(tableConfig, restored1.getTableConfig());
        restored1.close();
        final MerkleDbDataSource restored2 = instance2.getDataSource(tableName2, false);
        Assertions.assertEquals(tableConfig, restored2.getTableConfig());
        Assertions.assertEquals(tableConfig, instance2.getTableConfig(restored2.getTableId()));
        restored2.close();

        dataSource1.close();
        dataSource2.close();
    }

    @Test
    @DisplayName("Test MerkleDb snapshot some tables")
    void testSnapshotSelectedTables() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final String tableName1 = "tablee1";
        final MerkleDbDataSource dataSource1 = instance.createDataSource(tableName1, tableConfig, false);
        Assertions.assertNotNull(dataSource1);
        final String tableName2 = "tablee2";
        final MerkleDbDataSource dataSource2 = instance.createDataSource(tableName2, tableConfig, false);
        Assertions.assertNotNull(dataSource2);

        final MerkleDbDataSource activeCopy = instance.copyDataSource(dataSource2, true, false);
        dataSource1.close();
        dataSource2.close();

        Assertions.assertEquals(tableName2, activeCopy.getTableName());
        Assertions.assertNotEquals(dataSource2.getStorageDir(), activeCopy.getStorageDir());
        Assertions.assertEquals(tableConfig, activeCopy.getTableConfig());
        activeCopy.close();
    }

    @Test
    @DisplayName("Snapshot data source copies")
    void testSnapshotCopiedTables() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final String tableName1 = "tablee3";
        final MerkleDbDataSource dataSource1 = instance.createDataSource(tableName1, tableConfig, false);
        Assertions.assertNotNull(dataSource1);
        final String tableName2 = "tablee4";
        final MerkleDbDataSource dataSource2 = instance.createDataSource(tableName2, tableConfig, false);
        Assertions.assertNotNull(dataSource2);

        final MerkleDbDataSource inactiveCopy1 = instance.copyDataSource(dataSource1, false, false);
        final MerkleDbDataSource activeCopy2 = instance.copyDataSource(dataSource2, true, false);

        final Path snapshotDir =
                LegacyTemporaryFileBuilder.buildTemporaryFile("testSnapshotCopiedTables", CONFIGURATION);
        // Make a snapshot of original table 1
        instance.snapshot(snapshotDir, dataSource1);
        // Table with this name already exists in the target dir, so exception
        Assertions.assertThrows(IllegalStateException.class, () -> instance.snapshot(snapshotDir, inactiveCopy1));
        // Make a snapshot of a copy of table 1
        instance.snapshot(snapshotDir, activeCopy2);
        // This one will result in a name conflict, too
        Assertions.assertThrows(IllegalStateException.class, () -> instance.snapshot(snapshotDir, dataSource2));

        // Now try in a different order
        final Path snapshotDir2 =
                LegacyTemporaryFileBuilder.buildTemporaryFile("testSnapshotCopiedTables2", CONFIGURATION);
        instance.snapshot(snapshotDir2, inactiveCopy1);
        Assertions.assertThrows(IllegalStateException.class, () -> instance.snapshot(snapshotDir2, dataSource1));
        instance.snapshot(snapshotDir2, dataSource2);
        Assertions.assertThrows(IllegalStateException.class, () -> instance.snapshot(snapshotDir2, activeCopy2));

        final MerkleDb snapshotInstance = MerkleDb.getInstance(snapshotDir, CONFIGURATION);
        Assertions.assertTrue(Files.exists(snapshotInstance.getTableDir(tableName1, 0)));
        Assertions.assertTrue(Files.exists(snapshotInstance.getTableDir(tableName2, 1)));
        Assertions.assertFalse(Files.exists(snapshotInstance.getTableDir(tableName1, 2)));
        Assertions.assertFalse(Files.exists(snapshotInstance.getTableDir(tableName2, 3)));

        final MerkleDb snapshotInstance2 = MerkleDb.getInstance(snapshotDir2, CONFIGURATION);
        Assertions.assertTrue(Files.exists(snapshotInstance2.getTableDir(tableName1, 0)));
        Assertions.assertTrue(Files.exists(snapshotInstance2.getTableDir(tableName2, 1)));
        Assertions.assertFalse(Files.exists(snapshotInstance2.getTableDir(tableName1, 2)));
        Assertions.assertFalse(Files.exists(snapshotInstance2.getTableDir(tableName2, 3)));

        dataSource1.close();
        dataSource2.close();
        inactiveCopy1.close();
        activeCopy2.close();
    }

    @Test
    @DisplayName("Restore from snapshot")
    void testRestore() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final String tableName = "tableg";
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSource dataSource = instance.createDataSource(tableName, tableConfig, false);
        Assertions.assertNotNull(dataSource);
        final Path snapshotDir = LegacyTemporaryFileBuilder.buildTemporaryFile(CONFIGURATION);
        instance.snapshot(snapshotDir, dataSource);

        final Path newDir = LegacyTemporaryFileBuilder.buildTemporaryFile(CONFIGURATION);
        MerkleDb.setDefaultPath(newDir);
        final MerkleDb instance2 = MerkleDb.restore(snapshotDir, null, CONFIGURATION);
        final MerkleDbDataSource dataSource2 = instance2.getDataSource(tableName, false);
        Assertions.assertNotNull(dataSource2);
        Assertions.assertEquals(tableConfig, dataSource2.getTableConfig());

        dataSource.close();
        dataSource2.close();
    }

    @Test
    @DisplayName("Restore with no shared dir")
    void testRestoreNoSharedDir() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final String tableName = "tableh";
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSource dataSource = instance.createDataSource(tableName, tableConfig, false);
        Assertions.assertNotNull(dataSource);

        final Path snapshotDir = LegacyTemporaryFileBuilder.buildTemporaryFile(CONFIGURATION);
        instance.snapshot(snapshotDir, dataSource);
        // Make sure the instance can be restored even without the shared dir. In the future this
        // test may need to be removed, when the shared folder becomes mandatory to exist
        Files.delete(snapshotDir.resolve("shared"));

        final Path newDir = LegacyTemporaryFileBuilder.buildTemporaryFile(CONFIGURATION);
        final MerkleDb instance2 = MerkleDb.restore(snapshotDir, newDir, CONFIGURATION);
        Assertions.assertTrue(Files.exists(instance2.getSharedDir()));

        dataSource.close();
    }

    @Test
    @DisplayName("Double snapshots")
    void testDoubleSnapshot() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final String tableName = "tablei";
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSource dataSource = instance.createDataSource(tableName + "1", tableConfig, false);
        Assertions.assertNotNull(dataSource);
        final MerkleDbDataSource dataSource2 = instance.createDataSource(tableName + "2", tableConfig, false);
        Assertions.assertNotNull(dataSource2);

        final Path snapshotDir = LegacyTemporaryFileBuilder.buildTemporaryFile(CONFIGURATION);
        instance.snapshot(snapshotDir, dataSource);
        // Can't snapshot into the same target MerkleDb instance again
        Assertions.assertThrows(IllegalStateException.class, () -> instance.snapshot(snapshotDir, dataSource));
        // dataSource2 isn't in the target dir, should be able to snapshot
        instance.snapshot(snapshotDir, dataSource2);

        dataSource.close();
        dataSource2.close();
    }

    @Test
    @DisplayName("Test copied data sources are auto deleted on close")
    void testCopiedDataSourceAutoDeleted() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final String tableName1 = "tablej1";
        final MerkleDbTableConfig tableConfig1 = fixedConfig();
        final MerkleDbDataSource dataSource1 = instance.createDataSource(tableName1, tableConfig1, false);
        Assertions.assertNotNull(dataSource1);
        final String tableName2 = "tablej2";
        final MerkleDbTableConfig tableConfig2 = fixedConfig();
        final MerkleDbDataSource dataSource2 = instance.createDataSource(tableName2, tableConfig2, false);
        Assertions.assertNotNull(dataSource2);

        final MerkleDbDataSource inactiveCopy1 = instance.copyDataSource(dataSource1, false, false);
        final MerkleDbDataSource activeCopy2 = instance.copyDataSource(dataSource2, true, false);

        dataSource1.close();
        dataSource2.close();
        inactiveCopy1.close();
        activeCopy2.close();

        Assertions.assertFalse(Files.exists(instance.getTableDir(tableName1, dataSource1.getTableId())));
        Assertions.assertFalse(Files.exists(instance.getTableDir(tableName1, inactiveCopy1.getTableId())));
        Assertions.assertFalse(Files.exists(instance.getTableDir(tableName2, dataSource2.getTableId())));
        Assertions.assertFalse(Files.exists(instance.getTableDir(tableName2, activeCopy2.getTableId())));
    }

    @Test
    @DisplayName("Compactions after data source copy")
    void checkBackgroundCompactionsOnCopy() throws IOException {
        final MerkleDb instance = MerkleDb.getDefaultInstance(CONFIGURATION);
        final String tableName = "tablek";
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSource dataSource = instance.createDataSource(tableName + "1", tableConfig, true);
        Assertions.assertNotNull(dataSource);
        final MerkleDbDataSource dataSourceCopy = instance.copyDataSource(dataSource, false, false);
        Assertions.assertNotNull(dataSourceCopy);

        Assertions.assertTrue(dataSource.isCompactionEnabled());
        Assertions.assertFalse(dataSourceCopy.isCompactionEnabled());
        dataSource.close();
        dataSourceCopy.close();
    }

    @Test
    @DisplayName("Compactions after data source import")
    void checkBackgroundCompactionsOnImport() throws IOException {
        final MerkleDb instance1 = MerkleDb.getDefaultInstance(CONFIGURATION);
        final String tableName = "tablel";
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSource dataSource = instance1.createDataSource(tableName + "1", tableConfig, true);
        Assertions.assertNotNull(dataSource);

        final Path snapshotDir =
                LegacyTemporaryFileBuilder.buildTemporaryFile("checkBackgroundCompactionsOnImport", CONFIGURATION);
        instance1.snapshot(snapshotDir, dataSource);

        Assertions.assertTrue(dataSource.isCompactionEnabled());

        dataSource.close();
    }
}

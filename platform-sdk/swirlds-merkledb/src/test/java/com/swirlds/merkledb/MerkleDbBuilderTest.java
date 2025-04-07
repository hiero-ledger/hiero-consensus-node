// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.consensus.model.crypto.DigestType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MerkleDbBuilderTest {

    private static Path testDirectory;

    @BeforeAll
    static void setup() throws IOException {
        testDirectory = LegacyTemporaryFileBuilder.buildTemporaryFile("MerkleDbBuilderTest", CONFIGURATION);
    }

    @AfterEach
    public void afterCheckNoDbLeftOpen() {
        // check db count
        assertEquals(0, MerkleDbDataSource.getCountOfOpenDatabases(), "Expected no open dbs");
    }

    private MerkleDbTableConfig createTableConfig() {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        return new MerkleDbTableConfig(
                (short) 1,
                DigestType.SHA_384,
                merkleDbConfig.maxNumOfKeys(),
                merkleDbConfig.hashesRamToDiskThreshold());
    }

    private MerkleDbTableConfig createTableConfig(final long maxNumOfKeys, final long hashesRamToDiskThreshold) {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        return new MerkleDbTableConfig((short) 1, DigestType.SHA_384, maxNumOfKeys, hashesRamToDiskThreshold);
    }

    @Test
    @DisplayName("Test table config is passed to data source")
    public void testTableConfig() throws IOException {
        final MerkleDbTableConfig tableConfig = createTableConfig();
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
        VirtualDataSource dataSource = null;
        try {
            dataSource = builder.build("test1", false);
            assertTrue(dataSource instanceof MerkleDbDataSource);
            MerkleDbDataSource merkleDbDataSource = (MerkleDbDataSource) dataSource;
            assertEquals(tableConfig, merkleDbDataSource.getTableConfig());
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    @Test
    @DisplayName("Test data source config defaults")
    public void testBuilderDefaults() throws IOException {
        final MerkleDbTableConfig tableConfig = createTableConfig();
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
        final MerkleDb defaultDatabase = MerkleDb.getDefaultInstance(CONFIGURATION);
        VirtualDataSource dataSource = null;
        try {
            dataSource = builder.build("test2", false);
            assertTrue(dataSource instanceof MerkleDbDataSource);
            MerkleDbDataSource merkleDbDataSource = (MerkleDbDataSource) dataSource;
            assertEquals(
                    defaultDatabase
                            .getStorageDir()
                            .resolve("tables")
                            .resolve("test2-" + merkleDbDataSource.getTableId()),
                    merkleDbDataSource.getStorageDir());

            final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
            final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
            assertFalse(merkleDbDataSource.isPreferDiskBasedIndexes());
            assertEquals(merkleDbConfig.maxNumOfKeys(), merkleDbDataSource.getMaxNumberOfKeys());
            assertEquals(merkleDbConfig.hashesRamToDiskThreshold(), merkleDbDataSource.getHashesRamToDiskThreshold());
            // set explicitly above
            assertFalse(merkleDbDataSource.isCompactionEnabled());
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    @Test
    @DisplayName("Test custom table config values")
    public void testCustomTableConfig() throws IOException {
        final MerkleDbTableConfig tableConfig = createTableConfig(1999, Integer.MAX_VALUE >> 4);
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
        final Path defaultDbPath = testDirectory.resolve("defaultDatabasePath");
        MerkleDb.setDefaultPath(defaultDbPath);
        VirtualDataSource dataSource = null;
        try {
            dataSource = builder.build("test3", true);
            MerkleDbDataSource merkleDbDataSource = (MerkleDbDataSource) dataSource;
            assertEquals(
                    defaultDbPath.resolve("tables").resolve("test3-" + merkleDbDataSource.getTableId()),
                    merkleDbDataSource.getStorageDir());
            assertEquals(1999, merkleDbDataSource.getMaxNumberOfKeys());
            assertEquals(Integer.MAX_VALUE >> 4, merkleDbDataSource.getHashesRamToDiskThreshold());
            // set explicitly above
            assertTrue(merkleDbDataSource.isCompactionEnabled());
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }
}

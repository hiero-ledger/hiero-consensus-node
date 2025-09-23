// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.merkledb.test.fixtures.TestType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataSourceValidatorTest {

    @TempDir
    private Path tempDir;

    private int count;

    @BeforeEach
    public void setUp() throws IOException {
        count = 10_000;
        MerkleDbTestUtils.assertAllDatabasesClosed();
        if (Files.exists(tempDir)) {
            FileUtils.deleteDirectory(tempDir);
        }
    }

    @Test
    void testValidateValidDataSource() throws IOException {
        MerkleDbDataSourceTest.createAndApplyDataSource(
                tempDir, "createAndCheckInternalNodeHashes", TestType.long_fixed, count, 0, dataSource -> {
                    // check db count
                    MerkleDbTestUtils.assertSomeDatabasesStillOpen(1L);

                    final var validator = new DataSourceValidator(dataSource);
                    // create some node hashes
                    dataSource.saveRecords(
                            count - 1,
                            count * 2L - 2,
                            IntStream.range(0, count - 1).mapToObj(MerkleDbDataSourceTest::createVirtualInternalRecord),
                            IntStream.range(count - 1, count * 2 - 1)
                                    .mapToObj(
                                            i -> TestType.long_fixed.dataType().createVirtualLeafRecord(i)),
                            Stream.empty());

                    assertTrue(validator.validate());
                });
    }

    @Test
    void testValidateInvalidDataSource() throws IOException {
        MerkleDbDataSourceTest.createAndApplyDataSource(
                tempDir, "createAndCheckInternalNodeHashes", TestType.long_fixed, count, 0, dataSource -> {
                    // check db count
                    MerkleDbTestUtils.assertSomeDatabasesStillOpen(1L);
                    final var validator = new DataSourceValidator(dataSource);
                    // create some node hashes
                    dataSource.saveRecords(
                            count - 1,
                            count * 2L - 2,
                            IntStream.range(0, count - 1).mapToObj(MerkleDbDataSourceTest::createVirtualInternalRecord),
                            // leaves are missing
                            Stream.empty(),
                            Stream.empty());
                    assertFalse(validator.validate());
                });
    }
}

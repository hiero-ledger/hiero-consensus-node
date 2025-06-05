// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.merkledb.test.fixtures.TestType;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import java.io.IOException;
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
    public void setUp() {
        count = 10_000;
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }

    @Test
    void testValidateValidDataSource() throws IOException {
        final KeySerializer keySerializer = TestType.fixed_fixed.dataType().getKeySerializer();
        final ValueSerializer valueSerializer = TestType.fixed_fixed.dataType().getValueSerializer();
        MerkleDbDataSourceTest.createAndApplyDataSource(
                tempDir, "createAndCheckInternalNodeHashes", TestType.fixed_fixed, count, 0, dataSource -> {
                    // check db count
                    MerkleDbTestUtils.assertSomeDatabasesStillOpen(1L);

                    final var validator = new DataSourceValidator<>(keySerializer, valueSerializer, dataSource);
                    // create some node hashes
                    dataSource.saveRecords(
                            count - 1,
                            count * 2L - 2,
                            IntStream.range(0, count - 1).mapToObj(MerkleDbDataSourceTest::createVirtualInternalRecord),
                            IntStream.range(count - 1, count * 2 - 1)
                                    .mapToObj(
                                            i -> TestType.fixed_fixed.dataType().createVirtualLeafRecord(i))
                                    .map(r -> r.toBytes(keySerializer, valueSerializer)),
                            Stream.empty());

                    assertTrue(validator.validate());
                });
    }

    @Test
    void testValidateInvalidDataSource() throws IOException {
        final KeySerializer keySerializer = TestType.fixed_fixed.dataType().getKeySerializer();
        final ValueSerializer valueSerializer = TestType.fixed_fixed.dataType().getValueSerializer();
        MerkleDbDataSourceTest.createAndApplyDataSource(
                tempDir, "createAndCheckInternalNodeHashes", TestType.fixed_fixed, count, 0, dataSource -> {
                    // check db count
                    MerkleDbTestUtils.assertSomeDatabasesStillOpen(1L);
                    final var validator = new DataSourceValidator<>(keySerializer, valueSerializer, dataSource);
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

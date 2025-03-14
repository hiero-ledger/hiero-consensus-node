// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.merkledb.test.fixtures.TestType;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
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
        // check db count
        assertEventuallyEquals(
                0L, MerkleDbDataSource::getCountOfOpenDatabases, Duration.ofSeconds(1), "Expected no open dbs");
    }

    @Test
    void testValidateValidDataSource() throws IOException {
        final KeySerializer keySerializer = TestType.fixed_fixed.dataType().getKeySerializer();
        final ValueSerializer valueSerializer = TestType.fixed_fixed.dataType().getValueSerializer();
        MerkleDbDataSourceTest.createAndApplyDataSource(
                tempDir, "createAndCheckInternalNodeHashes", TestType.fixed_fixed, count, 0, dataSource -> {
                    // check db count
                    assertEventuallyEquals(
                            1L,
                            MerkleDbDataSource::getCountOfOpenDatabases,
                            Duration.ofSeconds(1),
                            "Expected only 1 db");

                    final var validator = new DataSourceValidator<>(keySerializer, valueSerializer, dataSource);
                    // create some node hashes
                    dataSource.saveRecords(
                            count,
                            count * 2L,
                            IntStream.range(0, count).mapToObj(MerkleDbDataSourceTest::createVirtualInternalRecord),
                            IntStream.range(count, count * 2 + 1)
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
                    assertEventuallyEquals(
                            1L,
                            MerkleDbDataSource::getCountOfOpenDatabases,
                            Duration.ofSeconds(1),
                            "Expected only 1 db");
                    final var validator = new DataSourceValidator<>(keySerializer, valueSerializer, dataSource);
                    // create some node hashes
                    dataSource.saveRecords(
                            count,
                            count * 2L,
                            IntStream.range(0, count).mapToObj(MerkleDbDataSourceTest::createVirtualInternalRecord),
                            // leaves are missing
                            Stream.empty(),
                            Stream.empty());
                    assertFalse(validator.validate());
                });
    }
}

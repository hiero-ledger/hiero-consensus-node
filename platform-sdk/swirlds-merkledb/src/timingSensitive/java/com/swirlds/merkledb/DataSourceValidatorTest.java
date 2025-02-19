/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.merkledb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.merkledb.test.fixtures.TestType;
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
        MerkleDbDataSourceTest.createAndApplyDataSource(
                tempDir, "createAndCheckInternalNodeHashes", TestType.long_fixed, count, 0, dataSource -> {
                    // check db count
                    assertEventuallyEquals(
                            1L,
                            MerkleDbDataSource::getCountOfOpenDatabases,
                            Duration.ofSeconds(1),
                            "Expected only 1 db");

                    final var validator = new DataSourceValidator(dataSource);
                    // create some node hashes
                    dataSource.saveRecords(
                            count,
                            count * 2L,
                            IntStream.range(0, count).mapToObj(MerkleDbDataSourceTest::createVirtualInternalRecord),
                            IntStream.range(count, count * 2 + 1)
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
                    assertEventuallyEquals(
                            1L,
                            MerkleDbDataSource::getCountOfOpenDatabases,
                            Duration.ofSeconds(1),
                            "Expected only 1 db");
                    final var validator = new DataSourceValidator(dataSource);
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

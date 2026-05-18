// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.createHashChunkStream;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.merkledb.test.fixtures.AbstractMerkelDbTest;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.merkledb.test.fixtures.TestType;
import java.io.IOException;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DataSourceValidatorTest extends AbstractMerkelDbTest {

    private static final int count = 10_000;

    @Test
    void testValidateValidDataSource() throws IOException {
        createAndApplyDataSource(count, dataSource -> {
            // check db count
            MerkleDbTestUtils.assertSomeDatabasesStillOpen(1L);

            final var validator = new DataSourceValidator(dataSource);
            // create some node hashes
            dataSource.saveRecords(
                    count - 1,
                    count * 2L - 2,
                    createHashChunkStream((int) (count * 2L - 2), dataSource.getHashChunkHeight()),
                    IntStream.range(count - 1, count * 2 - 1)
                            .mapToObj(i -> TestType.long_fixed.dataType().createVirtualLeafRecord(i)),
                    Stream.empty(),
                    false);

            assertTrue(validator.validate());
        });
    }

    @Test
    void testValidateInvalidDataSource() throws IOException {
        createAndApplyDataSource(count, dataSource -> {
            // check db count
            MerkleDbTestUtils.assertSomeDatabasesStillOpen(1L);
            final var validator = new DataSourceValidator(dataSource);
            // create some node hashes
            dataSource.saveRecords(
                    count - 1,
                    count * 2L - 2,
                    createHashChunkStream(count * 2 - 2, dataSource.getHashChunkHeight()),
                    // leaves are missing
                    Stream.empty(),
                    Stream.empty(),
                    false);
            assertFalse(validator.validate());
        });
    }
}

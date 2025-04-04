// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.hash;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.merkledb.test.fixtures.ExampleByteArrayVirtualValue;
import com.swirlds.merkledb.test.fixtures.TestType;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

public class MerkleDbDataSourceHammerTest {

    private static Path testDirectory;

    @BeforeAll
    static void setup() throws Exception {
        testDirectory = LegacyTemporaryFileBuilder.buildTemporaryFile("MerkleDbDataSourceHammerTest", CONFIGURATION);
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void closeWhileFlushingTest(final TestType testType) throws IOException, InterruptedException {
        final Path dbPath = testDirectory.resolve("merkledb-closeWhileFlushingTest-" + testType);
        final MerkleDbDataSource dataSource = testType.dataType().createDataSource(dbPath, "vm", 1000, 0, false, false);

        final int count = 20;
        final List<Bytes> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(testType.dataType().createVirtualLongKey(i));
        }
        final List<ExampleByteArrayVirtualValue> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(testType.dataType().createVirtualValue(i + 1));
        }

        final CountDownLatch updateStarted = new CountDownLatch(1);
        final Thread closeThread = new Thread(() -> {
            try {
                updateStarted.await();
                Thread.sleep(new Random().nextInt(100));
                dataSource.close();
            } catch (Exception z) {
                // Print and ignore
                z.printStackTrace(System.err);
            }
        });
        closeThread.start();

        updateStarted.countDown();
        for (int i = 0; i < 10; i++) {
            final int k = i;
            try {
                dataSource.saveRecords(
                        count - 1,
                        2 * count - 2,
                        IntStream.range(0, count).mapToObj(j -> new VirtualHashRecord(k + j, hash(k + j + 1))),
                        IntStream.range(count - 1, count)
                                .mapToObj(j -> new VirtualLeafBytes(
                                        k + j,
                                        keys.get(k),
                                        values.get((k + j) % count),
                                        testType.dataType().getCodec())),
                        Stream.empty(),
                        true);
            } catch (Exception z) {
                // Print and ignore
                z.printStackTrace(System.err);
                break;
            }
        }

        closeThread.join();
    }
}

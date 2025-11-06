// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.merkledb.collections.LongListHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Hammer the compaction subsystem with as many small compactions as possible to try to overwhelm it.
 */
@Disabled("This test needs to be investigated")
class DataFileCollectionCompactionHammerTest {

    @BeforeAll
    public static void setup() {
        Configurator.setRootLevel(Level.WARN);
    }

    @AfterAll
    public static void cleanUp() {
        Configurator.reconfigure();
    }

    @ParameterizedTest
    @MethodSource("provideForBenchmark")
    @Tags({@Tag("Speed")})
    void benchmark(int numFiles, int maxEntriesPerFile) throws IOException {
        final Path tempFileDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory(
                "DataFileCollectionCompactionHammerTest", CONFIGURATION);
        assertDoesNotThrow(() -> {
            final LongListHeap index = new LongListHeap(1024 * 1024, 2L * 1024 * 1024 * 1024, 256 * 1024);
            String storeName = "benchmark";
            final MerkleDbConfig dbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
            final var coll = new DataFileCollection(
                    dbConfig, tempFileDir.resolve(storeName), storeName, (dataLocation, dataValue) -> {});
            final var compactor = new DataFileCompactor(dbConfig, storeName, coll, index, null, null, null, null);

            final Random rand = new Random(777);
            for (int i = 0; i < numFiles; i++) {
                coll.startWriting();
                final int numRecords = rand.nextInt(maxEntriesPerFile);
                long prevId = 0;
                for (int j = 0; j < numRecords; j++) {
                    final long id = prevId + rand.nextInt((maxEntriesPerFile * 10) - (int) prevId);
                    if (id == prevId) {
                        break;
                    }
                    prevId = id;
                    index.put(
                            id,
                            coll.storeDataItem(
                                    o -> {
                                        o.writeLong(id);
                                        o.writeLong(rand.nextLong());
                                    },
                                    2 * Long.BYTES));
                }
                coll.updateValidKeyRange(index.size() * 2L - 1, index.size() * 2L);
                coll.endWriting();
            }

            final long start = System.currentTimeMillis();
            final var filesToMerge = coll.getAllCompletedFiles();
            compactor.compactFiles(index, filesToMerge, 1);
            System.out.println(numFiles + " files took " + (System.currentTimeMillis() - start) + "ms");
            index.close();
        });
    }

    private static Stream<Arguments> provideForBenchmark() {
        return Stream.of(
                Arguments.of(2, 100),
                Arguments.of(2, 1000),
                Arguments.of(2, 10_000),
                Arguments.of(2, 100_000),
                Arguments.of(10, 100),
                Arguments.of(10, 1000),
                Arguments.of(10, 10_000),
                Arguments.of(10, 100_000),
                Arguments.of(100, 100),
                Arguments.of(100, 1000),
                Arguments.of(100, 10_000),
                Arguments.of(100, 100_000),
                Arguments.of(1000, 100),
                Arguments.of(1000, 1000),
                Arguments.of(1000, 10_000),
                Arguments.of(1000, 100_000),
                Arguments.of(1000, 1_000_000));
    }

    @Test
    void hammer() throws IOException, InterruptedException, ExecutionException {
        final Path tempFileDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory(
                "DataFileCollectionCompactionHammerTest", CONFIGURATION);
        final LongListHeap index = new LongListHeap(1024 * 1024, 2L * 1024 * 1024 * 1024, 256 * 1024);
        String storeName = "hammer";
        final MerkleDbConfig dbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        final var coll = new DataFileCollection(
                dbConfig, tempFileDir.resolve(storeName), storeName, (dataLocation, dataValue) -> {});
        final var compactor = new DataFileCompactor(dbConfig, storeName, coll, index, null, null, null, null);

        final Random rand = new Random(777);
        final AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService writerService = Executors.newSingleThreadExecutor();
        Future<?> writerFuture = writerService.submit((Callable<Void>) () -> {
            while (!stop.get()) {
                coll.startWriting();
                final int numRecords = rand.nextInt(2500);
                long prevId = 0;
                for (int i = 0; i < numRecords; i++) {
                    final long id = prevId + rand.nextInt((10_000) - (int) prevId);
                    if (id == prevId) {
                        break;
                    }
                    prevId = id;
                    index.put(
                            id,
                            coll.storeDataItem(
                                    o -> {
                                        o.writeLong(id);
                                        o.writeLong(rand.nextLong());
                                    },
                                    2 * Long.BYTES));
                }
                coll.updateValidKeyRange(index.size() * 2L - 1, index.size() * 2L);
                coll.endWriting();
            }
            return null;
        });

        ExecutorService compactorService = Executors.newSingleThreadExecutor();
        Future<?> compactorFuture = compactorService.submit((Callable<Void>) () -> {
            while (!stop.get()) {
                final List<DataFileReader> filesToMerge = coll.getAllCompletedFiles();
                if (filesToMerge.size() > compactor.getMinNumberOfFilesToCompact()) {
                    System.out.println(filesToMerge.size());
                }
                if (filesToMerge.size() > 10000) {
                    stop.set(true);
                }
                compactor.compactFiles(index, filesToMerge, 1);
            }
            return null;
        });

        for (int i = 0; i < 100; i++) {
            System.out.println("Iteration " + i);
            compactor.pauseCompaction();
            SECONDS.sleep(3);
            compactor.resumeCompaction();
            SECONDS.sleep(1);
        }
        stop.set(true);
        compactorFuture.get();
        writerFuture.get();
        final var filesToMerge = coll.getAllCompletedFiles();
        assertTrue(filesToMerge.size() < 10000, "Too many files! We didn't keep up");
        coll.close();
        index.close();
    }
}

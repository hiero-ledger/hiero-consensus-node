// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyFalse;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.*;
import static com.swirlds.virtualmap.datasource.VirtualDataSource.INVALID_PATH;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.function.CheckedConsumer;
import com.swirlds.base.units.UnitConstants;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.test.fixtures.ExampleByteArrayVirtualValue;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.merkledb.test.fixtures.TestType;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.Metric.ValueType;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class MerkleDbDataSourceTest {

    private static final int COUNT = 10_000;
    private static final Random RANDOM = new Random(1234);

    private Path testDirectory;

    @BeforeAll
    static void setup() throws Exception {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds.merkledb");
    }

    /**
     * Keep track of initial direct memory used already, so we can check if we leak over and above
     * what we started with
     */
    private long directMemoryUsedAtStart;

    @BeforeEach
    void initializeDirectMemoryAtStart() {
        directMemoryUsedAtStart = getDirectMemoryUsedBytes();
    }

    @BeforeEach
    void setupDatabaseDir() throws IOException {
        testDirectory = LegacyTemporaryFileBuilder.buildTemporaryFile("MerkleDbDataSourceTest", CONFIGURATION);
    }

    @AfterEach
    void checkDirectMemoryForLeaks() {
        // check all memory is freed after DB is closed
        assertTrue(
                checkDirectMemoryIsCleanedUpToLessThanBaseUsage(directMemoryUsedAtStart),
                "Direct Memory used is more than base usage even after 20 gc() calls. At start was "
                        + (directMemoryUsedAtStart * UnitConstants.BYTES_TO_MEBIBYTES)
                        + "MB and is now "
                        + (getDirectMemoryUsedBytes() * UnitConstants.BYTES_TO_MEBIBYTES)
                        + "MB");
    }

    // =================================================================================================================
    // Tests

    @ParameterizedTest
    @MethodSource("provideParameters")
    void createAndCheckInternalNodeHashes(final TestType testType, final int hashesRamToDiskThreshold)
            throws IOException, InterruptedException {

        final String tableName = "createAndCheckInternalNodeHashes";
        // check db count
        MerkleDbTestUtils.assertAllDatabasesClosed();
        // create db
        final int count = 10_000;
        createAndApplyDataSource(testDirectory, tableName, testType, count, hashesRamToDiskThreshold, dataSource -> {
            // check db count
            MerkleDbTestUtils.assertSomeDatabasesStillOpen(1L);

            // create some node hashes
            dataSource.saveRecords(
                    count - 1,
                    count * 2 - 2,
                    IntStream.range(0, count * 2 - 1).mapToObj(MerkleDbDataSourceTest::createVirtualInternalRecord),
                    Stream.empty(),
                    Stream.empty());

            // check all the node hashes
            for (int i = 0; i < count; i++) {
                final var hash = dataSource.loadHash(i);
                assertEquals(hash(i), hash, "The hash for [" + i + "] should not have changed since it was created");
            }

            final IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> dataSource.loadHash(-1),
                    "loadInternalRecord should throw IAE on invalid path");
            assertEquals("Path (-1) is not valid", e.getMessage(), "Detail message should capture the failure");

            // close data source
            dataSource.close();
            // check db count
            MerkleDbTestUtils.assertAllDatabasesClosed();
            // check the database was deleted
            assertEventuallyFalse(
                    () -> Files.exists(testDirectory.resolve(tableName)),
                    Duration.ofSeconds(1),
                    "Database should have been deleted by close()");
        });
    }

    private static Stream<Arguments> provideParameters() {
        final ArrayList<Arguments> arguments = new ArrayList<>(TestType.values().length * 3);
        final int[] ramDiskSplitOptions = new int[] {0, COUNT / 2, Integer.MAX_VALUE};
        for (final TestType testType : TestType.values()) {
            for (final int ramDiskSplit : ramDiskSplitOptions) {
                arguments.add(Arguments.of(testType, ramDiskSplit, false));
                arguments.add(Arguments.of(testType, ramDiskSplit, true));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void testRandomHashUpdates(final TestType testType) throws IOException {
        final int testSize = 1000;
        createAndApplyDataSource(testDirectory, "test2", testType, testSize, dataSource -> {
            // create some node hashes
            dataSource.saveRecords(
                    testSize,
                    testSize * 2,
                    IntStream.range(0, testSize).mapToObj(MerkleDbDataSourceTest::createVirtualInternalRecord),
                    Stream.empty(),
                    Stream.empty());
            // create 4 lists with random hash updates some *10 hashes
            final IntArrayList[] lists = new IntArrayList[3];
            for (int i = 0; i < lists.length; i++) {
                lists[i] = new IntArrayList();
            }
            IntStream.range(0, testSize).forEach(i -> lists[RANDOM.nextInt(lists.length)].add(i));
            for (final IntArrayList list : lists) {
                dataSource.saveRecords(
                        testSize,
                        testSize * 2,
                        list.primitiveStream().mapToObj(i -> new VirtualHashRecord(i, hash(i * 10))),
                        Stream.empty(),
                        Stream.empty());
            }
            // check all the node hashes
            IntStream.range(0, testSize).forEach(i -> {
                try {
                    assertEquals(
                            hash(i * 10),
                            dataSource.loadHash(i),
                            "Internal hashes should not have changed since they were created");
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
            });
        });
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void createAndCheckLeaves(final TestType testType) throws IOException {
        final int count = 10_000;
        createAndApplyDataSource(testDirectory, "test3", testType, count, dataSource -> {
            // create some leaves
            dataSource.saveRecords(
                    count - 1,
                    count * 2 - 2,
                    IntStream.range(count - 1, count * 2 - 1)
                            .mapToObj(MerkleDbDataSourceTest::createVirtualInternalRecord),
                    IntStream.range(count - 1, count * 2 - 1)
                            .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)),
                    Stream.empty());
            // check all the leaf data
            IntStream.range(count - 1, count * 2 - 1).forEach(i -> assertLeaf(testType, dataSource, i, i));

            // invalid path should throw an exception
            assertThrows(
                    IllegalArgumentException.class,
                    () -> dataSource.loadLeafRecord(INVALID_PATH),
                    "Loading a leaf record from invalid path should throw Exception");

            final IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> dataSource.loadHash(-1),
                    "Loading a negative path should fail");
            assertEquals("Path (-1) is not valid", e.getMessage(), "Detail message should capture the failure");
        });
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void updateLeaves(final TestType testType) throws IOException, InterruptedException {
        final int incFirstLeafPath = 1;
        final int exclLastLeafPath = 1001;

        createAndApplyDataSource(testDirectory, "test4", testType, exclLastLeafPath - incFirstLeafPath, dataSource -> {
            // create some leaves
            dataSource.saveRecords(
                    incFirstLeafPath,
                    exclLastLeafPath,
                    IntStream.range(incFirstLeafPath, exclLastLeafPath)
                            .mapToObj(MerkleDbDataSourceTest::createVirtualInternalRecord),
                    IntStream.range(incFirstLeafPath, exclLastLeafPath)
                            .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)),
                    Stream.empty());
            // check all the leaf data
            IntStream.range(incFirstLeafPath, exclLastLeafPath).forEach(i -> assertLeaf(testType, dataSource, i, i));
            // update all to i+10,000 in a random order
            final int[] randomInts = shuffle(
                    RANDOM, IntStream.range(incFirstLeafPath, exclLastLeafPath).toArray());
            dataSource.saveRecords(
                    incFirstLeafPath,
                    exclLastLeafPath,
                    Stream.empty(),
                    Arrays.stream(randomInts)
                            .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i, i, i + 10_000))
                            .sorted(Comparator.comparingLong(VirtualLeafBytes::path)),
                    Stream.empty());
            assertEquals(
                    testType.dataType().createVirtualLeafRecord(100, 100, 100 + 10_000),
                    testType.dataType().createVirtualLeafRecord(100, 100, 100 + 10_000),
                    "same call to createVirtualLeafRecord returns different results");
            // check all the leaf data
            IntStream.range(incFirstLeafPath, exclLastLeafPath)
                    .forEach(i -> assertLeaf(testType, dataSource, i, i, i, i + 10_000));
            // delete a couple leaves
            dataSource.saveRecords(
                    incFirstLeafPath,
                    exclLastLeafPath,
                    Stream.empty(),
                    Stream.empty(),
                    IntStream.range(incFirstLeafPath + 10, incFirstLeafPath + 20)
                            .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)));
            // check deleted items are no longer there
            for (int i = (incFirstLeafPath + 10); i < (incFirstLeafPath + 20); i++) {
                final Bytes key = testType.dataType().createVirtualLongKey(i);
                assertEqualsAndPrint(null, dataSource.loadLeafRecord(key));
            }
            // check all remaining leaf data
            IntStream.range(incFirstLeafPath, incFirstLeafPath + 10)
                    .forEach(i -> assertLeaf(testType, dataSource, i, i, i, i + 10_000));
            IntStream.range(incFirstLeafPath + 21, exclLastLeafPath)
                    .forEach(i -> assertLeaf(testType, dataSource, i, i, i, i + 10_000));
        });
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void moveLeaf(final TestType testType) throws IOException {
        final int incFirstLeafPath = 1;
        final int exclLastLeafPath = 1001;

        createAndApplyDataSource(testDirectory, "test5", testType, exclLastLeafPath - incFirstLeafPath, dataSource -> {
            // create some leaves
            dataSource.saveRecords(
                    incFirstLeafPath,
                    exclLastLeafPath,
                    IntStream.range(incFirstLeafPath, exclLastLeafPath)
                            .mapToObj(MerkleDbDataSourceTest::createVirtualInternalRecord),
                    IntStream.range(incFirstLeafPath, exclLastLeafPath)
                            .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)),
                    Stream.empty());
            // check 250 and 500
            assertLeaf(testType, dataSource, 250, 250);
            assertLeaf(testType, dataSource, 500, 500);
            // move a leaf from 500 to 250, under new API there is no move as such, so we just write 500 leaf at 250
            // path
            final VirtualHashRecord vir500 = new VirtualHashRecord(
                    testType.dataType().createVirtualInternalRecord(250).path(), hash(500));

            VirtualLeafBytes vlr500 = testType.dataType().createVirtualLeafRecord(500);
            vlr500 = vlr500.withPath(250);
            dataSource.saveRecords(
                    incFirstLeafPath, exclLastLeafPath, Stream.of(vir500), Stream.of(vlr500), Stream.empty());
            // check 250 now has 500's data
            assertLeaf(testType, dataSource, 700, 700);
            assertEquals(
                    testType.dataType().createVirtualLeafRecord(500, 500, 500),
                    dataSource.loadLeafRecord(500),
                    "creating/loading same LeafRecord gives different results");
            assertLeaf(testType, dataSource, 250, 500);
        });
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void createAndDeleteAllLeaves(final TestType testType) throws IOException {
        final int count = 1000;
        createAndApplyDataSource(testDirectory, "test3", testType, count, dataSource -> {
            // create some leaves
            dataSource.saveRecords(
                    count - 1,
                    count * 2 - 2,
                    IntStream.range(count - 1, count * 2 - 1)
                            .mapToObj(MerkleDbDataSourceTest::createVirtualInternalRecord),
                    IntStream.range(count - 1, count * 2 - 1)
                            .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)),
                    Stream.empty());
            // check all the leaf data
            IntStream.range(count - 1, count * 2 - 1).forEach(i -> assertLeaf(testType, dataSource, i, i));

            // delete everything
            dataSource.saveRecords(
                    -1,
                    -1,
                    Stream.empty(),
                    Stream.empty(),
                    IntStream.range(count - 1, count * 2 - 1)
                            .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)));
            // check the data source is empty
            for (int i = 0; i < count * 2 - 1; i++) {
                assertNull(dataSource.loadHash(i));
                assertNull(dataSource.loadLeafRecord(i));
                final Bytes key = testType.dataType().createVirtualLongKey(i);
                assertNull(dataSource.loadLeafRecord(key));
            }
        });
    }

    @Test
    void preservesInterruptStatusWhenInterruptedSavingRecords() throws IOException {
        createAndApplyDataSource(testDirectory, "test6", TestType.long_fixed, 1000, dataSource -> {
            final InterruptRememberingThread savingThread = slowRecordSavingThread(dataSource);

            savingThread.start();
            /* Don't interrupt until the saving thread will be blocked on the CountDownLatch,
             * awaiting all internal records to be written. */
            sleepUnchecked(100L);

            savingThread.interrupt();
            /* Give some time for the interrupt to set the thread's interrupt status */
            sleepUnchecked(100L);

            System.out.println("Checking interrupt count");
            assertEquals(
                    2,
                    savingThread.numInterrupts(),
                    "Thread interrupt status should NOT be cleared (two total interrupts)");
            savingThread.join();
        });
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void createCloseSnapshotCheckDelete(final TestType testType) throws IOException {
        final int count = 10_000;
        final String tableName = "testDB";
        final Path originalDbPath = testDirectory.resolve("merkledb-" + testType);
        // array to hold the snapshot path
        final Path[] snapshotDbPathRef = new Path[1];
        createAndApplyDataSource(originalDbPath, tableName, testType, count, dataSource -> {
            // create some leaves
            dataSource.saveRecords(
                    count - 1,
                    count * 2 - 2,
                    IntStream.range(count - 1, count * 2 - 1)
                            .mapToObj(i -> testType.dataType().createVirtualInternalRecord(i)),
                    IntStream.range(count - 1, count * 2 - 1)
                            .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)),
                    Stream.empty());
            // check all the leaf data
            IntStream.range(count - 1, count * 2 - 1).forEach(i -> assertLeaf(testType, dataSource, i, i));
            // create a snapshot
            snapshotDbPathRef[0] = testDirectory.resolve("merkledb-" + testType + "_SNAPSHOT");
            dataSource.snapshot(snapshotDbPathRef[0]);
            // close data source
            dataSource.close();
            // check directory is deleted on close
            assertFalse(Files.exists(originalDbPath), "Data source dir should be deleted");
            assertTrue(Files.exists(snapshotDbPathRef[0]), "Snapshot dir [" + snapshotDbPathRef[0] + "] should exist");
        });

        // reopen data source and check
        final MerkleDbDataSource dataSource2 =
                testType.dataType().getDataSource(snapshotDbPathRef[0], tableName, false);
        try {
            // check all the leaf data
            IntStream.range(count - 1, count * 2 - 1).forEach(i -> assertLeaf(testType, dataSource2, i, i));
        } finally {
            // close data source
            dataSource2.close();
        }
        // check db count
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }

    boolean directMemoryUsageByDataFileIteratorWorkaroundApplied = false;

    // When the first DataFileIterator is initialized, it allocates 16Mb direct byte buffer internally.
    // Since we have direct memory usage checks after each test case, it's reported as a memory leak.
    // A workaround is to reset memory usage value right after the first usage of iterator. No need to
    // do it before each test run, it's enough to do just once
    void reinitializeDirectMemoryUsage() {
        if (!directMemoryUsageByDataFileIteratorWorkaroundApplied) {
            initializeDirectMemoryAtStart();
            directMemoryUsageByDataFileIteratorWorkaroundApplied = true;
        }
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void snapshotRestoreIndex(final TestType testType) throws IOException {
        final int count = 1000;
        final String tableName = "vm";
        final Path originalDbPath = testDirectory.resolve("merkledb-snapshotRestoreIndex-" + testType);
        final int[] deltas = {-10, 0, 10};
        for (int delta : deltas) {
            createAndApplyDataSource(originalDbPath, tableName, testType, count + Math.abs(delta), 0, dataSource -> {
                // create some records
                dataSource.saveRecords(
                        count - 1,
                        count * 2 - 2,
                        IntStream.range(0, count * 2 - 1).mapToObj(i -> createVirtualInternalRecord(i, i + 1)),
                        IntStream.range(count - 1, count * 2 - 1)
                                .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)),
                        Stream.empty());
                if (delta != 0) {
                    // create some more, current leaf path range shifted by delta
                    dataSource.saveRecords(
                            count - 1 + delta,
                            count * 2 - 2 + 2 * delta,
                            IntStream.range(0, count * 2 - 1 + 2 * delta)
                                    .mapToObj(i -> createVirtualInternalRecord(i, i + 1)),
                            IntStream.range(count - 1 + delta, count * 2 - 1 + 2 * delta)
                                    .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)),
                            Stream.empty());
                }
                // create a snapshot
                final Path snapshotDbPath =
                        testDirectory.resolve("merkledb-snapshotRestoreIndex-" + testType + "_SNAPSHOT");
                dataSource.snapshot(snapshotDbPath);
                // close data source
                dataSource.close();

                final MerkleDbPaths snapshotPaths = new MerkleDbPaths(snapshotDbPath);
                // Delete all indices
                Files.delete(snapshotPaths.pathToDiskLocationLeafNodesFile);
                Files.delete(snapshotPaths.pathToDiskLocationInternalNodesFile);
                // There is no way to use MerkleDbPaths to get bucket index file path
                Files.deleteIfExists(snapshotPaths.keyToPathDirectory.resolve(tableName + "_bucket_index.ll"));

                final MerkleDbDataSource snapshotDataSource =
                        testType.dataType().getDataSource(snapshotDbPath, tableName, false);
                reinitializeDirectMemoryUsage();
                // Check hashes
                IntStream.range(0, count * 2 - 1 + 2 * delta).forEach(i -> assertHash(snapshotDataSource, i, i + 1));
                assertNullHash(snapshotDataSource, count * 2 + 2 * delta);
                // Check leaves
                IntStream.range(0, count - 2 + delta).forEach(i -> assertNullLeaf(snapshotDataSource, i));
                IntStream.range(count - 1 + delta, count * 2 - 1 + 2 * delta)
                        .forEach(i -> assertLeaf(testType, snapshotDataSource, i, i, i + 1, i));
                assertNullLeaf(snapshotDataSource, count * 2 + 2 * delta);
                // close data source
                snapshotDataSource.close();

                // check db count
                MerkleDbTestUtils.assertAllDatabasesClosed();
            });
        }
    }

    @Test
    void preservesInterruptStatusWhenInterruptedClosing() throws IOException {
        createAndApplyDataSource(testDirectory, "test8", TestType.long_fixed, 1001, dataSource -> {
            /* Keep an executor busy */
            final InterruptRememberingThread savingThread = slowRecordSavingThread(dataSource);
            savingThread.start();
            sleepUnchecked(100L);

            final InterruptRememberingThread closingThread = new InterruptRememberingThread(() -> {
                try {
                    dataSource.close();
                } catch (final IOException ignore) {
                }
            });

            closingThread.start();
            closingThread.interrupt();
            sleepUnchecked(100L);

            System.out.println("Checking interrupt count for " + closingThread.getName());
            final var numInterrupts = closingThread.numInterrupts();
            assertEquals(2, numInterrupts, "Thread interrupt status should NOT be cleared (two total interrupts)");
            closingThread.join();
            savingThread.join();
        });
    }

    @Test
    void canConstructWithOnDiskInternalHashStore() {
        final long finiteInMemHashThreshold = 1_000_000;
        assertDoesNotThrow(
                () -> createAndApplyDataSource(
                        testDirectory,
                        "test9",
                        TestType.long_fixed,
                        1000,
                        finiteInMemHashThreshold,
                        MerkleDbDataSource::close),
                "Should be possible to instantiate data source using on-disk internal hash store");
    }

    @Test
    void canConstructWithNoRamInternalHashStore() {
        assertDoesNotThrow(
                () -> createAndApplyDataSource(
                        testDirectory, "test10", TestType.long_fixed, 1000, 0, MerkleDbDataSource::close),
                "Should be possible to instantiate data source with no in-memory internal hash store");
    }

    @Test
    void canConstructStandardStoreWithMergingDisabled() {
        assertDoesNotThrow(
                () -> TestType.long_fixed
                        .dataType()
                        .createDataSource(testDirectory, "testDB", 1000, Long.MAX_VALUE, false, false)
                        .close(),
                "Should be possible to instantiate data source with merging disabled");
        // check db count
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void dirtyDeletedLeavesBetweenFlushesOnReconnect(final TestType testType) throws IOException {
        final String tableName = "vm";
        final Path originalDbPath =
                testDirectory.resolve("merkledb-dirtyDeletedLeavesBetweenFlushesOnReconnect-" + testType);
        createAndApplyDataSource(originalDbPath, tableName, testType, 100, 0, dataSource -> {
            final List<Bytes> keys = new ArrayList<>(31);
            for (int i = 0; i < 31; i++) {
                keys.add(testType.dataType().createVirtualLongKey(i));
            }
            final List<ExampleByteArrayVirtualValue> values = new ArrayList<>(31);
            for (int i = 0; i < 31; i++) {
                values.add(testType.dataType().createVirtualValue(i + 1));
            }

            // Initial DB state: 11 leaves, paths 10 to 20
            dataSource.saveRecords(
                    10,
                    20,
                    IntStream.range(0, 21).mapToObj(i -> createVirtualInternalRecord(i, i + 1)),
                    IntStream.range(10, 21)
                            .mapToObj(i -> new VirtualLeafBytes(
                                    i,
                                    keys.get(i),
                                    values.get(i),
                                    testType.dataType().getCodec())),
                    Stream.empty(),
                    true);

            // Load all leaves back from DB
            final List<VirtualLeafBytes> oldLeaves = new ArrayList<>(11);
            for (int i = 10; i < 21; i++) {
                final VirtualLeafBytes leaf = dataSource.loadLeafRecord(i);
                assertNotNull(leaf);
                assertEquals(i, leaf.path());
                oldLeaves.add(leaf);
            }

            // First flush: move leaves 10 to 15 to paths 15 to 20, delete leaves 16 to 20
            dataSource.saveRecords(
                    10,
                    20,
                    IntStream.range(0, 21).mapToObj(i -> createVirtualInternalRecord(i, i + 2)),
                    IntStream.range(10, 21)
                            .mapToObj(i -> new VirtualLeafBytes(
                                    i,
                                    keys.get(i - 5),
                                    values.get(i - 5),
                                    testType.dataType().getCodec())),
                    oldLeaves.subList(6, 11).stream(),
                    true);

            // Check data after the first flush
            for (int i = 0; i < 21; i++) {
                final Hash hash = dataSource.loadHash(i);
                assertNotNull(hash);
                assertEquals(hash(i + 2), hash, "Wrong hash at path " + i);
            }
            for (int i = 5; i < 16; i++) {
                final VirtualLeafBytes leaf = dataSource.loadLeafRecord(keys.get(i));
                assertNotNull(leaf, "Leaf with key " + i + " not found");
                // // key 10 is moved to path 15, key 11 is moved to path 16, etc.
                assertEquals(i + 5, leaf.path(), "Leaf path mismatch at path " + i);
                assertEquals(keys.get(i), leaf.keyBytes(), "Wrong key at path " + i);
                assertEquals(values.get(i), leaf.value(testType.dataType().getCodec()), "Wrong value at path " + i);
            }
            for (int i = 16; i < 21; i++) {
                final VirtualLeafBytes leafBytes = dataSource.loadLeafRecord(keys.get(i));
                assertNull(leafBytes); // no more leafs for keys 16 to 20
            }

            // Second flush: don't update leaves, delete leaves 10 to 15 (they must not be deleted
            // as they were updated during the first flush)
            dataSource.saveRecords(
                    10,
                    20,
                    IntStream.range(0, 21).mapToObj(i -> createVirtualInternalRecord(i, i + 3)),
                    Stream.empty(),
                    oldLeaves.subList(0, 6).stream(),
                    true);

            // Check data after the second flush
            for (int i = 0; i < 21; i++) {
                final Hash hash = dataSource.loadHash(i);
                assertNotNull(hash);
                assertEquals(hash(i + 3), hash, "Wrong hash at path " + i);
            }
            for (int i = 5; i < 16; i++) {
                final VirtualLeafBytes leaf = dataSource.loadLeafRecord(keys.get(i));
                assertNotNull(leaf, "Leaf with key " + i + " not found");
                // // key 10 was moved to path 15, key 11 is moved to path 16, etc.
                assertEquals(i + 5, leaf.path(), "Leaf path mismatch at path " + i);
                assertEquals(keys.get(i), leaf.keyBytes(), "Wrong key at path " + i);
                assertEquals(values.get(i), leaf.value(testType.dataType().getCodec()), "Wrong value at path " + i);
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void testRebuildHDHMIndex() throws Exception {
        final String label = "testRebuildHDHMIndex";
        final TestType testType = TestType.variable_variable;
        final Path originalDbPath = testDirectory.resolve("merkledb-testRebuildHDHMIndex-" + testType);
        final Path snapshotDbPath1 = testDirectory.resolve("merkledb-testRebuildHDHMIndex_SNAPSHOT1");
        final Path snapshotDbPath2 = testDirectory.resolve("merkledb-testRebuildHDHMIndex_SNAPSHOT2");
        createAndApplyDataSource(originalDbPath, label, testType, 100, 0, dataSource -> {
            // Flush 1: leaf path range is [8,16]
            dataSource.saveRecords(
                    8,
                    16,
                    IntStream.range(0, 17).mapToObj(i -> createVirtualInternalRecord(i, 2 * i)),
                    IntStream.range(8, 17).mapToObj(i -> testType.dataType().createVirtualLeafRecord(i, i, 3 * i)),
                    Stream.empty());
            // Flush 2: leaf path range is [9,18]. Note that the list of deleted leaves is empty, so one of the leaves
            // becomes stale in the database. This is not what we have in production, but it will let test rebuilding
            // HDHM bucket index
            dataSource.saveRecords(
                    9,
                    18,
                    IntStream.range(0, 19).mapToObj(i -> createVirtualInternalRecord(i, 2 * i)),
                    IntStream.range(9, 19).mapToObj(i -> testType.dataType().createVirtualLeafRecord(i, i, 3 * i)),
                    Stream.empty());
            // Create snapshots
            dataSource.snapshot(snapshotDbPath1);
            dataSource.snapshot(snapshotDbPath2);
            // close data source
            dataSource.close();
        });

        // Load snapshot 1 with empty tablesToRepairHdhm config. It's expected to contain a stale key
        final Configuration config1 = ConfigurationBuilder.create()
                .withConfigDataType(MerkleDbConfig.class)
                .withConfigDataType(VirtualMapConfig.class)
                .withConfigDataType(TemporaryFileConfig.class)
                .withConfigDataType(StateCommonConfig.class)
                .withSource(new SimpleConfigSource("merkleDb.tablesToRepairHdhm", ""))
                .build();
        final MerkleDbDataSource snapshotDataSource1 =
                new MerkleDbDataSource(snapshotDbPath1, config1, label, false, false);
        IntStream.range(9, 19).forEach(i -> assertLeaf(testType, snapshotDataSource1, i, i, 2 * i, 3 * i));
        final Bytes staleKey = testType.dataType().createVirtualLongKey(8);
        assertEquals(8, snapshotDataSource1.findKey(staleKey));
        snapshotDataSource1.close();

        // Now load snapshot 2, but with HDHM bucket index rebuilt. There must be no stale keys there
        final Configuration config2 = ConfigurationBuilder.create()
                .withConfigDataType(MerkleDbConfig.class)
                .withConfigDataType(VirtualMapConfig.class)
                .withConfigDataType(TemporaryFileConfig.class)
                .withConfigDataType(StateCommonConfig.class)
                .withSource(new SimpleConfigSource("merkleDb.tablesToRepairHdhm", label))
                .build();
        final MerkleDbDataSource snapshotDataSource2 =
                new MerkleDbDataSource(snapshotDbPath2, config2, label, false, false);
        IntStream.range(9, 19).forEach(i -> assertLeaf(testType, snapshotDataSource2, i, i, 2 * i, 3 * i));
        assertEquals(-1, snapshotDataSource2.findKey(staleKey));
        snapshotDataSource2.close();
    }

    @Test
    void copyStatisticsTest() throws Exception {
        // This test simulates what happens on reconnect and makes sure that MerkleDb stats are reported
        // for the copy correctly
        final String label = "copyStatisticsTest";
        final TestType testType = TestType.variable_variable;
        final Metrics metrics = testType.getMetrics();
        createAndApplyDataSource(testDirectory, label, testType, 16, dataSource -> {
            dataSource.registerMetrics(metrics);
            assertEquals(
                    1L,
                    metrics.getMetric(MerkleDbStatistics.STAT_CATEGORY, "merkledb_count")
                            .get(ValueType.VALUE));
            final List<VirtualLeafBytes> dirtyLeaves = IntStream.range(15, 30)
                    .mapToObj(t -> new VirtualLeafBytes(
                            t,
                            testType.dataType().createVirtualLongKey(t),
                            testType.dataType().createVirtualValue(t),
                            testType.dataType().getCodec()))
                    .toList();
            // No dirty/deleted leaves - no new files created
            dataSource.saveRecords(15, 30, Stream.empty(), Stream.empty(), Stream.empty(), false);
            final IntegerGauge sourceCounter = (IntegerGauge)
                    metrics.getMetric(MerkleDbStatistics.STAT_CATEGORY, "ds_files_leavesStoreFileCount_" + label);
            assertEquals(0L, sourceCounter.get());
            // Now save some dirty leaves
            dataSource.saveRecords(15, 30, Stream.empty(), dirtyLeaves.stream(), Stream.empty(), false);
            assertEquals(1L, sourceCounter.get());
            final Path copyPath = LegacyTemporaryFileBuilder.buildTemporaryFile("copyStatisticsTest", CONFIGURATION);
            dataSource.snapshot(copyPath);
            final MerkleDbDataSource copy =
                    testType.dataType().getDataSource(copyPath, dataSource.getTableName(), true);
            try {
                assertEquals(
                        2L, metrics.getMetric("merkle_db", "merkledb_count").get(ValueType.VALUE));
                copy.copyStatisticsFrom(dataSource);
                VirtualLeafBytes leaf1 = dirtyLeaves.get(1);
                leaf1 = leaf1.withPath(4);
                copy.saveRecords(4, 8, Stream.empty(), Stream.of(leaf1), Stream.empty(), false);
                final IntegerGauge copyCounter = (IntegerGauge)
                        metrics.getMetric(MerkleDbStatistics.STAT_CATEGORY, "ds_files_leavesStoreFileCount_" + label);
                assertEquals(2L, copyCounter.get());
            } finally {
                copy.close();
            }
        });
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

    // =================================================================================================================
    // Helper Methods

    public static void createAndApplyDataSource(
            final Path testDirectory,
            final String name,
            final TestType testType,
            final int size,
            CheckedConsumer<MerkleDbDataSource, Exception> dataSourceConsumer)
            throws IOException {
        createAndApplyDataSource(testDirectory, name, testType, size, Long.MAX_VALUE, dataSourceConsumer);
    }

    public static void createAndApplyDataSource(
            final Path testDirectory,
            final String name,
            final TestType testType,
            final int size,
            final long hashesRamToDiskThreshold,
            CheckedConsumer<MerkleDbDataSource, Exception> dataSourceConsumer)
            throws IOException {
        final MerkleDbDataSource dataSource =
                testType.dataType().createDataSource(testDirectory, name, size, hashesRamToDiskThreshold, false, false);
        try {
            dataSourceConsumer.accept(dataSource);
        } catch (Throwable e) {
            fail(e);
        } finally {
            dataSource.close();
        }
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }

    public static VirtualHashRecord createVirtualInternalRecord(final int i) {
        return createVirtualInternalRecord(i, i);
    }

    public static VirtualHashRecord createVirtualInternalRecord(final long path, final int i) {
        return new VirtualHashRecord(path, hash(i));
    }

    public static void assertHash(final MerkleDbDataSource dataSource, final long path, final int i) {
        try {
            assertEqualsAndPrint(hash(i), dataSource.loadHash(path));
        } catch (final Exception e) {
            e.printStackTrace(System.err);
            fail("Exception should not have been thrown here!");
        }
    }

    public static void assertNullHash(final MerkleDbDataSource dataSource, final long path) {
        try {
            assertNull(dataSource.loadHash(path));
        } catch (final Exception e) {
            e.printStackTrace(System.err);
            fail("Exception should not have been thrown here!");
        }
    }

    public static void assertLeaf(
            final TestType testType, final MerkleDbDataSource dataSource, final long path, final int i) {
        assertLeaf(testType, dataSource, path, i, i, i);
    }

    public static void assertLeaf(
            final TestType testType,
            final MerkleDbDataSource dataSource,
            final long path,
            final int i,
            final int hashIndex,
            final int valueIndex) {
        try {
            final VirtualLeafBytes expectedRecord = testType.dataType().createVirtualLeafRecord(path, i, valueIndex);
            final Bytes key = testType.dataType().createVirtualLongKey(i);
            // things that should have changed
            assertEqualsAndPrint(expectedRecord, dataSource.loadLeafRecord(key));
            assertEqualsAndPrint(expectedRecord, dataSource.loadLeafRecord(path));
            assertEquals(hash(hashIndex), dataSource.loadHash(path), "unexpected Hash value for path " + path);
        } catch (final Exception e) {
            e.printStackTrace(System.err);
            fail("Exception should not have been thrown here!");
        }
    }

    public static void assertNullLeaf(final MerkleDbDataSource dataSource, final long path) {
        try {
            assertNull(dataSource.loadLeafRecord(path));
        } catch (final Exception e) {
            e.printStackTrace(System.err);
            fail("Exception should not have been thrown here!");
        }
    }

    public static <T> void assertEqualsAndPrint(final T recordA, final T recordB) {
        assertEquals(
                recordA == null ? null : recordA.toString(),
                recordB == null ? null : recordB.toString(),
                "Equal records should have the same toString representation");
    }

    private void sleepUnchecked(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException ignore) {
            /* No-op */
        }
    }

    private InterruptRememberingThread slowRecordSavingThread(final MerkleDbDataSource dataSource) {
        return new InterruptRememberingThread(() -> {
            try {
                dataSource.saveRecords(
                        1000,
                        2000,
                        IntStream.range(1, 5).mapToObj(i -> {
                            System.out.println("SLOWLY loading record #"
                                    + i
                                    + " in "
                                    + Thread.currentThread().getName());
                            sleepUnchecked(50L);
                            return createVirtualInternalRecord(i);
                        }),
                        Stream.empty(),
                        Stream.empty());
            } catch (final IOException impossible) {
                /* We don't throw this */
            }
        });
    }

    private static class InterruptRememberingThread extends Thread {

        private final AtomicInteger numInterrupts = new AtomicInteger(0);

        public InterruptRememberingThread(final Runnable target) {
            super(target);
        }

        @Override
        public void interrupt() {
            System.out.println(
                    this.getName() + " interrupted (that makes " + numInterrupts.incrementAndGet() + " times)");
            super.interrupt();
        }

        public synchronized int numInterrupts() {
            return numInterrupts.get();
        }
    }
}

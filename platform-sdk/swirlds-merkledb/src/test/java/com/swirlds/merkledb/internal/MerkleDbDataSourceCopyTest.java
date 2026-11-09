// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.internal;

import static com.swirlds.merkledb.internal.MerkleDbDataSourceTest.assertLeaf;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.DEFAULT_CONFIGURATION;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertNoDatabaseFolders;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.createHashChunkStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListSegment;
import com.swirlds.merkledb.config.DataSourceCopyMode;
import com.swirlds.merkledb.config.MerkleDbConfig_;
import com.swirlds.merkledb.internal.MerkleDbDataSource.CopiedIndexes;
import com.swirlds.merkledb.test.fixtures.TestType;
import com.swirlds.virtualmap.datasource.PreparedDataSourceCopy;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.base.io.IORunnable;
import org.hiero.base.utility.test.fixtures.file.AbstractFileManagerAwareTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * Tests for copying a MerkleDb data source in memory, see {@link MerkleDbDataSource#snapshotWithCopiedIndexes(Path)}
 * and {@link MerkleDbDataSourceBuilder#prepareCopy(String, VirtualDataSource, boolean, boolean)}.
 */
class MerkleDbDataSourceCopyTest extends AbstractFileManagerAwareTest {

    private static final String LABEL = "copyTest";

    /** The number of internal nodes and leaves. Leaf paths are from COUNT to 2 * COUNT - 1 */
    private static final int COUNT = 10_000;

    @AfterEach
    void checkNoDataSourcesLeft() {
        assertAllDatabasesClosed();
        assertNoDatabaseFolders(fileSystemManager.getTempPath());
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    @DisplayName("A copy has the same contents as the source, also after the source is closed")
    void copyHasSameContents(final TestType testType) throws IOException {
        final MerkleDbDataSource source = createPopulatedDataSource(testType, false);
        final MerkleDbDataSource copy;
        try {
            copy = copyInMemory(source, false);
            try {
                assertEquals(source.getFirstLeafPath(), copy.getFirstLeafPath());
                assertEquals(source.getLastLeafPath(), copy.getLastLeafPath());
                assertEquals(source.getInitialCapacity(), copy.getInitialCapacity());
                assertEquals(source.getHashChunkHeight(), copy.getHashChunkHeight());
                assertEquals(
                        source.getKeyToPath().getNumOfBuckets(),
                        copy.getKeyToPath().getNumOfBuckets());
                assertSameIndex(source.getIdToDiskLocationHashChunks(), copy.getIdToDiskLocationHashChunks());
                assertSameIndex(source.getPathToDiskLocationLeafNodes(), copy.getPathToDiskLocationLeafNodes());
                assertSameIndex(
                        source.getKeyToPath().getBucketIndexToBucketLocation(),
                        copy.getKeyToPath().getBucketIndexToBucketLocation());
                assertEquals(
                        source.getHashChunkStore().getFileCollection().getNumOfFiles(),
                        copy.getHashChunkStore().getFileCollection().getNumOfFiles());
                assertEquals(
                        source.getKeyValueStore().getFileCollection().getNumOfFiles(),
                        copy.getKeyValueStore().getFileCollection().getNumOfFiles());
                assertEquals(
                        source.getKeyToPath().getFileCollection().getNumOfFiles(),
                        copy.getKeyToPath().getFileCollection().getNumOfFiles());
                assertEquals(
                        source.getKeyValueStore().getFileCollection().getValidKeyRange(),
                        copy.getKeyValueStore().getFileCollection().getValidKeyRange());
                assertSameHashes(source, copy);
                checkData(testType, copy, 1);
            } catch (final RuntimeException | Error e) {
                copy.close();
                throw e;
            }
        } finally {
            source.close();
        }
        try {
            // The source's storage dir is deleted, the copy's files are hard links
            checkData(testType, copy, 1);
        } finally {
            copy.close();
        }
    }

    @Test
    @DisplayName("A copy can be updated and compacted independently of the source")
    void copyCanBeUpdatedAndCompacted() throws IOException {
        final TestType testType = TestType.variable_variable;
        final MerkleDbDataSource source = createPopulatedDataSource(testType, false);
        try {
            final MerkleDbDataSource copy = copyInMemory(source, true);
            try {
                // Update all leaves in the copy. It creates new data files, and makes the old ones garbage
                updateAllLeaves(testType, copy, 2);
                final int leafFilesBefore =
                        copy.getKeyValueStore().getFileCollection().getNumOfFiles();
                compact(copy);
                assertTrue(
                        copy.getKeyValueStore().getFileCollection().getNumOfFiles() < leafFilesBefore,
                        "The copy should have been compacted");
                checkData(testType, copy, 2);
                assertSameHashes(source, copy);
                // The source isn't affected
                checkData(testType, source, 1);
            } finally {
                copy.close();
            }
        } finally {
            source.close();
        }
    }

    @Test
    @DisplayName("A copy stays readable after its source compacts")
    void copyIsReadableAfterSourceCompacts() throws IOException {
        final TestType testType = TestType.variable_variable;
        final MerkleDbDataSource source = createPopulatedDataSource(testType, false, true);
        try {
            final MerkleDbDataSource copy = copyInMemory(source, false);
            try {
                // Update all leaves in the source, and compact it. Compaction deletes the source's old data
                // files. The copy has hard links to them, and its indexes still point to them
                updateAllLeaves(testType, source, 2);
                final int leafFilesBefore =
                        source.getKeyValueStore().getFileCollection().getNumOfFiles();
                compact(source);
                assertTrue(
                        source.getKeyValueStore().getFileCollection().getNumOfFiles() < leafFilesBefore,
                        "The source should have been compacted");
                checkData(testType, source, 2);
                // The copy isn't affected
                checkData(testType, copy, 1);
            } finally {
                copy.close();
            }
        } finally {
            source.close();
        }
    }

    @Test
    @DisplayName("Closing a prepared copy without completing it releases it")
    void uncompletedCopyIsReleased() throws IOException {
        final MerkleDbDataSource source = createPopulatedDataSource(TestType.long_fixed, false);
        try {
            final MerkleDbDataSourceBuilder builder = builder(DEFAULT_CONFIGURATION);
            final PreparedDataSourceCopy prepared = builder.prepareCopy(LABEL, source, false, false);
            final CopiedIndexes indexes =
                    assertInstanceOf(InMemoryDataSourceCopy.class, prepared).indexes();
            final List<LongList> indexList =
                    List.of(indexes.hashChunkIndex(), indexes.leafIndex(), indexes.bucketIndex());
            for (final LongList index : indexList) {
                assertTrue(index.size() > 0, "The copied index should have entries");
                assertTrue(offHeapConsumption(index) > 0, "The copied index should use off-heap memory");
            }
            prepared.close();
            assertAllDatabasesClosedExcept(1);
            for (final LongList index : indexList) {
                assertEquals(0, index.size(), "The copied index should be closed");
                assertEquals(0, offHeapConsumption(index), "The copied index memory should be released");
            }
            // The source's indexes aren't affected
            checkData(TestType.long_fixed, source, 1);
        } finally {
            source.close();
        }
        // The copy's storage dir is checked in checkNoDataSourcesLeft()
    }

    @Test
    @DisplayName("Copied indexes are released, if resuming compaction fails after they are copied")
    void copiedIndexesAreReleasedIfResumingCompactionFails() throws IOException {
        final MerkleDbDataSource source = createPopulatedDataSource(TestType.long_fixed, false);
        try {
            final MerkleDbDataSource spy = Mockito.spy(source);
            final AtomicReference<CopiedIndexes> copied = new AtomicReference<>();
            doAnswer(invocation -> {
                        final CopiedIndexes indexes = (CopiedIndexes) invocation.callRealMethod();
                        copied.set(indexes);
                        return indexes;
                    })
                    .when(spy)
                    .snapshotWithCopiedIndexes(any());
            // Runs the action, then fails as resuming compaction would, e.g. if a new compaction file
            // can't be created
            doAnswer(invocation -> {
                        invocation.<IORunnable>getArgument(0).run();
                        throw new IOException("Test: resuming compaction failed");
                    })
                    .when(spy)
                    .pauseCompactionAndRun(any());

            final MerkleDbDataSourceBuilder builder = builder(DEFAULT_CONFIGURATION);
            final UncheckedIOException e =
                    assertThrows(UncheckedIOException.class, () -> builder.prepareCopy(LABEL, spy, false, false));
            assertEquals("Test: resuming compaction failed", e.getCause().getMessage());

            final CopiedIndexes indexes = copied.get();
            assertNotNull(indexes, "The indexes should have been copied");
            for (final LongList index : List.of(indexes.hashChunkIndex(), indexes.leafIndex(), indexes.bucketIndex())) {
                assertEquals(0, index.size(), "The copied index should be closed");
                assertEquals(0, offHeapConsumption(index), "The copied index memory should be released");
            }
            // The source's indexes aren't affected
            checkData(TestType.long_fixed, source, 1);
        } finally {
            source.close();
        }
        // The copy's storage dir is checked in checkNoDataSourcesLeft()
    }

    @Test
    @DisplayName("A failed snapshot to a new temp dir deletes the dir")
    void failedSnapshotToTempDirDeletesIt() throws IOException {
        final MerkleDbDataSource source = createPopulatedDataSource(TestType.long_fixed, false);
        try {
            final MerkleDbDataSource spy = Mockito.spy(source);
            // Takes the snapshot, then fails as resuming compaction would
            doAnswer(invocation -> {
                        invocation.<IORunnable>getArgument(0).run();
                        throw new IOException("Test: resuming compaction failed");
                    })
                    .when(spy)
                    .pauseCompactionAndRun(any());
            final MerkleDbDataSourceBuilder builder = builder(DEFAULT_CONFIGURATION);
            final UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> builder.snapshot(null, spy));
            assertEquals("Test: resuming compaction failed", e.getCause().getMessage());
            checkData(TestType.long_fixed, source, 1);
        } finally {
            source.close();
        }
        // The snapshot's temp dir is checked in checkNoDataSourcesLeft()
    }

    @Test
    @DisplayName("A data source with disk-based indices can't be copied in memory")
    void diskBasedIndicesCantBeCopiedInMemory() throws IOException {
        final MerkleDbDataSource source = createPopulatedDataSource(TestType.long_fixed, true);
        try {
            final Path copyDir = fileSystemManager.resolveNewTemp(MerkleDbDataSourceBuilder.FOLDER_PREFIX + LABEL);
            assertThrows(IllegalStateException.class, () -> source.snapshotWithCopiedIndexes(copyDir));
            // The builder copies such a data source through a snapshot
            final MerkleDbDataSourceBuilder builder = builder(DEFAULT_CONFIGURATION);
            try (final PreparedDataSourceCopy prepared = builder.prepareCopy(LABEL, source, false, false)) {
                assertEquals("SnapshotDataSourceCopy", prepared.getClass().getSimpleName());
                final MerkleDbDataSource copy = assertInstanceOf(MerkleDbDataSource.class, prepared.complete());
                try {
                    checkData(TestType.long_fixed, copy, 1);
                } finally {
                    copy.close();
                }
            }
        } finally {
            source.close();
        }
    }

    @ParameterizedTest
    @MethodSource("copyModes")
    @DisplayName("The copy mode and other settings select how the builder copies a data source")
    void copyModeSelectsCopyMethod(
            final DataSourceCopyMode copyMode,
            final boolean compactionEnabled,
            final boolean offlineUse,
            final boolean indexRebuildingEnforced,
            final String expectedCopyClass,
            final boolean expectedDiskBasedIndices)
            throws IOException {
        final Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue(MerkleDbConfig_.DATA_SOURCE_COPY_MODE, copyMode.name())
                .withValue(MerkleDbConfig_.INDEX_REBUILDING_ENFORCED, Boolean.toString(indexRebuildingEnforced))
                .build();
        final MerkleDbDataSource source = createPopulatedDataSource(TestType.long_fixed, false);
        try {
            final MerkleDbDataSourceBuilder builder = builder(config);
            try (final PreparedDataSourceCopy prepared =
                    builder.prepareCopy(LABEL, source, compactionEnabled, offlineUse)) {
                assertEquals(expectedCopyClass, prepared.getClass().getSimpleName());
                final MerkleDbDataSource copy = assertInstanceOf(MerkleDbDataSource.class, prepared.complete());
                try {
                    assertThrows(IllegalStateException.class, prepared::complete, "A copy is completed only once");
                    assertEquals(expectedDiskBasedIndices, copy.isPreferDiskBasedIndices());
                    checkData(TestType.long_fixed, copy, 1);
                } finally {
                    copy.close();
                }
            }
        } finally {
            source.close();
        }
    }

    /**
     * Arguments: copy mode, compaction enabled, offline use, index rebuilding enforced, expected copy class,
     * expected disk-based indices.
     */
    static Stream<Arguments> copyModes() {
        final String inMemory = "InMemoryDataSourceCopy";
        final String snapshot = "SnapshotDataSourceCopy";
        return Stream.of(
                // The copy mode decides
                Arguments.of(DataSourceCopyMode.IN_MEMORY, false, false, false, inMemory, false),
                Arguments.of(DataSourceCopyMode.IN_MEMORY, true, false, false, inMemory, false),
                Arguments.of(DataSourceCopyMode.SNAPSHOT, false, false, false, snapshot, false),
                Arguments.of(DataSourceCopyMode.SNAPSHOT, true, false, false, snapshot, false),
                Arguments.of(DataSourceCopyMode.DISK_INDEXES, false, false, false, snapshot, true),
                Arguments.of(DataSourceCopyMode.DISK_INDEXES, true, false, false, inMemory, false),
                // Offline copies are made through a snapshot, with disk-based indices, in every mode
                Arguments.of(DataSourceCopyMode.IN_MEMORY, false, true, false, snapshot, true),
                Arguments.of(DataSourceCopyMode.IN_MEMORY, true, true, false, snapshot, true),
                Arguments.of(DataSourceCopyMode.SNAPSHOT, false, true, false, snapshot, true),
                Arguments.of(DataSourceCopyMode.SNAPSHOT, true, true, false, snapshot, true),
                Arguments.of(DataSourceCopyMode.DISK_INDEXES, false, true, false, snapshot, true),
                Arguments.of(DataSourceCopyMode.DISK_INDEXES, true, true, false, snapshot, true),
                // Enforced index rebuilding needs a snapshot to rebuild the indexes from, in every mode
                Arguments.of(DataSourceCopyMode.IN_MEMORY, false, false, true, snapshot, false),
                Arguments.of(DataSourceCopyMode.IN_MEMORY, true, false, true, snapshot, false),
                Arguments.of(DataSourceCopyMode.SNAPSHOT, false, false, true, snapshot, false),
                Arguments.of(DataSourceCopyMode.SNAPSHOT, true, false, true, snapshot, false),
                Arguments.of(DataSourceCopyMode.DISK_INDEXES, false, false, true, snapshot, true),
                Arguments.of(DataSourceCopyMode.DISK_INDEXES, true, false, true, snapshot, false));
    }

    private MerkleDbDataSourceBuilder builder(final Configuration config) {
        return new MerkleDbDataSourceBuilder(config, fileSystemManager, COUNT);
    }

    /**
     * Copies the data source in memory with the builder, and completes the copy.
     */
    private MerkleDbDataSource copyInMemory(final MerkleDbDataSource source, final boolean compactionEnabled) {
        try (final PreparedDataSourceCopy prepared =
                builder(DEFAULT_CONFIGURATION).prepareCopy(LABEL, source, compactionEnabled, false)) {
            assertInstanceOf(InMemoryDataSourceCopy.class, prepared);
            return (MerkleDbDataSource) prepared.complete();
        }
    }

    /**
     * Compacts all stores of a data source. The first run only scans the stores for garbage. The
     * second one compacts, based on the results of the scan.
     */
    private static void compact(final MerkleDbDataSource dataSource) {
        for (int i = 0; i < 2; i++) {
            dataSource.runPathToKeyValueStoreCompaction();
            dataSource.runHashChunkStoreCompaction();
            dataSource.runKeyToPathStoreCompaction();
            dataSource.awaitForCurrentCompactionsToComplete(60_000);
        }
    }

    /**
     * Updates all leaves of a data source created with {@link #createPopulatedDataSource}, so the
     * leaf value index is the leaf key index times the given multiplier. The old leaf records become
     * garbage.
     */
    private static void updateAllLeaves(
            final TestType testType, final MerkleDbDataSource dataSource, final int valueMultiplier)
            throws IOException {
        dataSource.saveRecords(
                COUNT,
                2 * COUNT - 1,
                Stream.empty(),
                IntStream.range(COUNT, 2 * COUNT)
                        .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i, i, valueMultiplier * i)),
                Stream.empty(),
                false);
    }

    private static long offHeapConsumption(final LongList index) {
        return assertInstanceOf(LongListSegment.class, index).getOffHeapConsumption();
    }

    /**
     * Creates a data source with hashes for paths 0 to 2 * COUNT - 1, and leaves for paths COUNT to
     * 2 * COUNT - 1, written in several flushes, so every store has several data files.
     */
    private MerkleDbDataSource createPopulatedDataSource(final TestType testType, final boolean diskBasedIndices)
            throws IOException {
        return createPopulatedDataSource(testType, diskBasedIndices, false);
    }

    /**
     * Same as {@link #createPopulatedDataSource(TestType, boolean)}, with background compaction enabled
     * or disabled in the data source.
     */
    private MerkleDbDataSource createPopulatedDataSource(
            final TestType testType, final boolean diskBasedIndices, final boolean compactionEnabled)
            throws IOException {
        final MerkleDbDataSource dataSource = (MerkleDbDataSource)
                builder(DEFAULT_CONFIGURATION).build(LABEL, null, compactionEnabled, diskBasedIndices);
        final int batches = 5;
        final int batchSize = COUNT / batches;
        for (int batch = 0; batch < batches; batch++) {
            final int start = batch * batchSize;
            final int end = start + batchSize;
            dataSource.saveRecords(
                    COUNT,
                    2 * COUNT - 1,
                    // Hash chunks are written for all paths in the last batch only
                    (batch == batches - 1)
                            ? createHashChunkStream(0, 2 * COUNT - 1, t -> t, dataSource.getHashChunkHeight())
                            : Stream.empty(),
                    IntStream.range(COUNT + start, COUNT + end)
                            .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)),
                    Stream.empty(),
                    false);
        }
        return dataSource;
    }

    /**
     * Checks all leaves and leaf hashes of a data source created with {@link #createPopulatedDataSource}.
     *
     * @param valueMultiplier the leaf value index is the leaf key index times this multiplier
     */
    private static void checkData(
            final TestType testType, final MerkleDbDataSource dataSource, final int valueMultiplier) {
        assertEquals(COUNT, dataSource.getFirstLeafPath());
        assertEquals(2 * COUNT - 1, dataSource.getLastLeafPath());
        for (int i = COUNT; i < 2 * COUNT; i++) {
            assertLeaf(testType, dataSource, i, i, i, valueMultiplier * i);
        }
    }

    /**
     * Checks that two data sources have the same hash chunks.
     */
    private static void assertSameHashes(final MerkleDbDataSource expected, final MerkleDbDataSource actual)
            throws IOException {
        final int hashChunkHeight = expected.getHashChunkHeight();
        final long lastChunkId = VirtualHashChunk.lastChunkIdForPaths(expected.getLastLeafPath(), hashChunkHeight);
        for (long chunkId = 0; chunkId <= lastChunkId; chunkId++) {
            final VirtualHashChunk expectedChunk = expected.loadHashChunk(chunkId);
            final VirtualHashChunk actualChunk = actual.loadHashChunk(chunkId);
            assertEquals(serialize(expectedChunk), serialize(actualChunk), "Hash chunk mismatch, id " + chunkId);
        }
    }

    private static Bytes serialize(final VirtualHashChunk chunk) {
        if (chunk == null) {
            return null;
        }
        final BufferedData buffer = BufferedData.allocate(chunk.getSerializedSizeInBytes());
        chunk.writeTo(buffer);
        return buffer.getBytes(0, buffer.length());
    }

    private static void assertSameIndex(final LongList expected, final LongList actual) {
        assertEquals(expected.size(), actual.size());
        assertEquals(expected.getMinValidIndex(), actual.getMinValidIndex());
        assertEquals(expected.getMaxValidIndex(), actual.getMaxValidIndex());
        for (long i = Math.max(0, expected.getMinValidIndex()); i <= expected.getMaxValidIndex(); i++) {
            assertEquals(expected.get(i), actual.get(i), "Index mismatch at " + i);
        }
    }

    private static void assertAllDatabasesClosedExcept(final long openCount) {
        assertEquals(openCount, MerkleDbDataSourceBuilder.getCountOfOpenDatabases());
    }
}

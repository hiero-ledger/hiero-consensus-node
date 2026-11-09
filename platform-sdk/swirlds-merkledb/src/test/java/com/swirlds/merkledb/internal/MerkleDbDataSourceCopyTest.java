// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.internal;

import static com.swirlds.merkledb.internal.MerkleDbDataSourceTest.assertLeaf;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.DEFAULT_CONFIGURATION;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertNoDatabaseFolders;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.createHashChunkStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListSegment;
import com.swirlds.merkledb.config.DataSourceCopyMode;
import com.swirlds.merkledb.config.MerkleDbConfig_;
import com.swirlds.merkledb.test.fixtures.TestType;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.base.file.FileUtils;
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

/// Tests for copying a MerkleDb data source in memory, see [MerkleDbDataSource#copyInMemory]
/// and [MerkleDbDataSourceBuilder#copy].
class MerkleDbDataSourceCopyTest extends AbstractFileManagerAwareTest {

    private static final String LABEL = "copyTest";

    /// The number of internal nodes and leaves. Leaf paths are from COUNT to 2 * COUNT - 1
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
    @DisplayName("Copied indexes are released, if the copy fails after they are copied")
    void copiedIndexesAreReleasedIfCopyFailsAfterCapture() throws IOException {
        final MerkleDbDataSource source = createPopulatedDataSource(TestType.long_fixed, false);
        try {
            final MerkleDbDataSource spy = Mockito.spy(source);
            final List<LongList> copiedIndexes = captureCopiedIndexes(spy);
            final MerkleDbDataSourceBuilder builder = builder(DEFAULT_CONFIGURATION);
            final IllegalStateException failure = new IllegalStateException("Test: failed after the capture");
            final IllegalStateException e = assertThrows(
                    IllegalStateException.class,
                    () -> builder.copy(LABEL, spy, false, false, capture -> {
                        capture.run();
                        for (final LongList index : copiedIndexes) {
                            assertTrue(index.size() > 0, "The copied index should have entries");
                            assertTrue(offHeapConsumption(index) > 0, "The copied index should use off-heap memory");
                        }
                        throw failure;
                    }));
            assertSame(failure, e);
            assertAllDatabasesClosedExcept(1);
            assertReleased(copiedIndexes);
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
            final List<LongList> copiedIndexes = captureCopiedIndexes(spy);
            // Runs the action, then fails as resuming compaction would, e.g. if a new compaction file
            // can't be created
            doAnswer(invocation -> {
                        invocation.<IORunnable>getArgument(0).run();
                        throw new IOException("Test: resuming compaction failed");
                    })
                    .when(spy)
                    .pauseCompactionAndRun(any());

            final MerkleDbDataSourceBuilder builder = builder(DEFAULT_CONFIGURATION);
            final UncheckedIOException e = assertThrows(
                    UncheckedIOException.class, () -> builder.copy(LABEL, spy, false, false, Runnable::run));
            assertEquals("Test: resuming compaction failed", e.getCause().getMessage());

            assertReleased(copiedIndexes);
            // The source's indexes aren't affected
            checkData(TestType.long_fixed, source, 1);
        } finally {
            source.close();
        }
        // The copy's storage dir is checked in checkNoDataSourcesLeft()
    }

    @Test
    @DisplayName("The copy's storage dir is selected while the source is paused")
    void storageDirIsSelectedWhilePaused() throws IOException {
        final MerkleDbDataSource source = createPopulatedDataSource(TestType.long_fixed, false);
        final String defaultFolder = "defaultFolderTakenBeforePause";
        final Path defaultDir = fileSystemManager.getTempPath().resolve(defaultFolder);
        try {
            final MerkleDbDataSourceBuilder builder =
                    new MerkleDbDataSourceBuilder(defaultFolder, DEFAULT_CONFIGURATION, fileSystemManager, COUNT);
            // The default folder doesn't exist when the copy starts, but something else takes it before the
            // pause, for example another copy
            final MerkleDbDataSource copy = (MerkleDbDataSource) builder.copy(LABEL, source, false, false, capture -> {
                FileUtils.rethrowIO(() -> Files.createDirectories(defaultDir));
                capture.run();
            });
            try {
                try (final Stream<Path> files = Files.list(defaultDir)) {
                    assertEquals(0, files.count(), "The copy must not use a folder taken before the pause");
                }
                checkData(TestType.long_fixed, copy, 1);
            } finally {
                copy.close();
            }
        } finally {
            source.close();
            FileUtils.deleteDirectory(defaultDir);
        }
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
    @DisplayName("A data source with disk-based indices is copied through a snapshot")
    void diskBasedIndicesAreCopiedThroughSnapshot() throws IOException {
        final MerkleDbDataSource source = createPopulatedDataSource(TestType.long_fixed, true);
        try {
            final MerkleDbDataSource spy = Mockito.spy(source);
            final MerkleDbDataSource copy =
                    (MerkleDbDataSource) builder(DEFAULT_CONFIGURATION).copy(LABEL, spy, false, false, Runnable::run);
            try {
                verifyCopiedInMemory(spy, false);
                checkData(TestType.long_fixed, copy, 1);
            } finally {
                copy.close();
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
            final boolean expectedInMemory,
            final boolean expectedDiskBasedIndices)
            throws IOException {
        final Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue(MerkleDbConfig_.DATA_SOURCE_COPY_MODE, copyMode.name())
                .withValue(MerkleDbConfig_.INDEX_REBUILDING_ENFORCED, Boolean.toString(indexRebuildingEnforced))
                .build();
        final MerkleDbDataSource source = createPopulatedDataSource(TestType.long_fixed, false);
        try {
            final MerkleDbDataSource spy = Mockito.spy(source);
            final MerkleDbDataSource copy =
                    (MerkleDbDataSource) builder(config).copy(LABEL, spy, compactionEnabled, offlineUse, Runnable::run);
            try {
                verifyCopiedInMemory(spy, expectedInMemory);
                assertEquals(expectedDiskBasedIndices, copy.isPreferDiskBasedIndices());
                checkData(TestType.long_fixed, copy, 1);
            } finally {
                copy.close();
            }
        } finally {
            source.close();
        }
    }

    /// Arguments: copy mode, compaction enabled, offline use, index rebuilding enforced, expected in-memory
    /// copy, expected disk-based indices.
    static Stream<Arguments> copyModes() {
        final boolean inMemory = true;
        final boolean snapshot = false;
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

    /// Copies the data source in memory with the builder.
    private MerkleDbDataSource copyInMemory(final MerkleDbDataSource source, final boolean compactionEnabled) {
        final MerkleDbDataSource spy = Mockito.spy(source);
        final MerkleDbDataSource copy = (MerkleDbDataSource)
                builder(DEFAULT_CONFIGURATION).copy(LABEL, spy, compactionEnabled, false, Runnable::run);
        verifyCopiedInMemory(spy, true);
        return copy;
    }

    private static void verifyCopiedInMemory(final MerkleDbDataSource spy, final boolean expected) {
        verify(spy, times(expected ? 1 : 0)).copyInMemory(any(), any(), any(), any(), anyBoolean(), any());
    }

    /// Collects the indexes copied by the given data source spy.
    private static List<LongList> captureCopiedIndexes(final MerkleDbDataSource spy) {
        final List<LongList> copiedIndexes = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
                    final LongList copy = (LongList) invocation.callRealMethod();
                    copiedIndexes.add(copy);
                    return copy;
                })
                .when(spy)
                .copyIndex(any());
        return copiedIndexes;
    }

    private static void assertReleased(final List<LongList> copiedIndexes) {
        assertEquals(3, copiedIndexes.size(), "All indexes should have been copied");
        for (final LongList index : copiedIndexes) {
            assertEquals(0, index.size(), "The copied index should be closed");
            assertEquals(0, offHeapConsumption(index), "The copied index memory should be released");
        }
    }

    /// Compacts all stores of a data source. The first run only scans the stores for garbage. The
    /// second one compacts, based on the results of the scan.
    private static void compact(final MerkleDbDataSource dataSource) {
        for (int i = 0; i < 2; i++) {
            dataSource.runPathToKeyValueStoreCompaction();
            dataSource.runHashChunkStoreCompaction();
            dataSource.runKeyToPathStoreCompaction();
            dataSource.awaitForCurrentCompactionsToComplete(60_000);
        }
    }

    /// Updates all leaves of a data source created with [#createPopulatedDataSource], so the
    /// leaf value index is the leaf key index times the given multiplier. The old leaf records become
    /// garbage.
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

    /// Creates a data source with hashes for paths 0 to 2 * COUNT - 1, and leaves for paths COUNT to
    /// 2 * COUNT - 1, written in several flushes, so every store has several data files.
    private MerkleDbDataSource createPopulatedDataSource(final TestType testType, final boolean diskBasedIndices)
            throws IOException {
        return createPopulatedDataSource(testType, diskBasedIndices, false);
    }

    /// Same as [#createPopulatedDataSource(TestType, boolean)], with background compaction enabled
    /// or disabled in the data source.
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

    /// Checks all leaves and leaf hashes of a data source created with [#createPopulatedDataSource].
    ///
    /// @param valueMultiplier the leaf value index is the leaf key index times this multiplier
    private static void checkData(
            final TestType testType, final MerkleDbDataSource dataSource, final int valueMultiplier) {
        assertEquals(COUNT, dataSource.getFirstLeafPath());
        assertEquals(2 * COUNT - 1, dataSource.getLastLeafPath());
        for (int i = COUNT; i < 2 * COUNT; i++) {
            assertLeaf(testType, dataSource, i, i, i, valueMultiplier * i);
        }
    }

    /// Checks that two data sources have the same hash chunks.
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

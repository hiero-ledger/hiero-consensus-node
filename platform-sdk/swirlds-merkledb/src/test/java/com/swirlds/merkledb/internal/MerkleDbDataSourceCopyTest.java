// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.internal;

import static com.swirlds.merkledb.internal.MerkleDbDataSourceTest.assertLeaf;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.DEFAULT_CONFIGURATION;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertNoDatabaseFolders;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.createHashChunkStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import com.swirlds.merkledb.config.MerkleDbConfig_;
import com.swirlds.merkledb.test.fixtures.TestType;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.base.io.IORunnable;
import org.hiero.base.utility.test.fixtures.file.AbstractFileManagerAwareTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/// Tests for copying a MerkleDb data source, see [MerkleDbDataSource#copy(Path, boolean)] and
/// [MerkleDbDataSourceBuilder#copy(VirtualDataSource, boolean)].
class MerkleDbDataSourceCopyTest extends AbstractFileManagerAwareTest {

    private static final String LABEL = "copyTest";

    /// Same as [MerkleDbTestUtils#DEFAULT_CONFIGURATION], with disk based indices
    private static final Configuration DISK_INDICES_CONFIGURATION = ConfigurationBuilder.create()
            .autoDiscoverExtensions()
            .withValue(MerkleDbConfig_.USE_DISK_INDICES, "true")
            .build();

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
        final MerkleDbDataSource source = createPopulatedDataSource(testType);
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
        final MerkleDbDataSource source = createPopulatedDataSource(testType);
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
        final MerkleDbDataSource source = createPopulatedDataSource(testType, true);
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
    @DisplayName("Copied indexes are released, if resuming compaction fails after they are copied")
    void copiedIndexesAreReleasedIfResumingCompactionFails() throws IOException {
        final MerkleDbDataSource source = createPopulatedDataSource(TestType.long_fixed);
        try {
            final MerkleDbDataSource spy = Mockito.spy(source);
            final List<LongList> copiedIndexes = new CopyOnWriteArrayList<>();
            doAnswer(invocation -> {
                        final LongList copy = (LongList) invocation.callRealMethod();
                        copiedIndexes.add(copy);
                        return copy;
                    })
                    .when(spy)
                    .copyIndex(any());
            // Runs the action, then fails as resuming compaction would, e.g. if a new compaction file
            // can't be created
            doAnswer(invocation -> {
                        invocation.<IORunnable>getArgument(0).run();
                        throw new IOException("Test: resuming compaction failed");
                    })
                    .when(spy)
                    .pauseCompactionAndRun(any());

            final MerkleDbDataSourceBuilder builder = builder(DEFAULT_CONFIGURATION);
            final UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> builder.copy(spy, false));
            assertEquals("Test: resuming compaction failed", e.getCause().getMessage());

            assertEquals(3, copiedIndexes.size(), "The indexes should have been copied");
            for (final LongList index : copiedIndexes) {
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

    @ParameterizedTest
    @EnumSource(TestType.class)
    @DisplayName("A data source with disk-based indices is copied with disk-based indices")
    void diskBasedIndicesAreCopied(final TestType testType) throws IOException {
        final MerkleDbDataSourceBuilder builder = builder(DISK_INDICES_CONFIGURATION);
        final MerkleDbDataSource source = createPopulatedDataSource(builder, testType, false);
        try {
            assertTrue(source.isPreferDiskBasedIndices());
            final MerkleDbDataSource copy = (MerkleDbDataSource) builder.copy(source, true);
            try {
                assertTrue(copy.isPreferDiskBasedIndices());
                assertTrue(copy.isCompactionEnabled());
                assertSameIndex(source.getPathToDiskLocationLeafNodes(), copy.getPathToDiskLocationLeafNodes());
                assertSameHashes(source, copy);
                checkData(testType, copy, 1);
                // The copy is independent of the source
                updateAllLeaves(testType, copy, 2);
                checkData(testType, copy, 2);
                checkData(testType, source, 1);
            } finally {
                copy.close();
            }
        } finally {
            source.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    @DisplayName("A copy has in-memory indexes and the requested compaction, also if index rebuilding is enforced")
    void copyHasInMemoryIndexesAndRequestedCompaction(
            final boolean compactionEnabled, final boolean indexRebuildingEnforced) throws IOException {
        final Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue(MerkleDbConfig_.INDEX_REBUILDING_ENFORCED, Boolean.toString(indexRebuildingEnforced))
                .build();
        final MerkleDbDataSourceBuilder builder = builder(config);
        final MerkleDbDataSource source = createPopulatedDataSource(builder, TestType.long_fixed, false);
        try {
            // A rebuilt index has the same contents as a copied one, so the copies are tracked
            final MerkleDbDataSource spy = Mockito.spy(source);
            final List<LongList> copiedIndexes = new CopyOnWriteArrayList<>();
            doAnswer(invocation -> {
                        final LongList copy = (LongList) invocation.callRealMethod();
                        copiedIndexes.add(copy);
                        return copy;
                    })
                    .when(spy)
                    .copyIndex(any());
            final MerkleDbDataSource copy = (MerkleDbDataSource) builder.copy(spy, compactionEnabled);
            try {
                assertFalse(copy.isPreferDiskBasedIndices());
                assertEquals(compactionEnabled, copy.isCompactionEnabled());
                // The copied indexes are used, they aren't rebuilt
                assertEquals(3, copiedIndexes.size(), "All three indexes should be copied");
                for (final LongList index : List.of(
                        copy.getIdToDiskLocationHashChunks(),
                        copy.getPathToDiskLocationLeafNodes(),
                        copy.getKeyToPath().getBucketIndexToBucketLocation())) {
                    assertTrue(
                            copiedIndexes.stream().anyMatch(copied -> copied == index),
                            "The copy should use the copied index " + index);
                }
                assertSameIndex(source.getPathToDiskLocationLeafNodes(), copy.getPathToDiskLocationLeafNodes());
                checkData(TestType.long_fixed, copy, 1);
            } finally {
                copy.close();
            }
        } finally {
            source.close();
        }
    }

    private MerkleDbDataSourceBuilder builder(final Configuration config) {
        return new MerkleDbDataSourceBuilder(config, fileSystemManager, COUNT);
    }

    /// Copies the data source with the builder.
    private MerkleDbDataSource copyInMemory(final MerkleDbDataSource source, final boolean compactionEnabled) {
        return (MerkleDbDataSource) builder(DEFAULT_CONFIGURATION).copy(source, compactionEnabled);
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

    /// Creates a data source with in-memory indices, hashes for paths 0 to 2 * COUNT - 1, and leaves for
    /// paths COUNT to 2 * COUNT - 1, written in several flushes, so every store has several data files.
    private MerkleDbDataSource createPopulatedDataSource(final TestType testType) throws IOException {
        return createPopulatedDataSource(testType, false);
    }

    /// Same as [#createPopulatedDataSource(TestType)], with background compaction enabled or disabled
    /// in the data source.
    private MerkleDbDataSource createPopulatedDataSource(final TestType testType, final boolean compactionEnabled)
            throws IOException {
        return createPopulatedDataSource(builder(DEFAULT_CONFIGURATION), testType, compactionEnabled);
    }

    /// Same as [#createPopulatedDataSource(TestType, boolean)], with the given builder.
    private MerkleDbDataSource createPopulatedDataSource(
            final MerkleDbDataSourceBuilder builder, final TestType testType, final boolean compactionEnabled)
            throws IOException {
        final MerkleDbDataSource dataSource = (MerkleDbDataSource) builder.build(LABEL, null, compactionEnabled);
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
}

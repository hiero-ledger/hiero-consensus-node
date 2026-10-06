// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.internal;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.DEFAULT_CONFIGURATION;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.DEFAULT_TABLE_NAME;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyFalse;

import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.hiero.base.file.FileSystemManager;

/**
 * Helpers for tests that need a concrete {@link MerkleDbDataSource} rather than a
 * {@link com.swirlds.virtualmap.datasource.VirtualDataSource}.
 *
 * <p>These live in {@code src/test} rather than in {@code src/testFixtures} on purpose:
 * {@code MerkleDbDataSource} is internal to the {@code com.swirlds.merkledb} module, so the
 * fixtures module (which is a separate JPMS module) must not expose it. The {@code test} source
 * set, by contrast, is patched into {@code com.swirlds.merkledb} and can see the internal package.
 *
 * <p>Everything here goes through {@link MerkleDbDataSourceBuilder}, which is the supported way
 * to create, snapshot and restore a MerkleDb data source.
 */
public final class MerkleDbDataSourceTestUtils {

    private MerkleDbDataSourceTestUtils() {}

    private static MerkleDbDataSourceBuilder builder(
            final Configuration configuration, final FileSystemManager fileSystemManager, final long initialCapacity) {
        return new MerkleDbDataSourceBuilder(configuration, fileSystemManager, initialCapacity);
    }

    public static MerkleDbDataSource createDataSource(
            final FileSystemManager fileSystemManager,
            final long size,
            final boolean compactionEnabled,
            final boolean preferDiskBasedIndexes) {
        return createDataSource(
                DEFAULT_CONFIGURATION,
                fileSystemManager,
                DEFAULT_TABLE_NAME,
                size,
                compactionEnabled,
                preferDiskBasedIndexes);
    }

    public static MerkleDbDataSource createDataSource(
            final Configuration configuration,
            final FileSystemManager fileSystemManager,
            final String name,
            final long size,
            final boolean compactionEnabled,
            final boolean preferDiskBasedIndexes) {
        return (MerkleDbDataSource) builder(configuration, fileSystemManager, size)
                .build(name, null, compactionEnabled, preferDiskBasedIndexes);
    }

    /**
     * Takes a snapshot of the given data source into {@code snapshotDir}, using the same layout
     * that {@link #restoreDataSource} expects. The caller owns {@code snapshotDir} and is
     * responsible for deleting it.
     *
     * @param snapshotDir the target directory, must not be null
     * @return the snapshot directory
     * @throws NullPointerException if {@code snapshotDir} is null
     */
    public static Path takeSnapshot(
            final FileSystemManager fileSystemManager, final MerkleDbDataSource dataSource, final Path snapshotDir) {
        // Not null: the builder would otherwise allocate a FOLDER_PREFIX-named dir that it owns,
        // which contradicts the "caller owns snapshotDir" contract above and looks like a leaked
        // data source to MerkleDbTestUtils.assertNoDatabaseFolders().
        requireNonNull(snapshotDir, "snapshotDir must not be null");
        return builder(DEFAULT_CONFIGURATION, fileSystemManager, 0).snapshot(snapshotDir, dataSource);
    }

    /**
     * Restores a data source from a snapshot taken by
     * {@link #takeSnapshot(FileSystemManager, MerkleDbDataSource, Path)}. The snapshot is
     * hard-linked into a fresh temp directory, so the snapshot itself is left untouched and
     * survives {@link MerkleDbDataSource#close()}.
     */
    public static MerkleDbDataSource restoreDataSource(
            final Configuration configuration,
            final FileSystemManager fileSystemManager,
            final Path snapshotDir,
            final String name,
            final boolean compactionEnabled) {
        // Initial capacity 0: on the restore path it is read back from the snapshot metadata
        return (MerkleDbDataSource)
                builder(configuration, fileSystemManager, 0).build(name, snapshotDir, compactionEnabled, false);
    }

    /**
     * Asserts that the data source deleted its storage directory when it was closed.
     *
     * <p>Only meaningful for a data source closed with {@code keepData == false}, which is what
     * {@link com.swirlds.virtualmap.datasource.VirtualDataSource#close()} does.
     */
    public static void assertDatabaseFolderDeleted(final MerkleDbDataSource dataSource) {
        // storageDir is the data source root itself: all MerkleDbPaths entries resolve directly
        // under it, so this is the directory close() is expected to have removed
        final Path storageDir = dataSource.getDbPaths().storageDir;
        assertEventuallyFalse(
                () -> Files.exists(storageDir),
                Duration.ofSeconds(1),
                "Database folder [" + storageDir + "] should have been deleted");
    }
}

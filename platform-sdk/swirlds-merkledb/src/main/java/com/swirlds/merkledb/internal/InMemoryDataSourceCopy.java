// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.internal;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static java.util.Objects.requireNonNull;

import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.internal.MerkleDbDataSource.CopiedIndexes;
import com.swirlds.virtualmap.datasource.PreparedDataSourceCopy;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.file.FileSystemManager;
import org.hiero.base.file.FileUtils;

/// A data source copy prepared with [MerkleDbDataSource#snapshotWithCopiedIndexes(Path)]: a
/// snapshot without index files in the copy's storage dir, and the indexes copied in memory.
public final class InMemoryDataSourceCopy implements PreparedDataSourceCopy {

    private static final Logger logger = LogManager.getLogger(InMemoryDataSourceCopy.class);

    private final String label;
    private final Path dir;
    private final CopiedIndexes indexes;
    private final MerkleDbConfig config;
    private final FileSystemManager fileSystemManager;
    private final boolean compactionEnabled;
    private boolean completed = false;

    /// Creates a prepared copy. It takes over the snapshot dir and the copied indexes.
    ///
    /// @param label the label of the copy
    /// @param dir the snapshot dir, it becomes the storage dir of the copy
    /// @param indexes the indexes copied when the snapshot was made
    /// @param config merkle db configuration
    /// @param fileSystemManager the file system manager
    /// @param compactionEnabled whether background compaction runs for the copy
    public InMemoryDataSourceCopy(
            final String label,
            @NonNull final Path dir,
            @NonNull final CopiedIndexes indexes,
            @NonNull final MerkleDbConfig config,
            @NonNull final FileSystemManager fileSystemManager,
            final boolean compactionEnabled) {
        this.label = label;
        this.dir = requireNonNull(dir);
        this.indexes = requireNonNull(indexes);
        this.config = requireNonNull(config);
        this.fileSystemManager = requireNonNull(fileSystemManager);
        this.compactionEnabled = compactionEnabled;
    }

    /// {@inheritDoc}
    ///
    /// Creates a data source from the snapshot and the copied indexes.
    @NonNull
    @Override
    public VirtualDataSource complete() {
        if (completed) {
            throw new IllegalStateException("The copy is already completed");
        }
        try {
            final MerkleDbDataSource dataSource =
                    new MerkleDbDataSource(dir, config, fileSystemManager, label, compactionEnabled, indexes);
            completed = true;
            return dataSource;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// {@inheritDoc}
    ///
    /// If the copy hasn't been completed, releases the copied indexes and deletes the snapshot.
    /// Otherwise, does nothing: the indexes and the storage dir belong to the completed copy. Failures
    /// to delete the snapshot are logged. The snapshot is in the temp directory, which is cleaned up on
    /// node restart.
    @Override
    public void close() {
        if (!completed) {
            indexes.close();
            try {
                FileUtils.deleteDirectory(dir);
            } catch (final IOException e) {
                logger.error(EXCEPTION.getMarker(), "Unable to delete data source copy directory {}", dir, e);
            }
        }
    }

    /// The copied indexes, for testing.
    CopiedIndexes indexes() {
        return indexes;
    }
}

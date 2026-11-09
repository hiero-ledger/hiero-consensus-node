// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.file.FileUtils;

/// A data source copy prepared as a snapshot of the source data source. The copy is restored from the
/// snapshot, when completed. The snapshot directory is deleted, when this prepared copy is closed.
final class SnapshotDataSourceCopy implements PreparedDataSourceCopy {

    private static final Logger logger = LogManager.getLogger(SnapshotDataSourceCopy.class);

    private final VirtualDataSourceBuilder builder;
    private final String label;
    private final Path snapshotPath;
    private final boolean compactionEnabled;
    private final boolean offlineUse;
    private boolean completed = false;

    SnapshotDataSourceCopy(
            @NonNull final VirtualDataSourceBuilder builder,
            final String label,
            @NonNull final Path snapshotPath,
            final boolean compactionEnabled,
            final boolean offlineUse) {
        this.builder = requireNonNull(builder);
        this.label = label;
        this.snapshotPath = requireNonNull(snapshotPath);
        this.compactionEnabled = compactionEnabled;
        this.offlineUse = offlineUse;
    }

    /// {@inheritDoc}
    ///
    /// Restores a data source from the snapshot. It uses its own directory to store data files.
    @NonNull
    @Override
    public VirtualDataSource complete() {
        if (completed) {
            throw new IllegalStateException("The copy is already completed");
        }
        final VirtualDataSource dataSource = builder.build(label, snapshotPath, compactionEnabled, offlineUse);
        completed = true;
        return dataSource;
    }

    /// {@inheritDoc}
    ///
    /// Deletes the snapshot directory. Failures are logged, but not thrown. The snapshot directory
    /// is in the temp directory, which is cleaned up on node restart.
    @Override
    public void close() {
        try {
            FileUtils.deleteDirectory(snapshotPath);
        } catch (final IOException e) {
            logger.error(EXCEPTION.getMarker(), "Unable to delete snapshot directory {}", snapshotPath, e);
        }
    }
}

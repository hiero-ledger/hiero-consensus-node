// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static java.util.Objects.requireNonNull;
import static org.hiero.base.file.FileUtils.hardLinkTree;

import org.hiero.base.file.FileSystemManager;
import com.swirlds.config.api.Configuration;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Virtual data source builder that manages MerkleDb data sources.
 *
 * <p>When a MerkleDb data source builder creates a new data source, or restores a data source
 * from snapshot, it creates a new temp folder using {@link FileSystemManager} as the data
 * source storage dir.
 *
 * <p>When a data source snapshot is taken, or a data source is restored from a snapshot, the
 * builder uses certain sub-folder under snapshot dir as described in {@link #snapshot(Path, VirtualDataSource)}
 * and {@link VirtualDataSourceBuilder#build(String, Path, boolean, boolean)} methods.
 */
public class MerkleDbDataSourceBuilder implements VirtualDataSourceBuilder {

    /** Platform configuration */
    private final Configuration configuration;

    private final FileSystemManager fileSystemManager;

    private long initialCapacity = 0;

    /**
     * Constructor for deserialization purposes.
     * @param configuration configuration to use
     * @param fileSystemManager file system manager to use
     */
    public MerkleDbDataSourceBuilder(
            @NonNull final Configuration configuration, @NonNull final FileSystemManager fileSystemManager) {
        this.configuration = requireNonNull(configuration);
        this.fileSystemManager = requireNonNull(fileSystemManager);
    }

    /**
     * Creates a new data source builder with the specified table configuration.
     *
     * @param initialCapacity initial capacity of the map
     * @param configuration platform configuration
     */
    public MerkleDbDataSourceBuilder(
            @NonNull final Configuration configuration,
            @NonNull final FileSystemManager fileSystemManager,
            final long initialCapacity) {
        this.configuration = requireNonNull(configuration);
        this.fileSystemManager = requireNonNull(fileSystemManager);
        this.initialCapacity = initialCapacity;
    }

    private Path newDataSourceDir(final String label) {
        return fileSystemManager.resolveNewTemp("merkledb-" + label);
    }

    private Path snapshotDataDir(final Path snapshotDir, final String label) {
        return snapshotDir.resolve("data").resolve(label);
    }

    /**
     * {@inheritDoc}
     *
     * <p>If the source directory is provided, this builder assumes the directory is a base
     * snapshot dir. Data source dir is either baseDir/data/label (new naming schema) or
     * baseDir/tables/label-ID (legacy naming).
     *
     * <p>If the source directory is null, a new empty data source is created in a temp
     * directory.
     */
    @NonNull
    @Override
    public VirtualDataSource build(
            final String label,
            @Nullable final Path sourceDir,
            final boolean compactionEnabled,
            final boolean offlineUse) {
        if (sourceDir == null) {
            return buildNewDataSource(label, compactionEnabled, offlineUse);
        } else {
            return restoreDataSource(label, sourceDir, compactionEnabled, offlineUse);
        }
    }

    @NonNull
    private VirtualDataSource buildNewDataSource(
            final String label, final boolean compactionEnabled, final boolean offlineUse) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial map capacity not set");
        }
        try {
            final Path dataSourceDir = newDataSourceDir(label);
            return new MerkleDbDataSource(
                    dataSourceDir,
                    configuration,
                    fileSystemManager,
                    label,
                    initialCapacity,
                    compactionEnabled,
                    offlineUse);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void snapshotDataSource(final MerkleDbDataSource dataSource, final Path dir) {
        try {
            dataSource.pauseCompactionAndRun(() -> dataSource.snapshot(dir));
        } catch (final IOException z) {
            throw new UncheckedIOException(z);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Data source snapshot is placed under "data/label" sub-folder in the provided
     * {@code snapshotDir}.
     */
    @NonNull
    @Override
    public Path snapshot(@Nullable Path snapshotDir, @NonNull final VirtualDataSource dataSource) {
        if (!(dataSource instanceof MerkleDbDataSource merkleDbDataSource)) {
            throw new IllegalArgumentException("The data source must be compatible with the MerkleDb");
        }
        final String label = merkleDbDataSource.getTableName();
        if (snapshotDir == null) {
            snapshotDir = newDataSourceDir(label);
        }
        final Path snapshotDataSourceDir = snapshotDataDir(snapshotDir, label);
        snapshotDataSource(merkleDbDataSource, snapshotDataSourceDir);
        return snapshotDir;
    }

    /**
     * The builder first checks if "data/label" sub-folder exists in the snapshot dir and
     * restores a data source from there. If the sub-folder doesn't exist, it may be an old
     * snapshot with MerkleDb database metadata available. The metadata is used to find the
     * folder for a data source with the given label. If database metadata file is not found,
     * this method throws an IO exception.
     */
    @NonNull
    private VirtualDataSource restoreDataSource(
            final String label,
            @NonNull final Path snapshotDir,
            final boolean compactionEnabled,
            final boolean offlineUse) {
        try {
            final Path dataSourceDir = newDataSourceDir(label);
            final Path snapshotDataSourceDir = snapshotDataDir(snapshotDir, label);
            if (Files.isDirectory(snapshotDataSourceDir)) {
                hardLinkTree(snapshotDataSourceDir, dataSourceDir);
                return new MerkleDbDataSource(
                        dataSourceDir, configuration, fileSystemManager, label, compactionEnabled, offlineUse);
            }
            throw new IOException(
                    "Cannot restore MerkleDb data source: label=" + label + " snapshotDir=" + snapshotDir);
        } catch (final IOException z) {
            throw new UncheckedIOException(z);
        }
    }
}

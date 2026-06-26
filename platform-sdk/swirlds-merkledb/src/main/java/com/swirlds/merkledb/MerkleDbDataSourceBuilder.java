// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static java.util.Objects.requireNonNull;
import static org.hiero.base.file.FileUtils.hardLinkTree;

import com.swirlds.config.api.Configuration;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hiero.base.file.FileSystemManager;

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

    public static final String FOLDER_SUFFIX = "merkledb-";

    /** Platform configuration */
    private final Configuration configuration;

    /**
     * A folder name for the first MerkleDb instance managed by this builder. It's used
     * when a new data source is created from scratch or a data source is restored from a
     * snapshot.
     *
     * <p>Also, this folder name (if not null or blank) is checked first, when a new data
     * source is requested. If a folder with this nams exists in the file system manager's
     * temp directory, this is considered a version upgrade, so the data source is created
     * directly from that folder rather than from scratch.
     */
    private final String defaultDbFolderName;

    private final FileSystemManager fileSystemManager;

    private final long initialCapacity;

    /**
     * Creates a new data source builder with the specified configuration, file system manager,
     * and initial MerkleDb database capacity.
     */
    public MerkleDbDataSourceBuilder(
            @NonNull final Configuration configuration,
            @NonNull final FileSystemManager fileSystemManager,
            final long initialCapacity) {
        this(null, configuration, fileSystemManager, initialCapacity);
    }

    /**
     * Creates a new data source builder with the specified default folder name (may be null or
     * blank), configuration, file system manager, and initial MerkleDb database capacity.
     */
    public MerkleDbDataSourceBuilder(
            @Nullable String defaultDbFolderName,
            @NonNull final Configuration configuration,
            @NonNull final FileSystemManager fileSystemManager,
            final long initialCapacity) {
        this.defaultDbFolderName =
                (defaultDbFolderName == null) || defaultDbFolderName.isBlank() ? null : defaultDbFolderName;
        this.configuration = requireNonNull(configuration);
        this.fileSystemManager = requireNonNull(fileSystemManager);
        this.initialCapacity = initialCapacity;
    }

    private Path newTempDataSourceDir(final String label) {
        return fileSystemManager.resolveNewTemp(FOLDER_SUFFIX + label);
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
    private MerkleDbDataSource buildNewDataSource(
            final String label, final boolean compactionEnabled, final boolean offlineUse) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial map capacity not set");
        }
        try {
            Path dataSourceDir = null;
            if (defaultDbFolderName != null) {
                // The folder may or may not exist
                dataSourceDir = fileSystemManager.getTempPath().resolve(defaultDbFolderName);
            }
            // If the default DB dir is not set, or the folder doesn't exist, create a new
            // temp folder and use it as the storage dir
            if (dataSourceDir == null) {
                dataSourceDir = newTempDataSourceDir(label);
            }
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
            snapshotDir = newTempDataSourceDir(label);
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
    private MerkleDbDataSource restoreDataSource(
            final String label,
            @NonNull final Path snapshotDir,
            final boolean compactionEnabled,
            final boolean offlineUse) {
        try {
            Path dataSourceDir = null;
            if (defaultDbFolderName != null) {
                final Path defaultDir = fileSystemManager.getTempPath().resolve(defaultDbFolderName);
                if (!Files.exists(defaultDir)) {
                    dataSourceDir = defaultDir;
                }
            }
            if (dataSourceDir == null) {
                dataSourceDir = newTempDataSourceDir(label);
            }
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

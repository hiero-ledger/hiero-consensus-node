// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.file.FileUtils.hardLinkTree;

import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.internal.MerkleDbDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.file.FileSystemManager;

/**
 * Virtual data source builder that manages MerkleDb data sources.
 *
 * <p>When a MerkleDb data source builder creates a new data source, or restores a data source
 * from snapshot, it creates a new temp folder using {@link FileSystemManager} as the data
 * source storage dir. The exception is when a default DB folder name is configured: see
 * {@link #MerkleDbDataSourceBuilder(String, Configuration, FileSystemManager, long)}.
 *
 * <p>When a data source snapshot is taken, or a data source is restored from a snapshot, the
 * builder uses certain sub-folder under snapshot dir as described in {@link #snapshot(Path, VirtualDataSource)}
 * and {@link VirtualDataSourceBuilder#build(String, Path, boolean, boolean)} methods.
 */
public class MerkleDbDataSourceBuilder implements VirtualDataSourceBuilder {

    private static final Logger logger = LogManager.getLogger(MerkleDbDataSourceBuilder.class);

    /** Prefix of every temp data source storage dir created by this builder. */
    public static final String FOLDER_PREFIX = "merkledb-";

    private final MerkleDbConfig configuration;

    /**
     * A folder name for the first MerkleDb instance managed by this builder. It's used
     * when a new data source is created from scratch or a data source is restored from a
     * snapshot.
     *
     * <p>Also, this folder name (if not null or blank) is checked first, when a new data
     * source is requested. If a folder with this name exists in the file system manager's
     * temp directory, this is considered a version upgrade, so the data source is created
     * directly from that folder rather than from scratch.
     *
     * <p>On the restore path the opposite applies: this folder is used as the storage dir only
     * if it does <i>not</i> already exist, otherwise a new temp folder is created.
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
        this.configuration = requireNonNull(configuration).getConfigData(MerkleDbConfig.class);
        this.fileSystemManager = requireNonNull(fileSystemManager);
        this.initialCapacity = initialCapacity;
    }

    /**
     * Returns the number of MerkleDb data sources currently open in this JVM, across all builders.
     *
     * <p>Intended for leak detection in tests and benchmarks: after all virtual maps have been
     * released and their data sources closed, this count is expected to drop back to zero.
     *
     * @return the number of currently open MerkleDb data sources
     */
    public static long getCountOfOpenDatabases() {
        return MerkleDbDataSource.getCountOfOpenDatabases();
    }

    private Path newTempDataSourceDir(final String label) {
        return fileSystemManager.resolveNewTemp(FOLDER_PREFIX + label);
    }

    private Path snapshotDataDir(final Path snapshotDir, final String label) {
        return snapshotDir.resolve("data").resolve(label);
    }

    /**
     * {@inheritDoc}
     *
     * <p>If the source directory is provided, this builder assumes the directory is a base
     * snapshot dir, as produced by {@link #snapshot(Path, VirtualDataSource)}, and it must
     * contain a {@code data/label} sub-folder. That sub-folder is hard-linked into a new temp
     * folder, which becomes the storage dir of the returned data source, so the snapshot itself
     * is left untouched and survives {@link VirtualDataSource#close()}. If the sub-folder is
     * missing, an {@link UncheckedIOException} is thrown.
     *
     * <p>If the source directory is null, a new empty data source is created in a temp
     * directory. In that case {@code initialCapacity} must be positive.
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
        final long start = System.currentTimeMillis();
        try {
            Path dataSourceDir = null;
            if (defaultDbFolderName != null) {
                // The folder may or may not exist
                dataSourceDir = fileSystemManager.getTempPath().resolve(defaultDbFolderName);
            }
            // If the default DB dir is not set, create a new temp folder and use it as the
            // storage dir
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
        } finally {
            logger.info(
                    STARTUP.getMarker(),
                    "++++++++ New MerkleDbDataSource is created, took {} ms",
                    System.currentTimeMillis() - start);
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
     * {@code snapshotDir}. If {@code snapshotDir} is null, a new temp folder is created and
     * returned. The resulting layout is what {@link #build(String, Path, boolean, boolean)}
     * expects as its source dir.
     *
     * <p><b>The caller owns the returned directory and is responsible for deleting it when it is
     * no longer needed.</b> Closing the data source does not remove it: a snapshot is
     * independent of the data source it was taken from.
     */
    @NonNull
    @Override
    public Path snapshot(@Nullable Path snapshotDir, @NonNull final VirtualDataSource dataSource) {
        if (!(dataSource instanceof MerkleDbDataSource merkleDbDataSource)) {
            throw new IllegalArgumentException("The data source must be compatible with the MerkleDb");
        }
        final long start = System.currentTimeMillis();
        final String label = merkleDbDataSource.getTableName();
        if (snapshotDir == null) {
            snapshotDir = newTempDataSourceDir(label);
        }
        final Path snapshotDataSourceDir = snapshotDataDir(snapshotDir, label);
        snapshotDataSource(merkleDbDataSource, snapshotDataSourceDir);
        logger.info(
                STARTUP.getMarker(), "++++++++ Snapshot data source, took {} ms", System.currentTimeMillis() - start);
        return snapshotDir;
    }

    /**
     * Restores a data source from the "data/label" sub-folder of the given snapshot dir. The
     * sub-folder is hard-linked into a new temp folder, which becomes the storage dir of the
     * returned data source; the snapshot dir itself is not modified and remains owned by the
     * caller.
     *
     * <p>Initial capacity is not used here: it is read back from the snapshot metadata, so a
     * builder created with capacity 0 can still restore.
     *
     * <p>If the "data/label" sub-folder does not exist, this method throws an
     * {@link UncheckedIOException}.
     */
    @NonNull
    private MerkleDbDataSource restoreDataSource(
            final String label,
            @NonNull final Path snapshotDir,
            final boolean compactionEnabled,
            final boolean offlineUse) {
        final long start = System.currentTimeMillis();
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
        } finally {
            logger.info(
                    STARTUP.getMarker(),
                    "++++++++ MerkleDbDataSource is restored, took {} ms",
                    System.currentTimeMillis() - start);
        }
    }
}

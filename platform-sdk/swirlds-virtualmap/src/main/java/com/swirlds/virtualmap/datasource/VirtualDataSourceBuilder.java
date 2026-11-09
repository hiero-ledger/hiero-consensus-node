// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.hiero.base.file.FileUtils;

/**
 * Manages {@link VirtualDataSource} instances. An instance of a data source builder is provided
 * to every {@link com.swirlds.virtualmap.VirtualMap} and used to get a reference to underlying
 * virtual data source.
 *
 * <p>Virtual data source builder configuration is not a part of this interface. For example, some
 * implementations that store data on disk may have "storage directory" config, which is used,
 * together with requested data source labels, to build full data source disk paths.
 */
public interface VirtualDataSourceBuilder {

    /**
     * Builds a new {@link VirtualDataSource} using the configuration of this builder and
     * the given label. If a source directory is provided, data source files are loaded from
     * it. This must be a directory previously used in the {@link #snapshot(Path, VirtualDataSource)}
     * method. If the directory is not provided, an empty data source is created. Regardless of
     * the source directory, the new data source will use its own directory to store data files.
     *
     * @param sourceDir         The directory of the snapshot the data source should be created from. Can be null, in this case it creates an empty data source
     * @param compactionEnabled Indicates whether background compaction should be enabled in the data source copy
     * @param offlineUse        Indicates that the copied data source should use as little resources as possible. Data
     *                          source copies created for offline use should not be used for performance critical tasks
     * @return An opened {@link VirtualDataSource}
     */
    @NonNull
    VirtualDataSource build(String label, @Nullable Path sourceDir, boolean compactionEnabled, boolean offlineUse);

    /**
     * Creates a snapshot of the given data source in the specified folder.
     *
     * <p>If the destination folder is not null, the snapshot is created in this folder, and
     * this folder is returned. If the destination folder is null, the builder creates a new
     * temp folder, takes the snapshot there, and returns the path to that folder.
     *
     * @param destination
     * 		The base path into which to snapshot the database. Can be null
     * @param dataSource
     * 		The dataSource to invoke snapshot on. Cannot be null
     * @return
     *      The base path where the snapshot is taken
     */
    @NonNull
    Path snapshot(@Nullable Path destination, @NonNull VirtualDataSource dataSource);

    /// Copies the given data source. The copy is made in two steps, so the source is held unmodified
    /// only for the first one. First, the state of the source is captured by an action passed to
    /// `whilePaused`. It must run the action once, synchronously, while the source isn't modified, like
    /// [#snapshot(Path, VirtualDataSource)] requires. Then, once `whilePaused` has returned, the copy is
    /// created from the captured state, and the source may be modified again.
    ///
    /// The default implementation takes a snapshot of the data source, restores the copy from it with
    /// [#build(String, Path, boolean, boolean)], and deletes the snapshot.
    ///
    /// @param label             The label of the copy
    /// @param dataSource        The data source to copy
    /// @param compactionEnabled Indicates whether background compaction should be enabled in the copy
    /// @param offlineUse        Indicates that the copy should use as little resources as possible, see
    ///                          [#build(String, Path, boolean, boolean)]
    /// @param whilePaused       Runs the given action while the data source isn't modified
    /// @return the data source copy, the caller is responsible for closing it
    @NonNull
    default VirtualDataSource copy(
            final String label,
            @NonNull final VirtualDataSource dataSource,
            final boolean compactionEnabled,
            final boolean offlineUse,
            @NonNull final Consumer<Runnable> whilePaused) {
        final AtomicReference<Path> snapshotPath = new AtomicReference<>();
        try {
            whilePaused.accept(() -> snapshotPath.set(snapshot(null, dataSource)));
            return build(label, snapshotPath.get(), compactionEnabled, offlineUse);
        } finally {
            if (snapshotPath.get() != null) {
                deleteSnapshot(snapshotPath.get());
            }
        }
    }

    /// Deletes a temporary snapshot. Failures are logged, but not thrown. The snapshot is in the temp
    /// directory, which is cleaned up on node restart.
    private static void deleteSnapshot(@NonNull final Path snapshotPath) {
        try {
            FileUtils.deleteDirectory(snapshotPath);
        } catch (final IOException | UncheckedIOException e) {
            LogManager.getLogger(VirtualDataSourceBuilder.class)
                    .error(EXCEPTION.getMarker(), "Unable to delete snapshot directory {}", snapshotPath, e);
        }
    }
}

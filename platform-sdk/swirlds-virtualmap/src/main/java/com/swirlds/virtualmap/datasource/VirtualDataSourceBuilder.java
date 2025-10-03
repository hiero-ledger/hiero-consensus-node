// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import org.hiero.base.io.SelfSerializable;

/**
 * Manages {@link VirtualDataSource} instances. An instance of a data source builder is provided
 * to every {@link com.swirlds.virtualmap.VirtualMap} and used to get a reference to underlying
 * virtual data source.
 *
 * <p>Virtual data source builder configuration is not a part of this interface. For example, some
 * implementations that store data on disk may have "storage directory" config, which is used,
 * together with requested data source labels, to build full data source disk paths.
 */
public interface VirtualDataSourceBuilder extends SelfSerializable {

    /**
     * Builds a new {@link VirtualDataSource} using the configuration of this builder and
     * the given label. If a source directory is provided, data source files are loaded from
     * it. This must be a directory previously used in the {@link #snapshot(Path, VirtualDataSource)}
     * method. If the directory is not provided, a new temp directory is created, and an empty
     * data source is opened in it.
     *
     * @param label
     * 		The label. Cannot be null. Labels can be used in logs and stats, and also to build
     * 		full disk paths to store data source files. This is builder implementation specific
     * @param compactionEnabled
     *      Indicates whether background compaction should be enabled in the data source copy
     * @param offlineUse
     *      Indicates that the copied data source should use as little resources as possible. Data
     *      source copies created for offline use should not be used for performance critical tasks
     * @return
     * 		An opened {@link VirtualDataSource}.
     */
    @NonNull
    VirtualDataSource build(
            String label, @Nullable Path sourceDir, final boolean compactionEnabled, boolean offlineUse);

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
}

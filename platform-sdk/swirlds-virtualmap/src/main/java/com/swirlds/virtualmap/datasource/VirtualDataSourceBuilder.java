// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import edu.umd.cs.findbugs.annotations.NonNull;
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
     * the given label. If a data source with the given label already exists, it's used instead.
     *
     * @param label
     * 		The label. Cannot be null. Labels can be used in logs and stats, and also to build
     * 		full disk paths to store data source files. This is builder implementation specific
     * @param withDbCompactionEnabled
     * 		If true then the new database will have background compaction enabled, false and the
     * 		new database will not have background compaction enabled
     * @return
     * 		An opened {@link VirtualDataSource}.
     */
    @NonNull
    VirtualDataSource build(String label, final boolean withDbCompactionEnabled);

    /**
     * Builds a new {@link VirtualDataSource} using the configuration of this builder by creating
     * a snapshot of the given data source. The new data source doesn't have background file
     * compaction enabled.
     *
     * <p>This method is used when a virtual map copy is created during reconnects. When a copy is
     * created on the teacher side, the original data source is preserved as active, i.e. used
     * to handle transactions. When this method is used on the learner side, it behaves in the
     * opposite way: the copied data source becomes active, while the original data source isn't
     * used any longer other than to re-initiate reconnect when failed.
     *
     * @param dataSource
     * 		The dataSource to invoke snapshot on. Cannot be null
     * @param compactionEnabled
     *      Indicates whether background compaction should be enabled in the data source copy
     * @param offlineUse
     *      Indicates that the copied data source should use as little resources as possible. Data
     *      source copies created for offline use should not be used for performance critical tasks
     * @return
     * 		An opened {@link VirtualDataSource}
     */
    @NonNull
    VirtualDataSource copy(VirtualDataSource dataSource, boolean compactionEnabled, boolean offlineUse);

    /**
     * Builds a new {@link VirtualDataSource} using the configuration of this builder by creating
     * a snapshot of the given data source in the specified folder. The new data source doesn't
     * have background file compaction enabled. Such snapshots should not be used for any time
     * critical operations, since snapshot data sources are expected to consume as little resources
     * as possible (e.g. use on-disk rather than in-memory indices) and therefore may be slow.
     *
     * <p>This method is used when a virtual map is written to disk during state serialization.
     *
     * @param destination
     * 		The base path into which to snapshot the database. Can be null
     * @param dataSource
     * 		The dataSource to invoke snapshot on. Cannot be null
     */
    void snapshot(@NonNull Path destination, VirtualDataSource dataSource);

    /**
     * Builds a new {@link VirtualDataSource} using the configuration of this builder and
     * the given label by copying all the database files from the given path into the new
     * database directory and then opening that database.
     *
     * <p>This method is used when a virtual map is deserialized from a state snapshot.
     * for details.
     *
     * @param label
     * 		The label. Cannot be null. This label must be posix compliant
     * @param snapshotDir
     * 		The base path of the database from which to copy all the database files. Cannot be null
     * @return
     * 		An opened {@link VirtualDataSource}
     */
    @NonNull
    VirtualDataSource restore(String label, Path snapshotDir);
}

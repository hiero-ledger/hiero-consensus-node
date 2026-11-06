// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.config;

/**
 * How a MerkleDb data source is copied, for example when a virtual map is detached for a reconnect.
 * See {@link MerkleDbConfig#dataSourceCopyMode()}.
 */
public enum DataSourceCopyMode {
    /**
     * The indexes are copied in memory, and the data files are hard-linked. No index files are written
     * or read. The copy uses as much off-heap memory for its indexes as the source data source.
     */
    IN_MEMORY,
    /**
     * The data source is snapshotted to disk, and the copy is restored from the snapshot. The snapshot
     * writes all indexes to disk while the source is held unmodified, for example while the pipeline is
     * paused for a reconnect, so the source is held longer than with {@link #IN_MEMORY}. The copy uses
     * as much off-heap memory for its indexes as with {@link #IN_MEMORY}.
     */
    SNAPSHOT,
    /**
     * Like {@link #SNAPSHOT}, but copies with compaction disabled, such as the read-only copy a
     * reconnect teacher makes, use disk-based indexes. They need no off-heap memory for indexes, but
     * read their indexes from disk. Like with {@link #SNAPSHOT}, the indexes are written to disk while
     * the source is held unmodified. Copies with compaction enabled become live data sources, so they
     * are copied as with {@link #IN_MEMORY}.
     */
    DISK_INDEXES
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.common.io.utility.FileUtils.hardLinkTree;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.constructable.constructors.MerkleDbDataSourceBuilderConstructor;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.hiero.base.constructable.ConstructableClass;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Virtual data source builder that manages MerkleDb data sources.
 *
 * <p>When a MerkleDb data source builder creates a new data source, or restores a data source
 * from snapshot, it creates a new temp folder using {@link LegacyTemporaryFileBuilder} as the data
 * source storage dir.
 *
 * <p>When a data source snapshot is taken, or a data source is restored from a snapshot, the
 * builder uses certain sub-folder under snapshot dir as described in {@link #snapshot(Path, VirtualDataSource)}
 * and {@link #restore(String, Path)} methods.
 */
@ConstructableClass(
        value = MerkleDbDataSourceBuilder.CLASS_ID,
        constructorType = MerkleDbDataSourceBuilderConstructor.class)
public class MerkleDbDataSourceBuilder implements VirtualDataSourceBuilder {

    public static final long CLASS_ID = 0x176ede0e1a69828L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
        public static final int NO_TABLE_CONFIG = 2;
    }

    /** Platform configuration */
    private final Configuration configuration;

    private long initialCapacity = 0;

    private long hashesRamToDiskThreshold = 0;

    /**
     * Constructor for deserialization purposes.
     */
    public MerkleDbDataSourceBuilder(@NonNull final Configuration configuration) {
        this.configuration = requireNonNull(configuration);
    }

    /**
     * Creates a new data source builder with the specified table configuration.
     *
     * @param initialCapacity
     * @param hashesRamToDiskThreshold
     * @param configuration platform configuration
     */
    public MerkleDbDataSourceBuilder(
            @NonNull final Configuration configuration,
            final long initialCapacity,
            final long hashesRamToDiskThreshold) {
        this.configuration = requireNonNull(configuration);
        this.initialCapacity = initialCapacity;
        this.hashesRamToDiskThreshold = hashesRamToDiskThreshold;
    }

    private Path newDataSourceDir(final String label) throws IOException {
        return LegacyTemporaryFileBuilder.buildTemporaryFile("merkledb-" + label, configuration);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public VirtualDataSource build(final String label, final boolean dbCompactionEnabled) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial map capacity not set");
        }
        try {
            final Path dataSourceDir = newDataSourceDir(label);
            return new MerkleDbDataSource(
                    dataSourceDir,
                    configuration,
                    label,
                    initialCapacity,
                    hashesRamToDiskThreshold,
                    dbCompactionEnabled,
                    false);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void snapshotDataSource(final MerkleDbDataSource dataSource, final Path dir) {
        try {
            try {
                dataSource.pauseCompaction();
                dataSource.snapshot(dir);
            } finally {
                dataSource.resumeCompaction();
            }
        } catch (final IOException z) {
            throw new UncheckedIOException(z);
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MerkleDbDataSource copy(
            final VirtualDataSource dataSource, final boolean compactionEnabled, final boolean offlineUse) {
        if (!(dataSource instanceof MerkleDbDataSource merkleDbDataSource)) {
            throw new IllegalArgumentException("The data source must be compatible with the MerkleDb");
        }
        final String label = merkleDbDataSource.getTableName();
        final long initialCapacity = merkleDbDataSource.getInitialCapacity();
        final long hashesRamToDiskThreshold = merkleDbDataSource.getHashesRamToDiskThreshold();
        try {
            final Path dataSourceDir = newDataSourceDir(label);
            snapshotDataSource(merkleDbDataSource, dataSourceDir);
            return new MerkleDbDataSource(
                    dataSourceDir,
                    configuration,
                    label,
                    initialCapacity,
                    hashesRamToDiskThreshold,
                    compactionEnabled,
                    offlineUse);
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
    @Override
    public void snapshot(@NonNull final Path snapshotDir, final VirtualDataSource dataSource) {
        if (!(dataSource instanceof MerkleDbDataSource merkleDbDataSource)) {
            throw new IllegalArgumentException("The data source must be compatible with the MerkleDb");
        }
        final String label = merkleDbDataSource.getTableName();
        final Path snapshotDataSourceDir = snapshotDir.resolve("data").resolve(label);
        snapshotDataSource(merkleDbDataSource, snapshotDataSourceDir);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The builder first checks if "data/label" sub-folder exists in the snapshot dir and
     * restores a data source from there. If the sub-folder doesn't exist, it may be an old
     * snapshot with MerkleDb database metadata available. The metadata is used to find the
     * folder for a data source with the given label. If database metadata file is not found,
     * this method throws an IO exception.
     */
    @NonNull
    @Override
    public VirtualDataSource restore(final String label, final Path snapshotDir) {
        return restore(label, snapshotDir, true);
    }

    protected VirtualDataSource restore(final String label, final Path snapshotDir, final boolean compactionEnabled) {
        try {
            final Path dataSourceDir = newDataSourceDir(label);
            final Path snapshotDataSourceDir = snapshotDir.resolve("data").resolve(label);
            if (Files.isDirectory(snapshotDataSourceDir)) {
                hardLinkTree(snapshotDataSourceDir, dataSourceDir);
                return new MerkleDbDataSource(dataSourceDir, configuration, label, compactionEnabled, false);
            }
            final Path legacyDatabaseMetadataPath = snapshotDir.resolve("database_metadata.pbj");
            if (Files.isReadable(legacyDatabaseMetadataPath)) {
                final TableMetadata tableMetadata = getLegacyTableMetadata(legacyDatabaseMetadataPath, label);
                if (tableMetadata != null) {
                    final int tableId = tableMetadata.getTableId();
                    final Path legacySnapshotDataSourceDir =
                            snapshotDir.resolve("tables").resolve(label + "-" + tableId);
                    if (Files.isDirectory(legacySnapshotDataSourceDir)) {
                        hardLinkTree(legacySnapshotDataSourceDir, dataSourceDir);
                        // Load initial capacity and hashes RAM/disk threshold from legacy MerkleDb database config
                        final long initialCapacity =
                                tableMetadata.getTableConfig().getInitialCapacity();
                        final long hashesRamToDiskThreshold =
                                tableMetadata.getTableConfig().getHashesRamToDiskThreshold();
                        return new MerkleDbDataSource(
                                dataSourceDir,
                                configuration,
                                label,
                                initialCapacity,
                                hashesRamToDiskThreshold,
                                true,
                                false);
                    } else {
                        throw new IOException("Table dir is not found: dir=" + legacySnapshotDataSourceDir);
                    }
                } else {
                    throw new IOException("Table metadata not found: label=" + label);
                }
            }
            throw new IOException(
                    "Cannot restore MerkleDb data source: label=" + label + " snapshotDir=" + snapshotDir);
        } catch (final IOException z) {
            throw new UncheckedIOException(z);
        }
    }

    private TableMetadata getLegacyTableMetadata(final Path databaseMetadataPath, final String label)
            throws IOException {
        final FieldDefinition FIELD_DBMETADATA_TABLEMETADATA =
                new FieldDefinition("tableMetadata", FieldType.MESSAGE, true, true, false, 11);
        try (final ReadableStreamingData in = new ReadableStreamingData(databaseMetadataPath)) {
            while (in.hasRemaining()) {
                final int tag = in.readVarInt(false);
                final int fieldNum = tag >> TAG_FIELD_OFFSET;
                if (fieldNum == FIELD_DBMETADATA_TABLEMETADATA.number()) {
                    final int size = in.readVarInt(false);
                    final long oldLimit = in.limit();
                    in.limit(in.position() + size);
                    final TableMetadata tableMetadata = new TableMetadata(in);
                    in.limit(oldLimit);
                    if (label.equals(tableMetadata.getTableName())) {
                        return tableMetadata;
                    }
                } else {
                    throw new IllegalArgumentException("Unknown database metadata field: " + fieldNum);
                }
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.NO_TABLE_CONFIG;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeLong(initialCapacity);
        out.writeLong(hashesRamToDiskThreshold);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(@NonNull final SerializableDataInputStream in, final int version) throws IOException {
        if (version < ClassVersion.NO_TABLE_CONFIG) {
            final MerkleDbTableConfig tableConfig = in.readSerializable(false, MerkleDbTableConfig::new);
            initialCapacity = tableConfig.getInitialCapacity();
            hashesRamToDiskThreshold = tableConfig.getHashesRamToDiskThreshold();
        } else {
            initialCapacity = in.readLong();
            hashesRamToDiskThreshold = in.readLong();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(initialCapacity, hashesRamToDiskThreshold);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof MerkleDbDataSourceBuilder that)) {
            return false;
        }
        return (initialCapacity == that.initialCapacity) && (hashesRamToDiskThreshold == that.hashesRamToDiskThreshold);
    }

    // This is a legacy class to read old snapshots (versions less than ClassVersion.NO_TABLE_CONFIG)
    private static class TableMetadata {

        private final int tableId;

        private final String tableName;

        private final MerkleDbTableConfig tableConfig;

        private static final FieldDefinition FIELD_TABLEMETADATA_TABLEID =
                new FieldDefinition("tableId", FieldType.UINT32, false, true, false, 1);
        private static final FieldDefinition FIELD_TABLEMETADATA_TABLENAME =
                new FieldDefinition("tableName", FieldType.BYTES, false, false, false, 2);
        private static final FieldDefinition FIELD_TABLEMETADATA_TABLECONFIG =
                new FieldDefinition("tableConfig", FieldType.MESSAGE, false, false, false, 3);

        /**
         * Creates a new table metadata object by reading it from an input stream.
         *
         * @param in Input stream to read table metadata from
         */
        public TableMetadata(final ReadableSequentialData in) {
            // Defaults
            int tableId = 0;
            String tableName = null;
            MerkleDbTableConfig tableConfig = null;

            while (in.hasRemaining()) {
                final int tag = in.readVarInt(false);
                final int fieldNum = tag >> TAG_FIELD_OFFSET;
                if (fieldNum == FIELD_TABLEMETADATA_TABLEID.number()) {
                    tableId = in.readVarInt(false);
                } else if (fieldNum == FIELD_TABLEMETADATA_TABLENAME.number()) {
                    final int len = in.readVarInt(false);
                    final byte[] bb = new byte[len];
                    in.readBytes(bb);
                    tableName = new String(bb, StandardCharsets.UTF_8);
                } else if (fieldNum == FIELD_TABLEMETADATA_TABLECONFIG.number()) {
                    final int len = in.readVarInt(false);
                    final long oldLimit = in.limit();
                    in.limit(in.position() + len);
                    tableConfig = new MerkleDbTableConfig(in);
                    in.limit(oldLimit);
                } else {
                    throw new IllegalArgumentException("Unknown table metadata field: " + fieldNum);
                }
            }

            requireNonNull(tableName, "Null table name");
            requireNonNull(tableConfig, "Null table config");

            if (tableId < 0) {
                throw new IllegalStateException("Corrupted MerkleDb metadata: wrong table ID");
            }

            this.tableId = tableId;
            this.tableName = tableName;
            this.tableConfig = tableConfig;
        }

        public int getTableId() {
            return tableId;
        }

        public String getTableName() {
            return tableName;
        }

        public MerkleDbTableConfig getTableConfig() {
            return tableConfig;
        }
    }
}

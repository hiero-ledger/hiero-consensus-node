// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.MerkleDbDataSourceBuilder.CLASS_ID;
import static java.util.Objects.requireNonNull;

import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.constructable.constructors.MerkleDbDataSourceBuilderConstructor;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import org.hiero.base.constructable.ConstructableClass;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Virtual data source builder that manages {@link MerkleDb} based data sources.
 *
 * <p>One of the key MerkleDb builder config options is database directory. When a builder is
 * requested to create a new data source, or restore an existing data sources from snapshot,
 * the data source is hosted in the specified database. Full data source path is therefore
 * databaseDir + "/" + dataSource.label. To make sure there are no folder name conflicts
 * between data sources with the same label, e.g. on copy or snapshot, MerkleDb builders
 * use different database directories, usually managed using {@link LegacyTemporaryFileBuilder}.
 */
@ConstructableClass(value = CLASS_ID, constructorType = MerkleDbDataSourceBuilderConstructor.class)
public class MerkleDbDataSourceBuilder implements VirtualDataSourceBuilder {

    public static final long CLASS_ID = 0x176ede0e1a69828L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    /**
     * Default database folder used by this builder to create and restore data sources. If {@code null},
     * the default {@link MerkleDb} database is used.
     */
    private Path databaseDir;

    /**
     * Table configuration to use when this builder is requested to create a new data source.
     */
    private MerkleDbTableConfig tableConfig;

    /** Platform configuration */
    private final Configuration configuration;

    /**
     * Constructor for deserialization purposes.
     */
    public MerkleDbDataSourceBuilder(final @NonNull Configuration configuration) {
        // for deserialization
        this.configuration = requireNonNull(configuration);
    }

    /**
     * Creates a new data source builder with the specified table configuration.
     *
     * @param tableConfig
     *      Table configuration to use to create new data sources
     * @param configuration platform configuration
     */
    public MerkleDbDataSourceBuilder(
            final MerkleDbTableConfig tableConfig, final @NonNull Configuration configuration) {
        this(null, tableConfig, configuration);
    }

    /**
     * Creates a new data source builder with the specified database dir and table configuration.
     *
     * @param databaseDir
     *      Default database folder. May be {@code null}
     * @param tableConfig
     *      Table configuration to use to create new data sources
     * @param configuration platform configuration
     */
    public MerkleDbDataSourceBuilder(
            final Path databaseDir, final MerkleDbTableConfig tableConfig, final @NonNull Configuration configuration) {
        this.databaseDir = databaseDir;
        this.tableConfig = tableConfig;
        this.configuration = requireNonNull(configuration);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public VirtualDataSource build(final String label, final boolean withDbCompactionEnabled) {
        if (tableConfig == null) {
            throw new IllegalArgumentException("Table serialization config is missing");
        }
        // Creates a new data source in this builder's database dir or in the default MerkleDb instance
        final MerkleDb database = MerkleDb.getInstance(databaseDir, configuration);
        try {
            return database.createDataSource(
                    label, // use VirtualMap name as the table name
                    tableConfig,
                    withDbCompactionEnabled);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MerkleDbDataSource copy(
            final VirtualDataSource snapshotMe, final boolean makeCopyActive, boolean offlineUse) {
        if (!(snapshotMe instanceof MerkleDbDataSource source)) {
            throw new IllegalArgumentException("The datasource must be compatible with the MerkleDb");
        }
        try {
            return source.getDatabase().copyDataSource(source, makeCopyActive, offlineUse);
        } catch (final IOException z) {
            throw new UncheckedIOException(z);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void snapshot(@NonNull final Path destination, final VirtualDataSource snapshotMe) {
        if (!(snapshotMe instanceof MerkleDbDataSource source)) {
            throw new IllegalArgumentException("The datasource must be compatible with the MerkleDb");
        }
        try {
            // Snapshot all tables. When this snapshot() method is called for other data sources,
            // the database will check if they are already present in the destination path. If so,
            // the snapshot will be a no-op
            source.getDatabase().snapshot(destination, source);
        } catch (final IOException z) {
            throw new UncheckedIOException(z);
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public VirtualDataSource restore(final String label, final Path source) {
        try {
            // Restore to the default database. Assuming the default database hasn't been initialized yet.
            // Note that all database data, shared and per-table for all tables, will be restored.
            final MerkleDb database = MerkleDb.restore(source, databaseDir, configuration);
            return database.getDataSource(label, true);
        } catch (final IOException z) {
            throw new UncheckedIOException(z);
        }
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
        return ClassVersion.ORIGINAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        // The order of the first 3 fields matches JasperDbBuilder serialization
        out.writeSerializable(tableConfig, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        tableConfig = in.readSerializable(false, MerkleDbTableConfig::new);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return tableConfig.hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof MerkleDbDataSourceBuilder that)) {
            return false;
        }
        return Objects.equals(tableConfig, that.tableConfig);
    }
}

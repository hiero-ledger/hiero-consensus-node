// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.parameterresolver;

import static com.hedera.statevalidation.parameterresolver.InitUtils.getConfiguration;

import com.swirlds.merkledb.MerkleDb;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * This is a special case of MerkleDbDataSourceBuilder that is using existing state to create a datasource.
 * @param <K>
 * @param <V>
 */
public class RestoringMerkleDbDataSourceBuilder<K extends VirtualKey, V extends VirtualValue>
        extends MerkleDbDataSourceBuilder {

    private final Path databaseDir;

    public RestoringMerkleDbDataSourceBuilder(Path databaseDir, MerkleDbTableConfig tableConfig) {
        super(databaseDir, tableConfig, getConfiguration());
        this.databaseDir = databaseDir;
    }

    @Override
    public VirtualDataSource build(String label, boolean withDbCompactionEnabled) {
        return restore(label, databaseDir);
    }

    public VirtualDataSource restore(String label, Path source) {
        try {
            MerkleDb database = MerkleDb.restore(source, this.databaseDir, getConfiguration());
            return database.getDataSource(label, false);
        } catch (IOException z) {
            throw new UncheckedIOException(z);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.parameterresolver;

import static com.hedera.statevalidation.parameterresolver.InitUtils.getConfiguration;

import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import java.nio.file.Path;

/**
 * This is a special case of MerkleDbDataSourceBuilder that is using existing state to create a datasource.
 */
public class RestoringMerkleDbDataSourceBuilder extends MerkleDbDataSourceBuilder {

    private final Path snapshotDir;

    public RestoringMerkleDbDataSourceBuilder(Path snapshotDir) {
        super(getConfiguration());
        this.snapshotDir = snapshotDir;
    }

    @Override
    public VirtualDataSource build(String label, boolean withDbCompactionEnabled) {
        return restore(label, snapshotDir, false);
    }
}

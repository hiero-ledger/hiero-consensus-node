// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.merkledb;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.virtualmap.VirtualMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({StateResolver.class})
@Tag("compaction")
public class Compaction {

    @Test
    void compaction(DeserializedSignedState deserializedState) {
        final MerkleNodeState merkleNodeState =
                deserializedState.reservedSignedState().get().getState();
        final VirtualMap virtualMap = (VirtualMap) merkleNodeState.getRoot();
        assertNotNull(virtualMap);
        MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();

        vds.enableBackgroundCompaction();

        vds.runKeyToPathStoreCompaction();
        vds.runPathToKeyStoreCompaction();
        vds.runHashStoreCompaction();

        vds.awaitForCurrentCompactionsToComplete(0);
    }
}

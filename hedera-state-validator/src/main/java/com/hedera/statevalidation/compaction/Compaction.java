// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.compaction;

import static java.util.Objects.requireNonNull;

import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

public final class Compaction {

    private Compaction() {}

    public static void runCompaction(@NonNull final VirtualMapState virtualMapState) {
        final VirtualMap virtualMap = virtualMapState.getRoot();
        requireNonNull(virtualMap);
        MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();
        requireNonNull(vds);

        vds.enableBackgroundCompaction();

        vds.runKeyToPathStoreCompaction();
        vds.runPathToKeyStoreCompaction();
        vds.runHashStoreCompaction();

        vds.awaitForCurrentCompactionsToComplete(0);
    }
}

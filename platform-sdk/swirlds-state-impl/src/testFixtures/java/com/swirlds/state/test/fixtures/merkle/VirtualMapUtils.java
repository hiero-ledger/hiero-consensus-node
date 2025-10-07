// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

public final class VirtualMapUtils {

    public static VirtualMap createVirtualMap(@NonNull Configuration configuration, @NonNull final String label) {
        final long MAX_NUM_OF_KEYS = 1_000L; // fixed small number to avoid OOO
        return createVirtualMap(configuration, label, MAX_NUM_OF_KEYS);
    }

    public static VirtualMap createVirtualMap(
            @NonNull Configuration configuration, @NonNull final String label, final long maxNumberOfKeys) {
        final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        final var dsBuilder = new MerkleDbDataSourceBuilder(
                configuration, maxNumberOfKeys, merkleDbConfig.hashesRamToDiskThreshold());
        return new VirtualMap(label, dsBuilder, configuration);
    }
}

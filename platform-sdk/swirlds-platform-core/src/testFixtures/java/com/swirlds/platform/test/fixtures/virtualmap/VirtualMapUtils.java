// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.virtualmap;

import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.CONFIGURATION;

import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.DigestType;

public final class VirtualMapUtils {

    public static VirtualMap createVirtualMap(@NonNull final String label) {
        final long MAX_NUM_OF_KEYS = 1_000L; // fixed small number to avoid OOO
        return createVirtualMap(label, MAX_NUM_OF_KEYS);
    }

    public static VirtualMap createVirtualMap(@NonNull final String label, final long maxNumberOfKeys) {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        final var tableConfig = new MerkleDbTableConfig(
                (short) 1, DigestType.SHA_384, maxNumberOfKeys, merkleDbConfig.hashesRamToDiskThreshold());
        final var dsBuilder = new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
        return new VirtualMap(label, dsBuilder, CONFIGURATION);
    }
}

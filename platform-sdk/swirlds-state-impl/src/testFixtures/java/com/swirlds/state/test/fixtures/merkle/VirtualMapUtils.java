// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.FileSystemManagerConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.config.MerkleDbConfig_;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import edu.umd.cs.findbugs.annotations.NonNull;

public final class VirtualMapUtils {

    static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .withConfigDataType(MerkleDbConfig.class)
            .withSource(new SimpleConfigSource().withValue(MerkleDbConfig_.INITIAL_CAPACITY, "" + 65_536L))
            .withConfigDataType(VirtualMapConfig.class)
            .withConfigDataType(TemporaryFileConfig.class)
            .withConfigDataType(StateCommonConfig.class)
            .withConfigDataType(FileSystemManagerConfig.class)
            .build();

    public static VirtualMap createVirtualMap(@NonNull final String label) {
        return createVirtualMap(CONFIGURATION, label);
    }

    public static VirtualMap createVirtualMap(@NonNull Configuration configuration, @NonNull final String label) {
        final long MAX_NUM_OF_KEYS = 1_000L; // fixed small number to avoid OOO
        return createVirtualMap(configuration, label, MAX_NUM_OF_KEYS);
    }

    public static VirtualMap createVirtualMap(@NonNull final String label, final long maxNumberOfKeys) {
        return createVirtualMap(CONFIGURATION, label, maxNumberOfKeys);
    }

    public static VirtualMap createVirtualMap(
            @NonNull Configuration configuration, @NonNull final String label, final long maxNumberOfKeys) {
        final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        final var dsBuilder = new MerkleDbDataSourceBuilder(
                configuration, maxNumberOfKeys, merkleDbConfig.hashesRamToDiskThreshold());
        return new VirtualMap(label, dsBuilder, configuration);
    }
}

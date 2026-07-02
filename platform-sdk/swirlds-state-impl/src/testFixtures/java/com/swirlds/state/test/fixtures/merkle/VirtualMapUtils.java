// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig_;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.file.FileSystemManager;

public final class VirtualMapUtils {

    public static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .autoDiscoverExtensions()
            .withValue(MerkleDbConfig_.INITIAL_CAPACITY, "" + 65_536L)
            .build();

    public static VirtualMap createVirtualMap(@NonNull final FileSystemManager fileSystemManager) {
        return createVirtualMap(CONFIGURATION, fileSystemManager);
    }

    public static VirtualMap createVirtualMap(
            @NonNull final Configuration configuration, @NonNull final FileSystemManager fileSystemManager) {
        final long MAX_NUM_OF_KEYS = 1_000L; // fixed small number to avoid OOO
        return createVirtualMap(configuration, fileSystemManager, MAX_NUM_OF_KEYS);
    }

    public static VirtualMap createVirtualMap(
            @NonNull final FileSystemManager fileSystemManager, final long maxNumberOfKeys) {
        return createVirtualMap(CONFIGURATION, fileSystemManager, maxNumberOfKeys);
    }

    public static VirtualMap createVirtualMap(
            @NonNull Configuration configuration,
            @NonNull FileSystemManager fileSystemManager,
            final long maxNumberOfKeys) {
        final var dsBuilder = new MerkleDbDataSourceBuilder(configuration, fileSystemManager, maxNumberOfKeys);
        return new VirtualMap(dsBuilder, configuration);
    }
}

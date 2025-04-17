// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;

/**
 * Methods for testing {@link VirtualMap}.
 */
public final class VirtualMapTestUtils {

    private VirtualMapTestUtils() {}

    public static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .withConfigDataType(VirtualMapConfig.class)
            .withConfigDataType(TemporaryFileConfig.class)
            .withConfigDataType(StateCommonConfig.class)
            .build();

    public static final VirtualMapConfig VIRTUAL_MAP_CONFIG = CONFIGURATION.getConfigData(VirtualMapConfig.class);

    public static VirtualMap createMap(String label) {
        final VirtualDataSourceBuilder builder = new InMemoryBuilder();
        return new VirtualMap(label, builder, CONFIGURATION);
    }

    public static VirtualMap createMap() {
        return createMap("Test");
    }

    public static VirtualRootNode createRoot() {
        return createRoot(CONFIGURATION);
    }

    public static VirtualRootNode createRoot(final Configuration configuration) {
        final VirtualRootNode root =
                new VirtualRootNode(new InMemoryBuilder(), configuration.getConfigData(VirtualMapConfig.class));
        root.postInit(new DummyVirtualStateAccessor());
        return root;
    }

    public static VirtualRootNode getRoot(VirtualMap map) {
        return map.getChild(1);
    }
}

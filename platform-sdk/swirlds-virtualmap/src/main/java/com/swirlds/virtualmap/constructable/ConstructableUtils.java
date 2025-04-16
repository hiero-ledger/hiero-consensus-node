// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.constructable;

import com.swirlds.config.api.Configuration;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;

/**
 * Utility class for registering constructable objects from the {@code com.swirlds.virtualmap} package
 * in the {@link ConstructableRegistry}.
 */
public final class ConstructableUtils {
    private ConstructableUtils() {}

    /**
     * Add Virtual Map classes to the constructable registry which need the configuration.
     * @param configuration configuration
     */
    public static void registerVirtualMapConstructables(Configuration configuration)
            throws ConstructableRegistryException {
        ConstructableRegistry.getInstance()
                .registerConstructable(new ClassConstructorPair(VirtualMap.class, () -> new VirtualMap(configuration)));
        ConstructableRegistry.getInstance()
                .registerConstructable(new ClassConstructorPair(
                        VirtualNodeCache.class,
                        () -> new VirtualNodeCache(configuration.getConfigData(VirtualMapConfig.class))));
        ConstructableRegistry.getInstance()
                .registerConstructable(new ClassConstructorPair(
                        VirtualRootNode.class,
                        () -> new VirtualRootNode(configuration.getConfigData(VirtualMapConfig.class))));
    }
}

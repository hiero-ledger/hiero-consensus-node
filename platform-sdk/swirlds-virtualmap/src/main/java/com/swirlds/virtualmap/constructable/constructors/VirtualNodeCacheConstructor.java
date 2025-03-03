// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.constructable.constructors;

import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;

@FunctionalInterface
public interface VirtualNodeCacheConstructor {
    VirtualNodeCache create(VirtualMapConfig virtualMapConfig);
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.constructable.constructors;

import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;

@FunctionalInterface
public interface VirtualRootNodeConstructor {
    VirtualRootNode create(VirtualMapConfig virtualMapConfig);
}

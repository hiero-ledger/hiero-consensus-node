// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable.constructors;

import com.swirlds.common.constructable.constructables.scannable.PrimitiveAndObjectConstructable;

@FunctionalInterface
public interface PrimitiveAndObjectConstructor {
    PrimitiveAndObjectConstructable create(long primitive, Integer object);
}

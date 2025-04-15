// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.constructable.constructors;

import org.hiero.base.constructable.constructables.scannable.PrimitiveAndObjectConstructable;

@FunctionalInterface
public interface PrimitiveAndObjectConstructor {
    PrimitiveAndObjectConstructable create(long primitive, Integer object);
}

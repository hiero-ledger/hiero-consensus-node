// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable.constructors;

import com.swirlds.common.constructable.constructables.scannable.StringConstructable;

@FunctionalInterface
public interface StringConstructor {
    StringConstructable construct(String s);
}

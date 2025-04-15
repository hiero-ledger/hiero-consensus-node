// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.constructable.constructors;

import org.hiero.base.constructable.constructables.scannable.StringConstructable;

@FunctionalInterface
public interface StringConstructor {
    StringConstructable construct(String s);
}

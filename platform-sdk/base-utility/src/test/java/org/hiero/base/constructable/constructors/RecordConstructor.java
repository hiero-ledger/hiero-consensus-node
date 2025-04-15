// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.constructable.constructors;

import org.hiero.base.constructable.constructables.scannable.ConstructableRecord;

@FunctionalInterface
public interface RecordConstructor {
    ConstructableRecord construct(String s);
}

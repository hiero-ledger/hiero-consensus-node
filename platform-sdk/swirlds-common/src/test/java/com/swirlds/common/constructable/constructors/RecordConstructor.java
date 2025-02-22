// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable.constructors;

import com.swirlds.common.constructable.constructables.scannable.ConstructableRecord;

@FunctionalInterface
public interface RecordConstructor {
    ConstructableRecord construct(String s);
}

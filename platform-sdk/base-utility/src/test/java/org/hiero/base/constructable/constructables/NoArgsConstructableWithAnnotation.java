// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.constructable.constructables;

import static org.hiero.base.constructable.constructables.NoArgsConstructableWithAnnotation.CLASS_ID;

import org.hiero.base.constructable.ConstructableClass;
import org.hiero.base.constructable.RuntimeConstructable;

@ConstructableClass(value = CLASS_ID)
public class NoArgsConstructableWithAnnotation implements RuntimeConstructable {
    public static final long CLASS_ID = 0xab0fcb634195d777L;

    @Override
    public long getClassId() {
        return CLASS_ID;
    }
}

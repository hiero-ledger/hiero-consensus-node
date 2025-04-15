// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.constructable.constructables.scannable;

import static org.hiero.base.constructable.constructables.scannable.ConstructableRecord.CLASS_ID;

import org.hiero.base.constructable.constructors.RecordConstructor;
import org.hiero.base.constructable.ConstructableClass;
import org.hiero.base.constructable.RuntimeConstructable;

@ConstructableClass(value = CLASS_ID, constructorType = RecordConstructor.class)
public record ConstructableRecord(String string) implements RuntimeConstructable {
    public static final long CLASS_ID = 0x1ffe2ed217d39a8L;

    @Override
    public long getClassId() {
        return CLASS_ID;
    }
}

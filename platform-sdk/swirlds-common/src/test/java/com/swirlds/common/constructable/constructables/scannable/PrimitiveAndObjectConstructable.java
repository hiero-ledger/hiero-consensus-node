// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable.constructables.scannable;

import static com.swirlds.common.constructable.constructables.scannable.PrimitiveAndObjectConstructable.CLASS_ID;

import com.swirlds.common.constructable.constructors.PrimitiveAndObjectConstructor;
import org.hiero.base.constructable.ConstructableClass;
import org.hiero.base.constructable.RuntimeConstructable;

@ConstructableClass(value = CLASS_ID, constructorType = PrimitiveAndObjectConstructor.class)
public class PrimitiveAndObjectConstructable implements RuntimeConstructable {
    public static final long CLASS_ID = 0xab845f40cd4bd2bdL;

    private final long first;
    private final Integer second;

    public PrimitiveAndObjectConstructable(final long first, final Integer second) {
        this.first = first;
        this.second = second;
    }

    public long getFirst() {
        return first;
    }

    public Integer getSecond() {
        return second;
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }
}

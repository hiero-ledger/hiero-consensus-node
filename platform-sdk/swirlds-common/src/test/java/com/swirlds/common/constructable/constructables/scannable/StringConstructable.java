// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable.constructables.scannable;

import static com.swirlds.common.constructable.constructables.scannable.StringConstructable.CLASS_ID;

import com.swirlds.common.constructable.constructors.StringConstructor;
import org.hiero.base.constructable.ConstructableClass;
import org.hiero.base.constructable.RuntimeConstructable;

@ConstructableClass(value = CLASS_ID, constructorType = StringConstructor.class)
public class StringConstructable implements RuntimeConstructable {
    public static final long CLASS_ID = 0x8b6de1a677f4cfafL;
    private final String string;

    public StringConstructable(final String string) {
        this.string = string;
    }

    public String getString() {
        return string;
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }
}

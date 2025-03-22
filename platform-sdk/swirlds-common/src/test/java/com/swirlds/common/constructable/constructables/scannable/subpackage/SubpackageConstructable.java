// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable.constructables.scannable.subpackage;

import org.hiero.consensus.model.constructable.RuntimeConstructable;

public class SubpackageConstructable implements RuntimeConstructable {
    public static final long CLASS_ID = 0x9ba91a6033637384L;

    @Override
    public long getClassId() {
        return CLASS_ID;
    }
}

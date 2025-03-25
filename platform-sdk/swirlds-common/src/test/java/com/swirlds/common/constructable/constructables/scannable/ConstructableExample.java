// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable.constructables.scannable;

import org.hiero.consensus.model.constructable.RuntimeConstructable;

public class ConstructableExample implements RuntimeConstructable {
    // Note: CLASS_ID should be final, this is not final because of unit tests
    public static long CLASS_ID = 0x722e98dc5b8d52d7L;

    @Override
    public long getClassId() {
        return CLASS_ID;
    }
}

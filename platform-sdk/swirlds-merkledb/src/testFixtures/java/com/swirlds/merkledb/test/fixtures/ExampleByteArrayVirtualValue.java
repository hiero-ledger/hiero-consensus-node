// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import com.swirlds.virtualmap.VirtualValue;

/**
 * Abstract base class for our fixed and variable size example virtual values. This allows tests to handle them the
 * same way.
 */
public abstract class ExampleByteArrayVirtualValue implements VirtualValue {

    public abstract int getId();

    public abstract byte[] getData();

    @Override
    public int hashCode() {
        return getId();
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof ExampleByteArrayVirtualValue that)) {
            return false;
        }
        return getId() == that.getId();
    }
}

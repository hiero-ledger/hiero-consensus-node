// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

public abstract class ExampleByteArrayVirtualValue {

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

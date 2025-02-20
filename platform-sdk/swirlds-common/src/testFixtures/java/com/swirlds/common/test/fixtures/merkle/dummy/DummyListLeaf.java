// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.dummy;

import com.swirlds.common.merkle.utility.AbstractListLeaf;
import com.swirlds.common.merkle.utility.SerializableLong;

public class DummyListLeaf extends AbstractListLeaf<SerializableLong> {

    private static final long CLASS_ID = 0x44d7d917eedb94d2L;

    public DummyListLeaf() {}

    private DummyListLeaf(final DummyListLeaf that) {
        super(that);
    }

    @Override
    public DummyListLeaf copy() {
        return new DummyListLeaf(this);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import com.swirlds.common.merkle.utility.AbstractListLeaf;

public class TransactionCounterList extends AbstractListLeaf<TransactionCounter> {

    public static final long CLASS_ID = 0x791c240dd28d1f1dL;

    public TransactionCounterList() {
        super();
    }

    public TransactionCounterList(int initialSize) {
        super(initialSize);
    }

    public TransactionCounterList(final TransactionCounterList that) {
        super(that);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getMaxSize() {
        return Integer.MAX_VALUE; // FUTURE WORK what is a sane value for this?
    }

    @Override
    public TransactionCounterList copy() {
        return new TransactionCounterList(this);
    }
}

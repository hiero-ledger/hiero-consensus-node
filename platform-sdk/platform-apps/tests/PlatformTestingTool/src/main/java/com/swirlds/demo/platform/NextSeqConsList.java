// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import com.swirlds.common.merkle.utility.AbstractListLeaf;
import com.swirlds.common.merkle.utility.SerializableLong;
import java.util.List;

public class NextSeqConsList extends AbstractListLeaf<SerializableLong> {

    public static final long CLASS_ID = 0xe38268806d8e1e8L;

    public NextSeqConsList() {
        super();
    }

    public NextSeqConsList(List<SerializableLong> longList) {
        super(longList);
    }

    public NextSeqConsList(int initialSize) {
        super(initialSize);
    }

    public NextSeqConsList(final NextSeqConsList that) {
        super(that);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    protected int getMaxSize() {
        return 1000; // FUTURE WORK this should match (or exceed) the total number of nodes
    }

    @Override
    public NextSeqConsList copy() {
        return new NextSeqConsList(this);
    }
}

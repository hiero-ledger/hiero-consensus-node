// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token.meta;

import com.google.common.base.MoreObjects;
import com.hederahashgraph.api.proto.java.SubType;

public abstract class TokenOpMetaBase {
    private final int bpt;
    private final SubType subType;
    private final long transferRecordRb;

    protected TokenOpMetaBase(final int bpt, final SubType subType, final long transferRecordRb) {
        this.bpt = bpt;
        this.subType = subType;
        this.transferRecordRb = transferRecordRb;
    }

    public SubType getSubType() {
        return subType;
    }

    public int getBpt() {
        return bpt;
    }

    public long getTransferRecordDb() {
        return transferRecordRb;
    }

    @Override
    public String toString() {
        return toStringHelper().toString();
    }

    protected MoreObjects.ToStringHelper toStringHelper() {
        return MoreObjects.toStringHelper(this)
                .add("bpt", bpt)
                .add("transferRecordDb", transferRecordRb)
                .add("subType", subType);
    }
}

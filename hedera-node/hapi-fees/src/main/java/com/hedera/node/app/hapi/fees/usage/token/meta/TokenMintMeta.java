// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token.meta;

import com.google.common.base.MoreObjects;
import com.hederahashgraph.api.proto.java.SubType;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class TokenMintMeta extends TokenOpMetaBase {
    private final long rbs;

    public TokenMintMeta(final int bpt, final SubType subType, final long transferRecordRb, final long rbs) {
        super(bpt, subType, transferRecordRb);
        this.rbs = rbs;
    }

    public long getRbs() {
        return rbs;
    }

    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }

    @Override
    public MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("rbs", rbs);
    }
}

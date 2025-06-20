// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token.meta;

import com.google.common.base.MoreObjects;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class TokenFreezeMeta {
    private final int bpt;

    public TokenFreezeMeta(final int bpt) {
        this.bpt = bpt;
    }

    public int getBpt() {
        return bpt;
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
    public String toString() {
        return toStringHelper().toString();
    }

    public MoreObjects.ToStringHelper toStringHelper() {
        return MoreObjects.toStringHelper(this).add("bpt", bpt);
    }
}

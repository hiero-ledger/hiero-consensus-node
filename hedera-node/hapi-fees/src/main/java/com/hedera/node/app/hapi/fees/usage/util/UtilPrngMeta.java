// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.util;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;

import com.google.common.base.MoreObjects;
import com.hederahashgraph.api.proto.java.UtilPrngTransactionBody;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class UtilPrngMeta {
    private final long msgBytesUsed;

    public UtilPrngMeta(UtilPrngTransactionBody txn) {
        msgBytesUsed = txn.getRange() > 0 ? INT_SIZE : 0;
    }

    public UtilPrngMeta(UtilPrngMeta.Builder builder) {
        msgBytesUsed = builder.msgBytes;
    }

    public long getMsgBytesUsed() {
        return msgBytesUsed;
    }

    public static class Builder {
        private long msgBytes;

        public UtilPrngMeta.Builder msgBytesUsed(long msgBytes) {
            this.msgBytes = msgBytes;
            return this;
        }

        public Builder() {
            // empty here on purpose.
        }

        public UtilPrngMeta build() {
            return new UtilPrngMeta(this);
        }
    }

    public static UtilPrngMeta.Builder newBuilder() {
        return new UtilPrngMeta.Builder();
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
        return MoreObjects.toStringHelper(this)
                .add("msgBytesUsed", msgBytesUsed)
                .toString();
    }
}

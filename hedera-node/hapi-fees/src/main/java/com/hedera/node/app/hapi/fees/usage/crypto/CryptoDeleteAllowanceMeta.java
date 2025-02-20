// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.NFT_DELETE_ALLOWANCE_SIZE;

import com.google.common.base.MoreObjects;
import com.hederahashgraph.api.proto.java.CryptoDeleteAllowanceTransactionBody;
import com.hederahashgraph.api.proto.java.NftRemoveAllowance;
import java.util.List;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

/** Metadata for CryptoDeleteAllowance */
public class CryptoDeleteAllowanceMeta {
    private final long effectiveNow;
    private final long msgBytesUsed;

    public CryptoDeleteAllowanceMeta(Builder builder) {
        effectiveNow = builder.effectiveNow;
        msgBytesUsed = builder.msgBytesUsed;
    }

    public CryptoDeleteAllowanceMeta(
            CryptoDeleteAllowanceTransactionBody cryptoDeleteTxnBody, long transactionValidStartSecs) {
        effectiveNow = transactionValidStartSecs;
        msgBytesUsed = bytesUsedInTxn(cryptoDeleteTxnBody);
    }

    private int bytesUsedInTxn(CryptoDeleteAllowanceTransactionBody op) {
        return op.getNftAllowancesCount() * NFT_DELETE_ALLOWANCE_SIZE
                + countNftDeleteSerials(op.getNftAllowancesList()) * LONG_SIZE;
    }

    public static int countNftDeleteSerials(final List<NftRemoveAllowance> nftAllowancesList) {
        int totalSerials = 0;
        for (var allowance : nftAllowancesList) {
            totalSerials += allowance.getSerialNumbersCount();
        }
        return totalSerials;
    }

    public static Builder newBuilder() {
        return new CryptoDeleteAllowanceMeta.Builder();
    }

    public long getEffectiveNow() {
        return effectiveNow;
    }

    public long getMsgBytesUsed() {
        return msgBytesUsed;
    }

    public static class Builder {
        private long effectiveNow;
        private long msgBytesUsed;

        public CryptoDeleteAllowanceMeta.Builder effectiveNow(long now) {
            this.effectiveNow = now;
            return this;
        }

        public CryptoDeleteAllowanceMeta.Builder msgBytesUsed(long msgBytesUsed) {
            this.msgBytesUsed = msgBytesUsed;
            return this;
        }

        public Builder() {
            // empty here on purpose.
        }

        public CryptoDeleteAllowanceMeta build() {
            return new CryptoDeleteAllowanceMeta(this);
        }
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
                .add("effectiveNow", effectiveNow)
                .add("msgBytesUsed", msgBytesUsed)
                .toString();
    }
}

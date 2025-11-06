// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.convertToCryptoMap;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.convertToNftMap;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.convertToTokenMap;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.countSerials;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.CRYPTO_ALLOWANCE_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.NFT_ALLOWANCE_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.TOKEN_ALLOWANCE_SIZE;

import com.google.common.base.MoreObjects;
import com.hederahashgraph.api.proto.java.CryptoApproveAllowanceTransactionBody;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

/** Metadata for CryptoApproveAllowance */
public class CryptoApproveAllowanceMeta {
    private final long effectiveNow;
    private final long msgBytesUsed;
    private final Map<Long, Long> cryptoAllowances;
    private final int cryptoAllowancesCount;
    private final Map<AllowanceId, Long> tokenAllowances;
    private final Set<AllowanceId> nftAllowances;

    public CryptoApproveAllowanceMeta(final Builder builder) {
        effectiveNow = builder.effectiveNow;
        msgBytesUsed = builder.msgBytesUsed;
        cryptoAllowances = builder.cryptoAllowances;
        cryptoAllowancesCount = cryptoAllowances.size();
        tokenAllowances = builder.tokenAllowances;
        nftAllowances = builder.nftAllowances;
    }

    public CryptoApproveAllowanceMeta(
            final CryptoApproveAllowanceTransactionBody cryptoApproveTxnBody, final long transactionValidStartSecs) {
        effectiveNow = transactionValidStartSecs;
        msgBytesUsed = bytesUsedInTxn(cryptoApproveTxnBody);
        cryptoAllowances = convertToCryptoMap(cryptoApproveTxnBody.getCryptoAllowancesList());
        cryptoAllowancesCount = cryptoApproveTxnBody.getCryptoAllowancesCount();
        tokenAllowances = convertToTokenMap(cryptoApproveTxnBody.getTokenAllowancesList());
        nftAllowances = convertToNftMap(cryptoApproveTxnBody.getNftAllowancesList());
    }

    public int getCryptoAllowancesCount() {
        return cryptoAllowancesCount;
    }

    private int bytesUsedInTxn(final CryptoApproveAllowanceTransactionBody op) {
        return op.getCryptoAllowancesCount() * CRYPTO_ALLOWANCE_SIZE
                + op.getTokenAllowancesCount() * TOKEN_ALLOWANCE_SIZE
                + op.getNftAllowancesCount() * NFT_ALLOWANCE_SIZE
                + countSerials(op.getNftAllowancesList()) * LONG_SIZE;
    }

    public static Builder newBuilder() {
        return new CryptoApproveAllowanceMeta.Builder();
    }

    public long getEffectiveNow() {
        return effectiveNow;
    }

    public long getMsgBytesUsed() {
        return msgBytesUsed;
    }

    public Map<Long, Long> getCryptoAllowances() {
        return cryptoAllowances;
    }

    public Map<AllowanceId, Long> getTokenAllowances() {
        return tokenAllowances;
    }

    public Set<AllowanceId> getNftAllowances() {
        return nftAllowances;
    }

    public static class Builder {
        private long effectiveNow;
        private long msgBytesUsed;
        private Map<Long, Long> cryptoAllowances;
        private Map<AllowanceId, Long> tokenAllowances;
        private Set<AllowanceId> nftAllowances;

        public CryptoApproveAllowanceMeta.Builder cryptoAllowances(final Map<Long, Long> cryptoAllowances) {
            this.cryptoAllowances = cryptoAllowances;
            return this;
        }

        public CryptoApproveAllowanceMeta.Builder tokenAllowances(final Map<AllowanceId, Long> tokenAllowances) {
            this.tokenAllowances = tokenAllowances;
            return this;
        }

        public CryptoApproveAllowanceMeta.Builder nftAllowances(final Set<AllowanceId> nftAllowances) {
            this.nftAllowances = nftAllowances;
            return this;
        }

        public CryptoApproveAllowanceMeta.Builder effectiveNow(final long now) {
            this.effectiveNow = now;
            return this;
        }

        public CryptoApproveAllowanceMeta.Builder msgBytesUsed(final long msgBytesUsed) {
            this.msgBytesUsed = msgBytesUsed;
            return this;
        }

        public Builder() {
            // empty here on purpose.
        }

        public CryptoApproveAllowanceMeta build() {
            return new CryptoApproveAllowanceMeta(this);
        }
    }

    @Override
    public boolean equals(final Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("cryptoAllowances", cryptoAllowances)
                .add("tokenAllowances", tokenAllowances)
                .add("nftAllowances", nftAllowances)
                .add("effectiveNow", effectiveNow)
                .add("msgBytesUsed", msgBytesUsed)
                .toString();
    }
}

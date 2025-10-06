// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.node.app.hapi.fees.usage.TxnUsage.keySizeIfPresent;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;

import com.google.common.base.MoreObjects;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class CryptoCreateMeta {
    private final long baseSize;
    private final long lifeTime;
    private final int maxAutomaticAssociations;
    private final long numHooks;

    public CryptoCreateMeta(final CryptoCreateTransactionBody cryptoCreateTxnBody) {
        baseSize = getCryptoCreateTxnBaseSize(cryptoCreateTxnBody);
        lifeTime = cryptoCreateTxnBody.getAutoRenewPeriod().getSeconds();
        maxAutomaticAssociations = cryptoCreateTxnBody.getMaxAutomaticTokenAssociations();
        numHooks = cryptoCreateTxnBody.getHookCreationDetailsCount();
    }

    public CryptoCreateMeta(final Builder builder) {
        this.baseSize = builder.baseSize;
        this.lifeTime = builder.lifeTime;
        this.maxAutomaticAssociations = builder.maxAutomaticAssociations;
        this.numHooks = builder.numHooks;
    }

    private long getCryptoCreateTxnBaseSize(final CryptoCreateTransactionBody op) {
        long variableBytes = op.getMemoBytes().size();
        variableBytes += keySizeIfPresent(op, CryptoCreateTransactionBody::hasKey, CryptoCreateTransactionBody::getKey);
        if (op.hasProxyAccountID()) {
            variableBytes += BASIC_ENTITY_ID_SIZE;
        }
        return variableBytes;
    }

    public long getBaseSize() {
        return baseSize;
    }

    public long getLifeTime() {
        return lifeTime;
    }

    public int getMaxAutomaticAssociations() {
        return maxAutomaticAssociations;
    }

    public long getNumHooks() {
        return numHooks;
    }

    public static class Builder {
        private long baseSize;
        private long lifeTime;
        private int maxAutomaticAssociations;
        private long numHooks;

        public Builder() {
            // empty here on purpose.
        }

        public CryptoCreateMeta.Builder baseSize(final int baseSize) {
            this.baseSize = baseSize;
            return this;
        }

        public CryptoCreateMeta.Builder lifeTime(final long lifeTime) {
            this.lifeTime = lifeTime;
            return this;
        }

        public CryptoCreateMeta.Builder maxAutomaticAssociations(final int maxAutomaticAssociations) {
            this.maxAutomaticAssociations = maxAutomaticAssociations;
            return this;
        }

        public CryptoCreateMeta.Builder numHooks(final long numHooks) {
            this.numHooks = numHooks;
            return this;
        }

        public CryptoCreateMeta build() {
            return new CryptoCreateMeta(this);
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
                .add("baseSize", baseSize)
                .add("lifeTime", lifeTime)
                .add("maxAutomaticAssociations", maxAutomaticAssociations)
                .add("numHooks", numHooks)
                .toString();
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;

import com.google.common.base.MoreObjects;
import com.hederahashgraph.api.proto.java.CryptoUpdateTransactionBody;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class CryptoUpdateMeta {

    private final long keyBytesUsed;
    private final long msgBytesUsed;
    private final long memoSize;
    private final long effectiveNow;
    private final long expiry;
    private final boolean hasProxy;
    private final int maxAutomaticAssociations;
    private final boolean hasMaxAutomaticAssociations;
    private final int numHookCreations;
    private final int numHookDeletions;

    public CryptoUpdateMeta(Builder builder) {
        keyBytesUsed = builder.keyBytesUsed;
        msgBytesUsed = builder.msgBytesUsed;
        memoSize = builder.memoSize;
        effectiveNow = builder.effectiveNow;
        expiry = builder.expiry;
        hasProxy = builder.hasProxy;
        maxAutomaticAssociations = builder.maxAutomaticAssociations;
        hasMaxAutomaticAssociations = builder.hasMaxAutomaticAssociations;
        numHookCreations = builder.numHookCreations;
        numHookDeletions = builder.numHookDeletions;
    }

    public CryptoUpdateMeta(CryptoUpdateTransactionBody cryptoUpdateTxnBody, long transactionValidStartSecs) {
        keyBytesUsed = cryptoUpdateTxnBody.hasKey() ? getAccountKeyStorageSize(cryptoUpdateTxnBody.getKey()) : 0;
        msgBytesUsed = bytesUsedInTxn(cryptoUpdateTxnBody) + keyBytesUsed;
        memoSize = cryptoUpdateTxnBody.hasMemo()
                ? cryptoUpdateTxnBody.getMemo().getValueBytes().size()
                : 0;
        effectiveNow = transactionValidStartSecs;
        expiry = cryptoUpdateTxnBody.getExpirationTime().getSeconds();
        hasProxy = cryptoUpdateTxnBody.hasProxyAccountID();
        hasMaxAutomaticAssociations = cryptoUpdateTxnBody.hasMaxAutomaticTokenAssociations();
        maxAutomaticAssociations =
                cryptoUpdateTxnBody.getMaxAutomaticTokenAssociations().getValue();
        numHookCreations = cryptoUpdateTxnBody.getHookCreationDetailsCount();
        numHookDeletions = cryptoUpdateTxnBody.getHookIdsToDeleteCount();
    }

    public long getMsgBytesUsed() {
        return msgBytesUsed;
    }

    public long getKeyBytesUsed() {
        return keyBytesUsed;
    }

    public long getMemoSize() {
        return memoSize;
    }

    public long getEffectiveNow() {
        return effectiveNow;
    }

    public long getExpiry() {
        return expiry;
    }

    public boolean hasProxy() {
        return hasProxy;
    }

    public int getMaxAutomaticAssociations() {
        return maxAutomaticAssociations;
    }

    public boolean hasMaxAutomaticAssociations() {
        return hasMaxAutomaticAssociations;
    }

    public int getNumHookCreations() {
        return numHookCreations;
    }

    public int getNumHookDeletions() {
        return numHookDeletions;
    }

    private int bytesUsedInTxn(CryptoUpdateTransactionBody op) {
        return BASIC_ENTITY_ID_SIZE
                + op.getMemo().getValueBytes().size()
                + (op.hasExpirationTime() ? LONG_SIZE : 0)
                + (op.hasAutoRenewPeriod() ? LONG_SIZE : 0)
                + (op.hasProxyAccountID() ? BASIC_ENTITY_ID_SIZE : 0)
                + (op.hasMaxAutomaticTokenAssociations() ? INT_SIZE : 0);
    }

    public static class Builder {
        private long keyBytesUsed;
        private long msgBytesUsed;
        private long memoSize;
        private long effectiveNow;
        private long expiry;
        private boolean hasProxy;
        private int maxAutomaticAssociations;
        private boolean hasMaxAutomaticAssociations;
        private int numHookCreations;
        private int numHookDeletions;

        public Builder() {
            // empty here on purpose.
        }

        public CryptoUpdateMeta.Builder keyBytesUsed(long keyBytesUsed) {
            this.keyBytesUsed = keyBytesUsed;
            return this;
        }

        public CryptoUpdateMeta.Builder msgBytesUsed(long msgBytesUsed) {
            this.msgBytesUsed = msgBytesUsed;
            return this;
        }

        public CryptoUpdateMeta.Builder memoSize(long memoSize) {
            this.memoSize = memoSize;
            return this;
        }

        public CryptoUpdateMeta.Builder effectiveNow(long effectiveNow) {
            this.effectiveNow = effectiveNow;
            return this;
        }

        public CryptoUpdateMeta.Builder expiry(long expiry) {
            this.expiry = expiry;
            return this;
        }

        public CryptoUpdateMeta.Builder hasProxy(boolean hasProxy) {
            this.hasProxy = hasProxy;
            return this;
        }

        public CryptoUpdateMeta.Builder maxAutomaticAssociations(int maxAutomaticAssociations) {
            this.maxAutomaticAssociations = maxAutomaticAssociations;
            return this;
        }

        public CryptoUpdateMeta.Builder hasMaxAutomaticAssociations(boolean hasMaxAutomaticAssociations) {
            this.hasMaxAutomaticAssociations = hasMaxAutomaticAssociations;
            return this;
        }

        public CryptoUpdateMeta.Builder numHookCreations(int numHookCreations) {
            this.numHookCreations = numHookCreations;
            return this;
        }

        public CryptoUpdateMeta.Builder numHookDeletions(int numHookDeletions) {
            this.numHookDeletions = numHookDeletions;
            return this;
        }

        public CryptoUpdateMeta build() {
            return new CryptoUpdateMeta(this);
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
                .add("keyBytesUsed", keyBytesUsed)
                .add("msgBytesUsed", msgBytesUsed)
                .add("memoSize", memoSize)
                .add("effectiveNow", effectiveNow)
                .add("expiry", expiry)
                .add("hasProxy", hasProxy)
                .add("maxAutomaticAssociations", maxAutomaticAssociations)
                .add("hasMaxAutomaticAssociations", hasMaxAutomaticAssociations)
                .toString();
    }
}

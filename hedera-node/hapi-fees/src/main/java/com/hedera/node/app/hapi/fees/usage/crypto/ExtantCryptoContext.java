// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.convertToCryptoMapFromGranted;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.convertToNftMapFromGranted;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.convertToTokenMapFromGranted;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;

import com.hederahashgraph.api.proto.java.GrantedCryptoAllowance;
import com.hederahashgraph.api.proto.java.GrantedNftAllowance;
import com.hederahashgraph.api.proto.java.GrantedTokenAllowance;
import com.hederahashgraph.api.proto.java.Key;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ExtantCryptoContext {
    private final int currentNumTokenRels;
    private final Key currentKey;
    private final long currentExpiry;
    private final String currentMemo;
    private final boolean currentlyHasProxy;
    private final int currentMaxAutomaticAssociations;
    private final Map<Long, Long> currentCryptoAllowances;
    private final Map<AllowanceId, Long> currentTokenAllowances;
    private final Set<AllowanceId> currentApproveForAllNftAllowances;

    private ExtantCryptoContext(final ExtantCryptoContext.Builder builder) {
        currentNumTokenRels = builder.currentNumTokenRels;
        currentMemo = builder.currentMemo;
        currentExpiry = builder.currentExpiry;
        currentKey = builder.currentKey;
        currentlyHasProxy = builder.currentlyHasProxy;
        currentMaxAutomaticAssociations = builder.currentMaxAutomaticAssociations;
        this.currentCryptoAllowances = builder.currentCryptoAllowances;
        this.currentTokenAllowances = builder.currentTokenAllowances;
        this.currentApproveForAllNftAllowances = builder.currentApproveForAllNftAllowances;
    }

    public long currentNonBaseRb() {
        return (long) (currentlyHasProxy ? BASIC_ENTITY_ID_SIZE : 0)
                + currentMemo.getBytes(StandardCharsets.UTF_8).length
                + getAccountKeyStorageSize(currentKey)
                + (currentMaxAutomaticAssociations == 0 ? 0 : INT_SIZE);
    }

    public Key currentKey() {
        return currentKey;
    }

    public int currentNumTokenRels() {
        return currentNumTokenRels;
    }

    public long currentExpiry() {
        return currentExpiry;
    }

    public String currentMemo() {
        return currentMemo;
    }

    public boolean currentlyHasProxy() {
        return currentlyHasProxy;
    }

    public int currentMaxAutomaticAssociations() {
        return currentMaxAutomaticAssociations;
    }

    public Map<Long, Long> currentCryptoAllowances() {
        return currentCryptoAllowances;
    }

    public Map<AllowanceId, Long> currentTokenAllowances() {
        return currentTokenAllowances;
    }

    public Set<AllowanceId> currentNftAllowances() {
        return currentApproveForAllNftAllowances;
    }

    public static ExtantCryptoContext.Builder newBuilder() {
        return new ExtantCryptoContext.Builder();
    }

    public static class Builder {
        private static final int HAS_PROXY_MASK = 1 << 0;
        private static final int EXPIRY_MASK = 1 << 1;
        private static final int MEMO_MASK = 1 << 2;
        private static final int KEY_MASK = 1 << 3;
        private static final int TOKEN_RELS_MASK = 1 << 4;
        private static final int MAX_AUTO_ASSOCIATIONS_MASK = 1 << 5;
        private static final int CRYPTO_ALLOWANCES_MASK = 1 << 6;
        private static final int TOKEN_ALLOWANCES_MASK = 1 << 7;
        private static final int NFT_ALLOWANCES_MASK = 1 << 8;

        private static final int ALL_FIELDS_MASK = TOKEN_RELS_MASK
                | EXPIRY_MASK
                | MEMO_MASK
                | KEY_MASK
                | HAS_PROXY_MASK
                | MAX_AUTO_ASSOCIATIONS_MASK
                | CRYPTO_ALLOWANCES_MASK
                | TOKEN_ALLOWANCES_MASK
                | NFT_ALLOWANCES_MASK;

        private int mask = 0;

        private int currentNumTokenRels;
        private Key currentKey;
        private String currentMemo;
        private boolean currentlyHasProxy;
        private long currentExpiry;
        private int currentMaxAutomaticAssociations;
        private Map<Long, Long> currentCryptoAllowances;
        private Map<AllowanceId, Long> currentTokenAllowances;
        private Set<AllowanceId> currentApproveForAllNftAllowances;

        private Builder() {}

        public ExtantCryptoContext build() {
            if (mask != ALL_FIELDS_MASK) {
                throw new IllegalStateException(String.format("Field mask is %d, not %d!", mask, ALL_FIELDS_MASK));
            }
            return new ExtantCryptoContext(this);
        }

        public ExtantCryptoContext.Builder setCurrentNumTokenRels(final int currentNumTokenRels) {
            this.currentNumTokenRels = currentNumTokenRels;
            mask |= TOKEN_RELS_MASK;
            return this;
        }

        public ExtantCryptoContext.Builder setCurrentExpiry(final long currentExpiry) {
            this.currentExpiry = currentExpiry;
            mask |= EXPIRY_MASK;
            return this;
        }

        public ExtantCryptoContext.Builder setCurrentMemo(final String currentMemo) {
            this.currentMemo = currentMemo;
            mask |= MEMO_MASK;
            return this;
        }

        public ExtantCryptoContext.Builder setCurrentKey(final Key currentKey) {
            this.currentKey = currentKey;
            mask |= KEY_MASK;
            return this;
        }

        public ExtantCryptoContext.Builder setCurrentlyHasProxy(final boolean currentlyHasProxy) {
            this.currentlyHasProxy = currentlyHasProxy;
            mask |= HAS_PROXY_MASK;
            return this;
        }

        public ExtantCryptoContext.Builder setCurrentMaxAutomaticAssociations(
                final int currentMaxAutomaticAssociations) {
            this.currentMaxAutomaticAssociations = currentMaxAutomaticAssociations;
            mask |= MAX_AUTO_ASSOCIATIONS_MASK;
            return this;
        }

        public ExtantCryptoContext.Builder setCurrentCryptoAllowances(final Map<Long, Long> currentCryptoAllowances) {
            this.currentCryptoAllowances = currentCryptoAllowances;
            mask |= CRYPTO_ALLOWANCES_MASK;
            return this;
        }

        public ExtantCryptoContext.Builder setCurrentTokenAllowances(
                final Map<AllowanceId, Long> currentTokenAllowances) {
            this.currentTokenAllowances = currentTokenAllowances;
            mask |= TOKEN_ALLOWANCES_MASK;
            return this;
        }

        public ExtantCryptoContext.Builder setCurrentApproveForAllNftAllowances(
                final Set<AllowanceId> currentApproveForAllNftAllowances) {
            this.currentApproveForAllNftAllowances = currentApproveForAllNftAllowances;
            mask |= NFT_ALLOWANCES_MASK;
            return this;
        }

        public Builder setCurrentCryptoAllowances(final List<GrantedCryptoAllowance> grantedCryptoAllowancesList) {
            this.currentCryptoAllowances = convertToCryptoMapFromGranted(grantedCryptoAllowancesList);
            mask |= CRYPTO_ALLOWANCES_MASK;
            return this;
        }

        public Builder setCurrentTokenAllowances(final List<GrantedTokenAllowance> grantedTokenAllowancesList) {
            this.currentTokenAllowances = convertToTokenMapFromGranted(grantedTokenAllowancesList);
            mask |= TOKEN_ALLOWANCES_MASK;
            return this;
        }

        public Builder setCurrentApproveForAllNftAllowances(final List<GrantedNftAllowance> grantedNftAllowancesList) {
            this.currentApproveForAllNftAllowances = convertToNftMapFromGranted(grantedNftAllowancesList);
            mask |= NFT_ALLOWANCES_MASK;
            return this;
        }
    }
}

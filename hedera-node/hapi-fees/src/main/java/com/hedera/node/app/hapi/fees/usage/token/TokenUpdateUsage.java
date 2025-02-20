// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.usage.EstimatorUtils.MAX_ENTITY_LIFETIME;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;

import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.Optional;

public class TokenUpdateUsage extends TokenTxnUsage<TokenUpdateUsage> {
    private int currentMemoLen;
    private int currentNameLen;
    private int currentSymbolLen;
    private long currentExpiry;
    private long currentMutableRb = 0;
    private boolean currentlyUsingAutoRenew = false;

    private TokenUpdateUsage(final TransactionBody tokenUpdateOp, final TxnUsageEstimator usageEstimator) {
        super(tokenUpdateOp, usageEstimator);
    }

    public static TokenUpdateUsage newEstimate(
            final TransactionBody tokenUpdateOp, final TxnUsageEstimator usageEstimator) {
        return new TokenUpdateUsage(tokenUpdateOp, usageEstimator);
    }

    @Override
    TokenUpdateUsage self() {
        return this;
    }

    public TokenUpdateUsage givenCurrentAdminKey(final Optional<Key> adminKey) {
        adminKey.map(FeeBuilder::getAccountKeyStorageSize).ifPresent(this::updateCurrentRb);
        return this;
    }

    public TokenUpdateUsage givenCurrentWipeKey(final Optional<Key> wipeKey) {
        wipeKey.map(FeeBuilder::getAccountKeyStorageSize).ifPresent(this::updateCurrentRb);
        return this;
    }

    public TokenUpdateUsage givenCurrentSupplyKey(final Optional<Key> supplyKey) {
        supplyKey.map(FeeBuilder::getAccountKeyStorageSize).ifPresent(this::updateCurrentRb);
        return this;
    }

    public TokenUpdateUsage givenCurrentFreezeKey(final Optional<Key> freezeKey) {
        freezeKey.map(FeeBuilder::getAccountKeyStorageSize).ifPresent(this::updateCurrentRb);
        return this;
    }

    public TokenUpdateUsage givenCurrentKycKey(final Optional<Key> kycKey) {
        kycKey.map(FeeBuilder::getAccountKeyStorageSize).ifPresent(this::updateCurrentRb);
        return this;
    }

    public TokenUpdateUsage givenCurrentFeeScheduleKey(final Optional<Key> feeScheduleKey) {
        feeScheduleKey.map(FeeBuilder::getAccountKeyStorageSize).ifPresent(this::updateCurrentRb);
        return this;
    }

    public TokenUpdateUsage givenCurrentPauseKey(final Optional<Key> pauseKey) {
        pauseKey.map(FeeBuilder::getAccountKeyStorageSize).ifPresent(this::updateCurrentRb);
        return this;
    }

    public TokenUpdateUsage givenCurrentMemo(final String memo) {
        currentMemoLen = memo.length();
        updateCurrentRb(currentMemoLen);
        return this;
    }

    public TokenUpdateUsage givenCurrentName(final String name) {
        currentNameLen = name.length();
        updateCurrentRb(currentNameLen);
        return this;
    }

    public TokenUpdateUsage givenCurrentSymbol(final String symbol) {
        currentSymbolLen = symbol.length();
        updateCurrentRb(currentSymbolLen);
        return this;
    }

    public TokenUpdateUsage givenCurrentlyUsingAutoRenewAccount() {
        currentlyUsingAutoRenew = true;
        updateCurrentRb(BASIC_ENTITY_ID_SIZE);
        return this;
    }

    public TokenUpdateUsage givenCurrentExpiry(final long expiry) {
        this.currentExpiry = expiry;
        return this;
    }

    public FeeData get() {
        final var op = this.op.getTokenUpdate();

        long newMutableRb = 0;
        newMutableRb +=
                keySizeIfPresent(op, TokenUpdateTransactionBody::hasKycKey, TokenUpdateTransactionBody::getKycKey);
        newMutableRb +=
                keySizeIfPresent(op, TokenUpdateTransactionBody::hasWipeKey, TokenUpdateTransactionBody::getWipeKey);
        newMutableRb +=
                keySizeIfPresent(op, TokenUpdateTransactionBody::hasAdminKey, TokenUpdateTransactionBody::getAdminKey);
        newMutableRb += keySizeIfPresent(
                op, TokenUpdateTransactionBody::hasSupplyKey, TokenUpdateTransactionBody::getSupplyKey);
        newMutableRb += keySizeIfPresent(
                op, TokenUpdateTransactionBody::hasFreezeKey, TokenUpdateTransactionBody::getFreezeKey);
        newMutableRb +=
                keySizeIfPresent(op, TokenUpdateTransactionBody::hasPauseKey, TokenUpdateTransactionBody::getPauseKey);
        if (!removesAutoRenewAccount(op) && (currentlyUsingAutoRenew || op.hasAutoRenewAccount())) {
            newMutableRb += BASIC_ENTITY_ID_SIZE;
        }
        newMutableRb += op.hasMemo() ? op.getMemo().getValue().length() : currentMemoLen;
        newMutableRb += (op.getName().length() > 0) ? op.getName().length() : currentNameLen;
        newMutableRb += (op.getSymbol().length() > 0) ? op.getSymbol().length() : currentSymbolLen;
        long newLifetime = ESTIMATOR_UTILS.relativeLifetime(
                this.op, Math.max(op.getExpiry().getSeconds(), currentExpiry));
        newLifetime = Math.min(newLifetime, MAX_ENTITY_LIFETIME);
        final long rbsDelta = Math.max(0, newLifetime * (newMutableRb - currentMutableRb));
        if (rbsDelta > 0) {
            usageEstimator.addRbs(rbsDelta);
        }

        final long txnBytes = newMutableRb + BASIC_ENTITY_ID_SIZE + noRbImpactBytes(op);
        usageEstimator.addBpt(txnBytes);
        if (op.hasTreasury()) {
            addTokenTransfersRecordRb(1, 2, 0);
        }

        return usageEstimator.get();
    }

    private int noRbImpactBytes(final TokenUpdateTransactionBody op) {
        return ((op.getExpiry().getSeconds() > 0) ? AMOUNT_REPR_BYTES : 0)
                + ((op.getAutoRenewPeriod().getSeconds() > 0) ? AMOUNT_REPR_BYTES : 0)
                + (op.hasTreasury() ? BASIC_ENTITY_ID_SIZE : 0)
                + (op.hasAutoRenewAccount() ? BASIC_ENTITY_ID_SIZE : 0);
    }

    private boolean removesAutoRenewAccount(final TokenUpdateTransactionBody op) {
        return op.hasAutoRenewAccount() && designatesAccountRemoval(op.getAutoRenewAccount());
    }

    private boolean designatesAccountRemoval(final AccountID id) {
        return id.getShardNum() == 0
                && id.getRealmNum() == 0
                && id.getAccountNum() == 0
                && id.getAlias().isEmpty();
    }

    private void updateCurrentRb(final long amount) {
        currentMutableRb += amount;
    }
}

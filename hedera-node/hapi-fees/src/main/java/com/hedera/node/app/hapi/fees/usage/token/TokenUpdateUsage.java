// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.usage.EstimatorUtils.MAX_ENTITY_LIFETIME;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
        final var op = this.op.tokenUpdate();

        long newMutableRb = 0;
        newMutableRb += keySizeIfPresent(op, TokenUpdateTransactionBody::hasKycKey, TokenUpdateTransactionBody::kycKey);
        newMutableRb +=
                keySizeIfPresent(op, TokenUpdateTransactionBody::hasWipeKey, TokenUpdateTransactionBody::wipeKey);
        newMutableRb +=
                keySizeIfPresent(op, TokenUpdateTransactionBody::hasAdminKey, TokenUpdateTransactionBody::adminKey);
        newMutableRb +=
                keySizeIfPresent(op, TokenUpdateTransactionBody::hasSupplyKey, TokenUpdateTransactionBody::supplyKey);
        newMutableRb +=
                keySizeIfPresent(op, TokenUpdateTransactionBody::hasFreezeKey, TokenUpdateTransactionBody::freezeKey);
        newMutableRb +=
                keySizeIfPresent(op, TokenUpdateTransactionBody::hasPauseKey, TokenUpdateTransactionBody::pauseKey);
        if (!removesAutoRenewAccount(op) && (currentlyUsingAutoRenew || op.hasAutoRenewAccount())) {
            newMutableRb += BASIC_ENTITY_ID_SIZE;
        }
        newMutableRb += op.hasMemo() ? op.memo().length() : currentMemoLen;
        newMutableRb += (op.name().length() > 0) ? op.name().length() : currentNameLen;
        newMutableRb += (op.symbol().length() > 0) ? op.symbol().length() : currentSymbolLen;
        long newLifetime = ESTIMATOR_UTILS.relativeLifetime(
                this.op, Math.max(op.expiryOrElse(Timestamp.DEFAULT).seconds(), currentExpiry));
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
        return ((op.expiryOrElse(Timestamp.DEFAULT).seconds() > 0) ? AMOUNT_REPR_BYTES : 0)
                + ((op.autoRenewPeriodOrElse(Duration.DEFAULT).seconds() > 0) ? AMOUNT_REPR_BYTES : 0)
                + (op.hasTreasury() ? BASIC_ENTITY_ID_SIZE : 0)
                + (op.hasAutoRenewAccount() ? BASIC_ENTITY_ID_SIZE : 0);
    }

    private boolean removesAutoRenewAccount(final TokenUpdateTransactionBody op) {
        return op.hasAutoRenewAccount() && designatesAccountRemoval(op.autoRenewAccount());
    }

    private boolean designatesAccountRemoval(final AccountID id) {
        return id.shardNum() == 0
                && id.realmNum() == 0
                && id.accountNumOrElse(0L) == 0
                && id.aliasOrElse(Bytes.EMPTY).length() == 0;
    }

    private void updateCurrentRb(final long amount) {
        currentMutableRb += amount;
    }
}

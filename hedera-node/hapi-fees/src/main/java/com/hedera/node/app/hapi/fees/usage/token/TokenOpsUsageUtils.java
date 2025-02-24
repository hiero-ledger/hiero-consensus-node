// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static com.hedera.node.app.hapi.fees.usage.EstimatorUtils.MAX_ENTITY_LIFETIME;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.token.TokenWipeAccountTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenBurnMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenCreateMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenCreateMeta.Builder;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenFreezeMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenMintMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenPauseMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenUnfreezeMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenUnpauseMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenWipeMeta;
import java.util.function.Function;
import java.util.function.Predicate;

public enum TokenOpsUsageUtils {
    TOKEN_OPS_USAGE_UTILS;

    private static final int AMOUNT_REPR_BYTES = 8;

    public TokenCreateMeta tokenCreateUsageFrom(final TransactionBody txn) {
        final var baseSize = getTokenTxnBaseSize(txn);

        final var op = txn.tokenCreation();
        var lifetime = op.hasAutoRenewAccount()
                ? op.autoRenewPeriod().seconds()
                : ESTIMATOR_UTILS.relativeLifetime(
                        txn, op.expiryOrElse(Timestamp.DEFAULT).seconds());
        lifetime = Math.min(lifetime, MAX_ENTITY_LIFETIME);

        final var tokenOpsUsage = new TokenOpsUsage();
        final var feeSchedulesSize = op.customFees().size() > 0 ? tokenOpsUsage.bytesNeededToRepr(op.customFees()) : 0;

        final SubType chosenType;
        final var usesCustomFees = op.hasFeeScheduleKey() || op.customFees().size() > 0;
        if (op.tokenType() == NON_FUNGIBLE_UNIQUE) {
            chosenType = usesCustomFees ? TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES : TOKEN_NON_FUNGIBLE_UNIQUE;
        } else {
            chosenType = usesCustomFees ? TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES : TOKEN_FUNGIBLE_COMMON;
        }

        return new Builder()
                .baseSize(baseSize)
                .lifeTime(lifetime)
                .customFeeScheleSize(feeSchedulesSize)
                .fungibleNumTransfers(op.initialSupply() > 0 ? 1 : 0)
                .nftsTranfers(0)
                .numTokens(1)
                .networkRecordRb(BASIC_ENTITY_ID_SIZE)
                .subType(chosenType)
                .build();
    }

    public TokenMintMeta tokenMintUsageFrom(
            final TransactionBody txn, final SubType subType, final long expectedLifeTime) {
        final var op = txn.tokenMint();
        int bpt = 0;
        long rbs = 0;
        int transferRecordRb = 0;
        if (subType == TOKEN_NON_FUNGIBLE_UNIQUE) {
            // bpt section in feeSchedules.json is manually modified to just use a constant price of $0.02
            // for each nft metadata
            bpt = op.metadata().size();
        } else {
            bpt = AMOUNT_REPR_BYTES;
            transferRecordRb = TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(1, 1, 0);
            bpt += BASIC_ENTITY_ID_SIZE;
        }
        return new TokenMintMeta(bpt, subType, transferRecordRb, rbs);
    }

    public TokenFreezeMeta tokenFreezeUsageFrom() {
        return new TokenFreezeMeta(2 * BASIC_ENTITY_ID_SIZE);
    }

    public TokenUnfreezeMeta tokenUnfreezeUsageFrom() {
        return new TokenUnfreezeMeta(2 * BASIC_ENTITY_ID_SIZE);
    }

    public TokenPauseMeta tokenPauseUsageFrom() {
        return new TokenPauseMeta(BASIC_ENTITY_ID_SIZE);
    }

    public TokenUnpauseMeta tokenUnpauseUsageFrom() {
        return new TokenUnpauseMeta(BASIC_ENTITY_ID_SIZE);
    }

    public TokenBurnMeta tokenBurnUsageFrom(final TransactionBody txn) {
        final var op = txn.tokenBurn();
        final var subType = op.serialNumbers().size() > 0 ? TOKEN_NON_FUNGIBLE_UNIQUE : TOKEN_FUNGIBLE_COMMON;
        return tokenBurnUsageFrom(txn, subType);
    }

    public TokenBurnMeta tokenBurnUsageFrom(final TransactionBody txn, final SubType subType) {
        final var op = txn.tokenBurn();
        return retrieveRawDataFrom(subType, op.serialNumbers().size(), TokenBurnMeta::new);
    }

    public TokenWipeMeta tokenWipeUsageFrom(final TransactionBody txn) {
        final var op = txn.tokenWipe();
        final var subType = op.serialNumbers().size() > 0 ? TOKEN_NON_FUNGIBLE_UNIQUE : TOKEN_FUNGIBLE_COMMON;
        return tokenWipeUsageFrom(op, subType);
    }

    public TokenWipeMeta tokenWipeUsageFrom(final TokenWipeAccountTransactionBody op) {
        final var subType = op.serialNumbers().size() > 0 ? TOKEN_NON_FUNGIBLE_UNIQUE : TOKEN_FUNGIBLE_COMMON;
        return tokenWipeUsageFrom(op, subType);
    }

    public TokenWipeMeta tokenWipeUsageFrom(final TokenWipeAccountTransactionBody op, final SubType subType) {
        return retrieveRawDataFrom(subType, op.serialNumbers().size(), TokenWipeMeta::new);
    }

    public <R> R retrieveRawDataFrom(
            final SubType subType, final int getDataForNFT, final TokenOpsProducer<R> producer) {
        int serialNumsCount = 0;
        int bpt = 0;
        int transferRecordRb = 0;
        if (subType == TOKEN_NON_FUNGIBLE_UNIQUE) {
            serialNumsCount = getDataForNFT;
            transferRecordRb = TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(1, 0, serialNumsCount);
            bpt = serialNumsCount * LONG_SIZE;
        } else {
            bpt = AMOUNT_REPR_BYTES;
            transferRecordRb = TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(1, 1, 0);
        }
        bpt += BASIC_ENTITY_ID_SIZE;

        return producer.create(bpt, subType, transferRecordRb, serialNumsCount);
    }

    public int getTokenTxnBaseSize(final TransactionBody txn) {
        final var op = txn.tokenCreation();

        final var tokenEntitySizes = TOKEN_ENTITY_SIZES;
        var baseSize = tokenEntitySizes.totalBytesInTokenReprGiven(op.symbol(), op.name());
        baseSize += keySizeIfPresent(op, TokenCreateTransactionBody::hasKycKey, TokenCreateTransactionBody::kycKey);
        baseSize += keySizeIfPresent(op, TokenCreateTransactionBody::hasWipeKey, TokenCreateTransactionBody::wipeKey);
        baseSize += keySizeIfPresent(op, TokenCreateTransactionBody::hasAdminKey, TokenCreateTransactionBody::adminKey);
        baseSize +=
                keySizeIfPresent(op, TokenCreateTransactionBody::hasSupplyKey, TokenCreateTransactionBody::supplyKey);
        baseSize +=
                keySizeIfPresent(op, TokenCreateTransactionBody::hasFreezeKey, TokenCreateTransactionBody::freezeKey);
        baseSize += keySizeIfPresent(
                op, TokenCreateTransactionBody::hasFeeScheduleKey, TokenCreateTransactionBody::feeScheduleKey);
        baseSize += keySizeIfPresent(op, TokenCreateTransactionBody::hasPauseKey, TokenCreateTransactionBody::pauseKey);
        baseSize += op.memo().getBytes().length;
        if (op.hasAutoRenewAccount()) {
            baseSize += BASIC_ENTITY_ID_SIZE;
        }
        return baseSize;
    }

    /**
     * Get the size of the key if it is present in the transaction body
     * @param body the body of the transaction
     * @param check the predicate to check if the key is present
     * @param getter the function to get the key
     * @return the size of the key if it is present, 0 otherwise
     * @param <T> the type of the body
     */
    public static <T> int keySizeIfPresent(final T body, final Predicate<T> check, final Function<T, Key> getter) {
        return check.test(body) ? getAccountKeyStorageSize(getter.apply(body)) : 0;
    }
}

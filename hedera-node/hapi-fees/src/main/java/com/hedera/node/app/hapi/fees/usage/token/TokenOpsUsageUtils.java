// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.usage.EstimatorUtils.MAX_ENTITY_LIFETIME;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.node.app.hapi.fees.usage.token.meta.TokenBurnMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenCreateMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenCreateMeta.Builder;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenFreezeMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenMintMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenPauseMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenUnfreezeMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenUnpauseMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenWipeMeta;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.TokenCreateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenWipeAccountTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

public enum TokenOpsUsageUtils {
    TOKEN_OPS_USAGE_UTILS;

    private static final int AMOUNT_REPR_BYTES = 8;

    public TokenCreateMeta tokenCreateUsageFrom(final TransactionBody txn) {
        final var baseSize = getTokenTxnBaseSize(txn);

        final var op = txn.getTokenCreation();
        var lifetime = op.hasAutoRenewAccount()
                ? op.getAutoRenewPeriod().getSeconds()
                : ESTIMATOR_UTILS.relativeLifetime(txn, op.getExpiry().getSeconds());
        lifetime = Math.min(lifetime, MAX_ENTITY_LIFETIME);

        final var tokenOpsUsage = new TokenOpsUsage();
        final var feeSchedulesSize =
                op.getCustomFeesCount() > 0 ? tokenOpsUsage.bytesNeededToRepr(op.getCustomFeesList()) : 0;

        final SubType chosenType;
        final var usesCustomFees = op.hasFeeScheduleKey() || op.getCustomFeesCount() > 0;
        if (op.getTokenType() == NON_FUNGIBLE_UNIQUE) {
            chosenType = usesCustomFees ? TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES : TOKEN_NON_FUNGIBLE_UNIQUE;
        } else {
            chosenType = usesCustomFees ? TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES : TOKEN_FUNGIBLE_COMMON;
        }

        return new Builder()
                .baseSize(baseSize)
                .lifeTime(lifetime)
                .customFeeScheleSize(feeSchedulesSize)
                .fungibleNumTransfers(op.getInitialSupply() > 0 ? 1 : 0)
                .nftsTranfers(0)
                .numTokens(1)
                .networkRecordRb(BASIC_ENTITY_ID_SIZE)
                .subType(chosenType)
                .build();
    }

    public TokenMintMeta tokenMintUsageFrom(
            final TransactionBody txn, final SubType subType, final long expectedLifeTime) {
        final var op = txn.getTokenMint();
        int bpt = 0;
        long rbs = 0;
        int transferRecordRb = 0;
        if (subType == TOKEN_NON_FUNGIBLE_UNIQUE) {
            // bpt section in feeSchedules.json is manually modified to just use a constant price of $0.02
            // for each nft metadata
            bpt = op.getMetadataList().size();
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
        final var op = txn.getTokenBurn();
        final var subType = op.getSerialNumbersCount() > 0 ? TOKEN_NON_FUNGIBLE_UNIQUE : TOKEN_FUNGIBLE_COMMON;
        return tokenBurnUsageFrom(txn, subType);
    }

    public TokenBurnMeta tokenBurnUsageFrom(final TransactionBody txn, final SubType subType) {
        final var op = txn.getTokenBurn();
        return retrieveRawDataFrom(subType, op::getSerialNumbersCount, TokenBurnMeta::new);
    }

    public TokenWipeMeta tokenWipeUsageFrom(final TransactionBody txn) {
        final var op = txn.getTokenWipe();
        final var subType = op.getSerialNumbersCount() > 0 ? TOKEN_NON_FUNGIBLE_UNIQUE : TOKEN_FUNGIBLE_COMMON;
        return tokenWipeUsageFrom(op, subType);
    }

    public TokenWipeMeta tokenWipeUsageFrom(final TokenWipeAccountTransactionBody op) {
        final var subType = op.getSerialNumbersCount() > 0 ? TOKEN_NON_FUNGIBLE_UNIQUE : TOKEN_FUNGIBLE_COMMON;
        return tokenWipeUsageFrom(op, subType);
    }

    public TokenWipeMeta tokenWipeUsageFrom(final TokenWipeAccountTransactionBody op, final SubType subType) {
        return retrieveRawDataFrom(subType, op::getSerialNumbersCount, TokenWipeMeta::new);
    }

    public <R> R retrieveRawDataFrom(
            final SubType subType, final IntSupplier getDataForNFT, final TokenOpsProducer<R> producer) {
        int serialNumsCount = 0;
        int bpt = 0;
        int transferRecordRb = 0;
        if (subType == TOKEN_NON_FUNGIBLE_UNIQUE) {
            serialNumsCount = getDataForNFT.getAsInt();
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
        final var op = txn.getTokenCreation();

        final var tokenEntitySizes = TOKEN_ENTITY_SIZES;
        var baseSize = tokenEntitySizes.totalBytesInTokenReprGiven(op.getSymbol(), op.getName());
        baseSize += keySizeIfPresent(op, TokenCreateTransactionBody::hasKycKey, TokenCreateTransactionBody::getKycKey);
        baseSize +=
                keySizeIfPresent(op, TokenCreateTransactionBody::hasWipeKey, TokenCreateTransactionBody::getWipeKey);
        baseSize +=
                keySizeIfPresent(op, TokenCreateTransactionBody::hasAdminKey, TokenCreateTransactionBody::getAdminKey);
        baseSize += keySizeIfPresent(
                op, TokenCreateTransactionBody::hasSupplyKey, TokenCreateTransactionBody::getSupplyKey);
        baseSize += keySizeIfPresent(
                op, TokenCreateTransactionBody::hasFreezeKey, TokenCreateTransactionBody::getFreezeKey);
        baseSize += keySizeIfPresent(
                op, TokenCreateTransactionBody::hasFeeScheduleKey, TokenCreateTransactionBody::getFeeScheduleKey);
        baseSize +=
                keySizeIfPresent(op, TokenCreateTransactionBody::hasPauseKey, TokenCreateTransactionBody::getPauseKey);
        baseSize += op.getMemoBytes().size();
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

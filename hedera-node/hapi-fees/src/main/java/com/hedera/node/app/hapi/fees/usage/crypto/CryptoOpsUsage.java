// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.getChangedCryptoKeys;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.getChangedTokenKeys;
import static com.hedera.node.app.hapi.fees.usage.crypto.entities.CryptoEntitySizes.CRYPTO_ENTITY_SIZES;
import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BOOL_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.CRYPTO_ALLOWANCE_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.NFT_ALLOWANCE_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.TOKEN_ALLOWANCE_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;

import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.EstimatorFactory;
import com.hedera.node.app.hapi.fees.usage.QueryUsage;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class CryptoOpsUsage {
    private static final long LONG_BASIC_ENTITY_ID_SIZE = BASIC_ENTITY_ID_SIZE;
    public static final long LONG_ACCOUNT_AMOUNT_BYTES = USAGE_PROPERTIES.accountAmountBytes();

    public static final long CREATE_SLOT_MULTIPLIER = 1228;
    public static final long UPDATE_SLOT_MULTIPLIER = 24000;

    public static EstimatorFactory txnEstimateFactory = TxnUsageEstimator::new;
    static Function<ResponseType, QueryUsage> queryEstimateFactory = QueryUsage::new;

    @Inject
    public CryptoOpsUsage() {
        // Default constructor
    }

    public void cryptoTransferUsage(
            final SigUsage sigUsage,
            final CryptoTransferMeta xferMeta,
            final BaseTransactionMeta baseMeta,
            final UsageAccumulator accumulator) {
        accumulator.resetForTransaction(baseMeta, sigUsage);

        final int tokenMultiplier = xferMeta.getTokenMultiplier();

        /* BPT calculations shouldn't include any custom fee payment usage */
        int totalXfers = baseMeta.numExplicitTransfers();
        int weightedTokensInvolved = tokenMultiplier * xferMeta.getNumTokensInvolved();
        int weightedTokenXfers = tokenMultiplier * xferMeta.getNumFungibleTokenTransfers();
        long incBpt = weightedTokensInvolved * LONG_BASIC_ENTITY_ID_SIZE;
        incBpt += (weightedTokenXfers + totalXfers) * LONG_ACCOUNT_AMOUNT_BYTES;
        incBpt += TOKEN_ENTITY_SIZES.bytesUsedForUniqueTokenTransfers(xferMeta.getNumNftOwnershipChanges());
        accumulator.addBpt(incBpt);

        totalXfers += xferMeta.getCustomFeeHbarTransfers();
        weightedTokenXfers += tokenMultiplier * xferMeta.getCustomFeeTokenTransfers();
        weightedTokensInvolved += tokenMultiplier * xferMeta.getCustomFeeTokensInvolved();
        long incRb = totalXfers * LONG_ACCOUNT_AMOUNT_BYTES;
        incRb += TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(
                weightedTokensInvolved, weightedTokenXfers, xferMeta.getNumNftOwnershipChanges());
        accumulator.addRbs(incRb * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    public FeeData cryptoInfoUsage(final Query cryptoInfoReq, final ExtantCryptoContext ctx) {
        final var op = cryptoInfoReq.getCryptoGetInfo();

        final var estimate = queryEstimateFactory.apply(op.getHeader().getResponseType());
        return getUsage(estimate, ctx);
    }

    public FeeData accountDetailsUsage(final Query accountDetailsReq, final ExtantCryptoContext ctx) {
        final var op = accountDetailsReq.getAccountDetails();

        final var estimate = queryEstimateFactory.apply(op.getHeader().getResponseType());
        return getUsage(estimate, ctx);
    }

    private FeeData getUsage(final QueryUsage estimate, final ExtantCryptoContext ctx) {
        estimate.addTb(BASIC_ENTITY_ID_SIZE);
        long extraRb = 0;
        extraRb += ctx.currentMemo().getBytes(StandardCharsets.UTF_8).length;
        extraRb += getAccountKeyStorageSize(ctx.currentKey());
        if (ctx.currentlyHasProxy()) {
            extraRb += BASIC_ENTITY_ID_SIZE;
        }
        extraRb += ctx.currentNumTokenRels() * TOKEN_ENTITY_SIZES.bytesUsedPerAccountRelationship();
        estimate.addRb(CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + extraRb);

        return estimate.get();
    }

    public long cryptoAutoRenewRb(final ExtantCryptoContext ctx) {
        return CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr()
                + ctx.currentNonBaseRb()
                + ctx.currentNumTokenRels() * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr();
    }

    /**
     * Returns the estimated resource usage for a crypto update transaction.
     *
     * @param sigUsage the already-computed resource usage for signature verification
     * @param baseMeta the already-computed resource usage for the base transaction
     * @param cryptoUpdateMeta metadata summarizing the update transaction
     * @param ctx the current state of the crypto account
     * @param accumulator the resource usage accumulator
     * @param explicitAutoAssocSlotLifetime a minimum lifetime to use for resource usage of new auto-renew slots
     */
    public void cryptoUpdateUsage(
            final SigUsage sigUsage,
            final BaseTransactionMeta baseMeta,
            final CryptoUpdateMeta cryptoUpdateMeta,
            final ExtantCryptoContext ctx,
            final UsageAccumulator accumulator,
            final long explicitAutoAssocSlotLifetime) {
        accumulator.resetForTransaction(baseMeta, sigUsage);

        accumulator.addBpt(cryptoUpdateMeta.getMsgBytesUsed());

        long newVariableBytes = 0;
        final var newMemoSize = cryptoUpdateMeta.getMemoSize();
        newVariableBytes += newMemoSize != 0 ? newMemoSize : ctx.currentMemo().getBytes(StandardCharsets.UTF_8).length;
        final var newKeyBytes = cryptoUpdateMeta.getKeyBytesUsed();
        newVariableBytes += newKeyBytes == 0 ? getAccountKeyStorageSize(ctx.currentKey()) : newKeyBytes;
        newVariableBytes += (cryptoUpdateMeta.hasProxy() || ctx.currentlyHasProxy()) ? BASIC_ENTITY_ID_SIZE : 0;

        final long tokenRelBytes = ctx.currentNumTokenRels() * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr();
        final long sharedFixedBytes = CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + tokenRelBytes;
        final long newLifetime =
                ESTIMATOR_UTILS.relativeLifetime(cryptoUpdateMeta.getEffectiveNow(), cryptoUpdateMeta.getExpiry());
        final long oldLifetime =
                ESTIMATOR_UTILS.relativeLifetime(cryptoUpdateMeta.getEffectiveNow(), ctx.currentExpiry());
        final long rbsDelta = ESTIMATOR_UTILS.changeInBsUsage(
                cryptoAutoRenewRb(ctx), oldLifetime, sharedFixedBytes + newVariableBytes, newLifetime);
        if (rbsDelta > 0) {
            accumulator.addRbs(rbsDelta);
        }

        final var oldSlotsUsage = ctx.currentMaxAutomaticAssociations() * UPDATE_SLOT_MULTIPLIER;
        final var newSlotsUsage = cryptoUpdateMeta.hasMaxAutomaticAssociations()
                ? cryptoUpdateMeta.getMaxAutomaticAssociations() * UPDATE_SLOT_MULTIPLIER
                : oldSlotsUsage;
        // If given an explicit auto-assoc slot lifetime, we use it as a lower bound for both old and new lifetimes
        final long slotRbsDelta = ESTIMATOR_UTILS.changeInBsUsage(
                oldSlotsUsage,
                Math.max(explicitAutoAssocSlotLifetime, oldLifetime),
                newSlotsUsage,
                Math.max(explicitAutoAssocSlotLifetime, newLifetime));
        if (slotRbsDelta > 0) {
            accumulator.addRbs(slotRbsDelta);
        }
    }

    public void cryptoCreateUsage(
            final SigUsage sigUsage,
            final BaseTransactionMeta baseMeta,
            final CryptoCreateMeta cryptoCreateMeta,
            final UsageAccumulator accumulator) {
        accumulator.resetForTransaction(baseMeta, sigUsage);

        var baseSize = cryptoCreateMeta.getBaseSize();

        final var maxAutomaticTokenAssociations = cryptoCreateMeta.getMaxAutomaticAssociations();

        final var lifeTime = cryptoCreateMeta.getLifeTime();

        if (maxAutomaticTokenAssociations > 0) {
            baseSize += INT_SIZE;
        }

        /* Variable bytes plus two additional longs for balance and auto-renew period;
        plus a boolean for receiver sig required. */
        accumulator.addBpt(baseSize + 2 * LONG_SIZE + BOOL_SIZE);
        accumulator.addRbs((CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + baseSize) * lifeTime);
        accumulator.addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    public void cryptoApproveAllowanceUsage(
            final SigUsage sigUsage,
            final BaseTransactionMeta baseMeta,
            final CryptoApproveAllowanceMeta cryptoApproveMeta,
            final ExtantCryptoContext ctx,
            final UsageAccumulator accumulator) {
        accumulator.resetForTransaction(baseMeta, sigUsage);
        accumulator.addBpt(cryptoApproveMeta.getMsgBytesUsed());

        final long lifeTime =
                ESTIMATOR_UTILS.relativeLifetime(cryptoApproveMeta.getEffectiveNow(), ctx.currentExpiry());
        // If the value is being adjusted instead of inserting a new entry , the fee charged will be
        // slightly less than
        // the base price
        final var adjustedBytes = getNewBytes(cryptoApproveMeta, ctx);
        if (adjustedBytes > 0) {
            accumulator.addRbs(adjustedBytes * lifeTime);
        }
    }

    public void cryptoDeleteAllowanceUsage(
            final SigUsage sigUsage,
            final BaseTransactionMeta baseMeta,
            final CryptoDeleteAllowanceMeta cryptoDeleteAllowanceMeta,
            final UsageAccumulator accumulator) {

        accumulator.resetForTransaction(baseMeta, sigUsage);
        accumulator.addBpt(cryptoDeleteAllowanceMeta.getMsgBytesUsed());
    }

    private long getNewBytes(final CryptoApproveAllowanceMeta cryptoApproveMeta, final ExtantCryptoContext ctx) {
        long newTotalBytes = 0;
        final var newCryptoKeys = getChangedCryptoKeys(
                cryptoApproveMeta.getCryptoAllowances().keySet(),
                ctx.currentCryptoAllowances().keySet());
        newTotalBytes += cryptoApproveMeta.getCryptoAllowancesCount() * CRYPTO_ALLOWANCE_SIZE;

        final var newTokenKeys = getChangedTokenKeys(
                cryptoApproveMeta.getTokenAllowances().keySet(),
                ctx.currentTokenAllowances().keySet());
        newTotalBytes += newTokenKeys * TOKEN_ALLOWANCE_SIZE;

        final var newApproveForAllNfts =
                getChangedTokenKeys(cryptoApproveMeta.getNftAllowances(), ctx.currentNftAllowances());
        newTotalBytes += newApproveForAllNfts * NFT_ALLOWANCE_SIZE;

        return newTotalBytes;
    }
}

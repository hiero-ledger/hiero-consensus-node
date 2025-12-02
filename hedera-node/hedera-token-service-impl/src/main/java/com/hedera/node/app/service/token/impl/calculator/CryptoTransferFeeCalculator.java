// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.ACCOUNTS;
import static org.hiero.hapi.support.fees.Extra.CRYPTO_TRANSFER_BASE_FUNGIBLE;
import static org.hiero.hapi.support.fees.Extra.CRYPTO_TRANSFER_BASE_FUNGIBLE_CUSTOM_FEES;
import static org.hiero.hapi.support.fees.Extra.CRYPTO_TRANSFER_BASE_NFT;
import static org.hiero.hapi.support.fees.Extra.CRYPTO_TRANSFER_BASE_NFT_CUSTOM_FEES;
import static org.hiero.hapi.support.fees.Extra.FUNGIBLE_TOKENS;
import static org.hiero.hapi.support.fees.Extra.HOOK_EXECUTION;
import static org.hiero.hapi.support.fees.Extra.HOOK_UPDATES;
import static org.hiero.hapi.support.fees.Extra.NON_FUNGIBLE_TOKENS;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashSet;
import java.util.Set;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.fees.FeeScheduleUtils;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/**
 * Calculates CryptoTransfer fees per HIP-1261.
 *
 * Uses transaction-type-specific base fees via CRYPTO_TRANSFER_BASE_* extras:
 * - CRYPTO_TRANSFER_BASE_FUNGIBLE: Base fee for fungible token transfers
 * - CRYPTO_TRANSFER_BASE_NFT: Base fee for NFT transfers
 * - CRYPTO_TRANSFER_BASE_FUNGIBLE_CUSTOM_FEES: Base fee for custom fee fungible tokens
 * - CRYPTO_TRANSFER_BASE_NFT_CUSTOM_FEES: Base fee for custom fee NFTs
 * - HOOK_EXECUTION: Base fee for transfers with hooks (highest tier)
 * - No extra charged for HBAR-only transfers (uses baseFee=0)
 *
 * Additional extras for items beyond included counts:
 * - HOOK_UPDATES: Per-hook execution fee
 * - ACCOUNTS: Number of unique accounts involved
 * - FUNGIBLE_TOKENS: Additional fungible token transfers
 * - NON_FUNGIBLE_TOKENS: Additional NFT transfers
 */
public class CryptoTransferFeeCalculator implements ServiceFeeCalculator {

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CRYPTO_TRANSFER;
    }

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final FeeContext feeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {

        final ReadableTokenStore tokenStore = feeContext.readableStore(ReadableTokenStore.class);
        final var op = txnBody.cryptoTransferOrThrow();
        final long numAccounts = countUniqueAccounts(op);
        final long numHooks = countHooks(op);
        final TokenCounts tokenCounts = analyzeTokenTransfers(op, tokenStore);

        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.CRYPTO_TRANSFER);

        final Extra transferType = determineTransferType(tokenCounts, numHooks);
        if (transferType != null) {
            feeResult.addServiceFee(1, serviceDef.baseFee());
            addExtraFeeWithIncludedCount(feeResult, transferType, feeSchedule, serviceDef, 1);
        }

        addExtraFeeWithIncludedCount(feeResult, HOOK_UPDATES, feeSchedule, serviceDef, numHooks);
        addExtraFeeWithIncludedCount(feeResult, ACCOUNTS, feeSchedule, serviceDef, numAccounts);
        // Count all fungible tokens together (standard + custom fee)
        final long totalFungible = tokenCounts.standardFungible() + tokenCounts.customFeeFungible();
        addExtraFeeWithIncludedCount(feeResult, FUNGIBLE_TOKENS, feeSchedule, serviceDef, totalFungible);
        // Count all NFTs together (standard + custom fee)
        final long totalNft = tokenCounts.standardNft() + tokenCounts.customFeeNft();
        addExtraFeeWithIncludedCount(feeResult, NON_FUNGIBLE_TOKENS, feeSchedule, serviceDef, totalNft);
    }

    /** Returns the CRYPTO_TRANSFER_BASE_* extra for base fee, or null for HBAR-only transfers. */
    @Nullable
    private Extra determineTransferType(@NonNull final TokenCounts tokenCounts, final long numHooks) {
        if (numHooks > 0) {
            return HOOK_EXECUTION;
        }
        if (tokenCounts.customFeeNft() > 0) {
            return CRYPTO_TRANSFER_BASE_NFT_CUSTOM_FEES;
        }
        if (tokenCounts.standardNft() > 0) {
            return CRYPTO_TRANSFER_BASE_NFT;
        }
        if (tokenCounts.customFeeFungible() > 0) {
            return CRYPTO_TRANSFER_BASE_FUNGIBLE_CUSTOM_FEES;
        }
        if (tokenCounts.standardFungible() > 0) {
            return CRYPTO_TRANSFER_BASE_FUNGIBLE;
        }
        return null;
    }

    /**
     * Counts all unique accounts involved in a CryptoTransfer transaction.
     */
    private long countUniqueAccounts(@NonNull final CryptoTransferTransactionBody op) {
        final Set<AccountID> accounts = new HashSet<>();
        op.transfersOrElse(TransferList.DEFAULT).accountAmounts().forEach(aa -> accounts.add(aa.accountIDOrThrow()));
        op.tokenTransfers().forEach(ttl -> {
            ttl.transfers().forEach(aa -> accounts.add(aa.accountIDOrThrow()));
            ttl.nftTransfers().forEach(nft -> {
                accounts.add(nft.senderAccountIDOrThrow());
                accounts.add(nft.receiverAccountIDOrThrow());
            });
        });
        return accounts.size();
    }

    /** Counts token transfers by type (standard vs custom fee, fungible vs NFT). */
    private TokenCounts analyzeTokenTransfers(
            @NonNull final CryptoTransferTransactionBody op, @NonNull final ReadableTokenStore tokenStore) {
        int standardFungible = 0;
        int standardNft = 0;
        int customFeeFungible = 0;
        int customFeeNft = 0;

        for (final var ttl : op.tokenTransfers()) {
            final var tokenId = ttl.tokenOrThrow();
            final var token = tokenStore.get(tokenId);
            final boolean hasCustomFees = !token.customFees().isEmpty();
            final boolean isFungible = token.tokenType() == TokenType.FUNGIBLE_COMMON;
            if (isFungible) {
                if (!ttl.transfers().isEmpty()) {
                    if (hasCustomFees) {
                        customFeeFungible += 1;
                    } else {
                        standardFungible += 1;
                    }
                }
            } else {
                final int nftCount = ttl.nftTransfers().size();
                if (hasCustomFees) {
                    customFeeNft += nftCount;
                } else {
                    standardNft += nftCount;
                }
            }
        }
        return new TokenCounts(standardFungible, standardNft, customFeeFungible, customFeeNft);
    }

    /** Adds extra fee for items exceeding the included count. */
    private void addExtraFeeWithIncludedCount(
            @NonNull final FeeResult result,
            @NonNull final Extra extra,
            @NonNull final FeeSchedule feeSchedule,
            @NonNull final ServiceFeeDefinition serviceDef,
            final long actualCount) {
        final var extraRef =
                serviceDef.extras().stream().filter(ref -> ref.name() == extra).findFirst();
        if (extraRef.isEmpty()) {
            return;
        }
        final long includedCount = extraRef.get().includedCount();
        if (actualCount <= includedCount) {
            return;
        }
        final long overage = actualCount - includedCount;
        final long unitFee = FeeScheduleUtils.lookupExtraFee(feeSchedule, extra).fee();
        result.addServiceFee(overage, unitFee);
    }

    /** Counts hooks across all transfers in the operation. */
    private long countHooks(@NonNull final CryptoTransferTransactionBody op) {
        long hookCount = 0;
        if (op.hasTransfers()) {
            for (final var aa : op.transfersOrThrow().accountAmounts()) {
                if (aa.hasPreTxAllowanceHook()) {
                    hookCount++;
                }
                if (aa.hasPrePostTxAllowanceHook()) {
                    hookCount++;
                }
            }
        }
        for (final var ttl : op.tokenTransfers()) {
            for (final var transfer : ttl.transfers()) {
                if (transfer.hasPreTxAllowanceHook() || transfer.hasPrePostTxAllowanceHook()) {
                    hookCount++;
                }
            }
            for (final var nft : ttl.nftTransfers()) {
                if (nft.hasPreTxSenderAllowanceHook() || nft.hasPrePostTxSenderAllowanceHook()) {
                    hookCount++;
                }
                if (nft.hasPreTxReceiverAllowanceHook() || nft.hasPrePostTxReceiverAllowanceHook()) {
                    hookCount++;
                }
            }
        }
        return hookCount;
    }

    /**
     * Record holding token transfer counts.
     */
    private record TokenCounts(int standardFungible, int standardNft, int customFeeFungible, int customFeeNft) {}
}

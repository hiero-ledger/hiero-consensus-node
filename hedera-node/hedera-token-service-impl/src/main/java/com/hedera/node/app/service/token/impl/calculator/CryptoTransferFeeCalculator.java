// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.ACCOUNTS;
import static org.hiero.hapi.support.fees.Extra.FUNGIBLE_TOKENS;
import static org.hiero.hapi.support.fees.Extra.HOOK_EXECUTION;
import static org.hiero.hapi.support.fees.Extra.NON_FUNGIBLE_TOKENS;
import static org.hiero.hapi.support.fees.Extra.TOKEN_TRANSFER_BASE;
import static org.hiero.hapi.support.fees.Extra.TOKEN_TRANSFER_BASE_CUSTOM_FEES;

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
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/**
 * Calculates CryptoTransfer fees per HIP-1261.
 *
 * <p>Fee tiers based on transfer type:
 * <ul>
 *   <li>HBAR-only transfers: Uses baseFee ($0.0001)
 *   <li>Token transfers (FT or NFT): TOKEN_TRANSFER_BASE ($0.001)
 *   <li>Token transfers with custom fees: TOKEN_TRANSFER_BASE_CUSTOM_FEES ($0.002)
 * </ul>
 *
 * <p>Additional extras for items beyond included counts:
 * <ul>
 *   <li>HOOK_EXECUTION: Per-hook invocation fee (prePost hooks count as 2 executions)
 *   <li>ACCOUNTS: Number of unique accounts involved
 *   <li>FUNGIBLE_TOKENS: Additional fungible token transfers
 *   <li>NON_FUNGIBLE_TOKENS: Additional NFT transfers
 * </ul>
 */
public class CryptoTransferFeeCalculator implements ServiceFeeCalculator {

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CRYPTO_TRANSFER;
    }

    @Override
    public void accumulateServiceFee(
            @NonNull TransactionBody txnBody, @NonNull FeeResult feeResult, @NonNull FeeSchedule feeSchedule) {
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.CRYPTO_TRANSFER);
        feeResult.addServiceBase(serviceDef.baseFee());
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
        feeResult.addServiceBase(serviceDef.baseFee());

        final Extra transferType = determineTransferType(tokenCounts);
        if (transferType != null) {
            addExtraFee(feeResult, serviceDef, transferType, feeSchedule, 1);
        }
        addExtraFee(feeResult, serviceDef, HOOK_EXECUTION, feeSchedule, numHooks);
        addExtraFee(feeResult, serviceDef, ACCOUNTS, feeSchedule, numAccounts);
        final long totalFungible = tokenCounts.standardFungible() + tokenCounts.customFeeFungible();
        addExtraFee(feeResult, serviceDef, FUNGIBLE_TOKENS, feeSchedule, totalFungible);
        final long totalNft = tokenCounts.standardNft() + tokenCounts.customFeeNft();
        addExtraFee(feeResult, serviceDef, NON_FUNGIBLE_TOKENS, feeSchedule, totalNft);
    }

    /**
     * Returns the TOKEN_TRANSFER_BASE extra for token transfers, or null for HBAR-only transfers.
     * A single base fee is charged regardless of whether the transfer includes FT, NFT, or both.
     */
    @Nullable
    private Extra determineTransferType(@NonNull final TokenCounts tokenCounts) {
        final boolean hasCustomFeeTokens = tokenCounts.customFeeNft() > 0 || tokenCounts.customFeeFungible() > 0;
        final boolean hasAnyTokens =
                hasCustomFeeTokens || tokenCounts.standardNft() > 0 || tokenCounts.standardFungible() > 0;

        if (hasCustomFeeTokens) {
            return TOKEN_TRANSFER_BASE_CUSTOM_FEES;
        }
        if (hasAnyTokens) {
            return TOKEN_TRANSFER_BASE;
        }
        return null; // HBAR-only (uses baseFee)
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

    /**
     * Counts token transfers by type (standard vs custom fee, fungible vs NFT).
     */
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

    /**
     * Counts hooks across all transfers in the operation.
     */
    private long countHooks(@NonNull final CryptoTransferTransactionBody op) {
        long hookCount = 0;
        if (op.hasTransfers()) {
            for (final var aa : op.transfersOrThrow().accountAmounts()) {
                if (aa.hasPreTxAllowanceHook()) {
                    hookCount++;
                }
                if (aa.hasPrePostTxAllowanceHook()) {
                    hookCount += 2; // Pre + Post = 2 executions
                }
            }
        }
        for (final var ttl : op.tokenTransfers()) {
            for (final var transfer : ttl.transfers()) {
                if (transfer.hasPreTxAllowanceHook()) {
                    hookCount++;
                }
                if (transfer.hasPrePostTxAllowanceHook()) {
                    hookCount += 2; // Pre + Post = 2 executions
                }
            }
            for (final var nft : ttl.nftTransfers()) {
                if (nft.hasPreTxSenderAllowanceHook()) {
                    hookCount++;
                }
                if (nft.hasPrePostTxSenderAllowanceHook()) {
                    hookCount += 2; // Pre + Post = 2 executions
                }
                if (nft.hasPreTxReceiverAllowanceHook()) {
                    hookCount++;
                }
                if (nft.hasPrePostTxReceiverAllowanceHook()) {
                    hookCount += 2; // Pre + Post = 2 executions
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

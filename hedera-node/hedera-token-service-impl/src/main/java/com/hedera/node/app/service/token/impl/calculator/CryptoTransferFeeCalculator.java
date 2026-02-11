// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler.getHookInfo;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.ACCOUNTS;
import static org.hiero.hapi.support.fees.Extra.FUNGIBLE_TOKENS;
import static org.hiero.hapi.support.fees.Extra.GAS;
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
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.config.data.ContractsConfig;
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
            @NonNull final TransactionBody txnBody,
            @NonNull SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {

        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.CRYPTO_TRANSFER);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
        final var op = txnBody.cryptoTransferOrThrow();
        final long numAccounts = countUniqueAccounts(op);
        addExtraFee(feeResult, serviceDef, ACCOUNTS, feeSchedule, numAccounts);
        final var hookInfo = getHookInfo(op);
        if (hookInfo.numHookInvocations() > 0) {
            final var config = simpleFeeContext.feeContext().configuration();
            // Avoid overflow in by clamping effective limit. Since we validate each hook dispatch can't
            // exceed maxGasPerSec downstream, we need to allow to charge upto maxGasPerSec * numHookInvocations
            final long effectiveGasLimit = Math.max(
                    0,
                    Math.min(
                            hookInfo.numHookInvocations()
                                    * config.getConfigData(ContractsConfig.class)
                                            .maxGasPerSec(),
                            hookInfo.totalGasLimitOfHooks()));
            addExtraFee(feeResult, serviceDef, HOOK_EXECUTION, feeSchedule, hookInfo.numHookInvocations());
            addExtraFee(feeResult, serviceDef, GAS, feeSchedule, effectiveGasLimit);
        }

        if (simpleFeeContext.feeContext() != null) {
            final ReadableTokenStore tokenStore = simpleFeeContext.feeContext().readableStore(ReadableTokenStore.class);
            final TokenCounts tokenCounts = analyzeTokenTransfers(op, tokenStore);

            final Extra transferType = determineTransferType(tokenCounts);
            if (transferType != null) {
                addExtraFee(feeResult, serviceDef, transferType, feeSchedule, 1);
            }

            final long totalFungible = tokenCounts.standardFungible() + tokenCounts.customFeeFungible();
            addExtraFee(feeResult, serviceDef, FUNGIBLE_TOKENS, feeSchedule, totalFungible);
            final long totalNft = tokenCounts.standardNft() + tokenCounts.customFeeNft();
            addExtraFee(feeResult, serviceDef, NON_FUNGIBLE_TOKENS, feeSchedule, totalNft);
        } else {
            for (final var ttl : op.tokenTransfers()) {
                var regular_count = ttl.transfers().size();
                addExtraFee(feeResult, serviceDef, FUNGIBLE_TOKENS, feeSchedule, regular_count);
                var nft_count = ttl.nftTransfers().size();
                addExtraFee(feeResult, serviceDef, NON_FUNGIBLE_TOKENS, feeSchedule, nft_count);
            }
        }
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
            // Skip if token doesn't exist - validation will happen at handle time
            // and properly return CONTRACT_REVERT_EXECUTED for contract calls
            if (token == null) {
                continue;
            }
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
     * Record holding token transfer counts.
     */
    private record TokenCounts(int standardFungible, int standardNft, int customFeeFungible, int customFeeNft) {}
}

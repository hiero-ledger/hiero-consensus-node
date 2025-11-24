// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.ACCOUNTS;
import static org.hiero.hapi.support.fees.Extra.CREATED_ACCOUNTS;
import static org.hiero.hapi.support.fees.Extra.CREATED_AUTO_ASSOCIATIONS;
import static org.hiero.hapi.support.fees.Extra.CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON;
import static org.hiero.hapi.support.fees.Extra.CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static org.hiero.hapi.support.fees.Extra.CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE;
import static org.hiero.hapi.support.fees.Extra.CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static org.hiero.hapi.support.fees.Extra.CRYPTO_TRANSFER_WITH_HOOKS;
import static org.hiero.hapi.support.fees.Extra.CUSTOM_FEE_FUNGIBLE_TOKENS;
import static org.hiero.hapi.support.fees.Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS;
import static org.hiero.hapi.support.fees.Extra.STANDARD_FUNGIBLE_TOKENS;
import static org.hiero.hapi.support.fees.Extra.STANDARD_NON_FUNGIBLE_TOKENS;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.CalculatorState;
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
 * Uses transaction-type-specific base fees via CRYPTO_TRANSFER_* extras:
 * - CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON: Base fee for fungible token transfers
 * - CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE: Base fee for NFT transfers
 * - CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES: Base fee for custom fee fungible tokens
 * - CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES: Base fee for custom fee NFTs
 * - CRYPTO_TRANSFER_WITH_HOOKS: Base fee for transfers with hooks (highest tier)
 * - No extra charged for HBAR-only transfers (uses baseFee=0)
 *
 * Additional extras for items beyond included counts:
 * - ACCOUNTS: Number of unique accounts involved
 * - STANDARD_FUNGIBLE_TOKENS: Additional fungible token transfers without custom fees
 * - STANDARD_NON_FUNGIBLE_TOKENS: Additional NFT transfers without custom fees
 * - CUSTOM_FEE_FUNGIBLE_TOKENS: Additional fungible token transfers with custom fees
 * - CUSTOM_FEE_NON_FUNGIBLE_TOKENS: Additional NFT transfers with custom fees
 * - CREATED_AUTO_ASSOCIATIONS: Token associations created automatically
 * - CREATED_ACCOUNTS: Hollow accounts created automatically
 */
public class CryptoTransferFeeCalculator implements ServiceFeeCalculator {

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CRYPTO_TRANSFER;
    }

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final CalculatorState calculatorState,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {

        final ReadableTokenStore tokenStore =
                calculatorState != null ? calculatorState.readableStore(ReadableTokenStore.class) : null;
        final ReadableAccountStore accountStore =
                calculatorState != null ? calculatorState.readableStore(ReadableAccountStore.class) : null;
        final ReadableTokenRelationStore tokenRelStore =
                calculatorState != null ? calculatorState.readableStore(ReadableTokenRelationStore.class) : null;

        final var op = txnBody.cryptoTransferOrThrow();
        final long numAccounts = countUniqueAccounts(op);
        TokenCounts tokenCounts = analyzeTokenTransfers(op, tokenStore);
        final long numCreatedAutoAssociations = predictAutoAssociations(op, tokenStore, accountStore, tokenRelStore);
        final long numCreatedAccounts = predictHollowAccounts(op, accountStore);

        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.CRYPTO_TRANSFER);
        feeResult.addServiceFee(1, serviceDef.baseFee());

        // Determine and charge transaction-type-specific base fee
        final Extra transferType = determineTransferType(op, tokenCounts);
        if (transferType != null) {
            addExtraFeeWithIncludedCount(feeResult, transferType, feeSchedule, serviceDef, 1);
        }

        // Charge for accounts and additional token movements beyond what's included
        addExtraFeeWithIncludedCount(feeResult, ACCOUNTS, feeSchedule, serviceDef, numAccounts);
        addExtraFeeWithIncludedCount(
                feeResult, STANDARD_FUNGIBLE_TOKENS, feeSchedule, serviceDef, tokenCounts.standardFungible());
        addExtraFeeWithIncludedCount(
                feeResult, STANDARD_NON_FUNGIBLE_TOKENS, feeSchedule, serviceDef, tokenCounts.standardNft());
        addExtraFeeWithIncludedCount(
                feeResult, CUSTOM_FEE_FUNGIBLE_TOKENS, feeSchedule, serviceDef, tokenCounts.customFeeFungible());
        addExtraFeeWithIncludedCount(
                feeResult, CUSTOM_FEE_NON_FUNGIBLE_TOKENS, feeSchedule, serviceDef, tokenCounts.customFeeNft());
        addExtraFeeWithIncludedCount(
                feeResult, CREATED_AUTO_ASSOCIATIONS, feeSchedule, serviceDef, numCreatedAutoAssociations);
        addExtraFeeWithIncludedCount(feeResult, CREATED_ACCOUNTS, feeSchedule, serviceDef, numCreatedAccounts);
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
     * Counts unique fungible tokens involved in the transfer.
     * Each unique token ID with at least one transfer counts as 1.
     */
    private int countUniqueFungibleTokens(@NonNull final CryptoTransferTransactionBody op) {
        return (int) op.tokenTransfers().stream()
                .filter(ttl -> !ttl.transfers().isEmpty())
                .count();
    }

    /**
     * Counts NFT transfers (used when we can't access state).
     */
    private int countNftTransfers(@NonNull final CryptoTransferTransactionBody op) {
        return op.tokenTransfers().stream()
                .mapToInt(ttl -> ttl.nftTransfers().size())
                .sum();
    }

    /**
     * Analyzes token transfers to distinguish between standard and custom fee tokens.
     * Counts each unique token involved, not individual AccountAmount entries.
     * For fungible tokens: Each unique token ID counts as 1 transfer
     * For NFTs: Each NFT serial counts as 1 transfer
     *
     * @param op the CryptoTransfer operation
     * @param tokenStore the token store, or null if unavailable
     * @return counts of each token transfer type
     */
    private TokenCounts analyzeTokenTransfers(
            @NonNull final CryptoTransferTransactionBody op, @Nullable final ReadableTokenStore tokenStore) {
        if (tokenStore == null) {
            return new TokenCounts(countUniqueFungibleTokens(op), countNftTransfers(op), 0, 0);
        }

        int standardFungible = 0;
        int standardNft = 0;
        int customFeeFungible = 0;
        int customFeeNft = 0;

        for (final var ttl : op.tokenTransfers()) {
            final var tokenId = ttl.tokenOrThrow();
            final var token = tokenStore.get(tokenId);

            if (token == null) {
                if (!ttl.transfers().isEmpty()) {
                    standardFungible += 1;
                }
                standardNft += ttl.nftTransfers().size();
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
     * Adds an extra fee with proper includedCount handling and total cost calculation.
     */
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
        final long totalCost = overage * unitFee;
        result.addServiceFee(overage, totalCost);
    }

    /**
     * Predicts how many auto-associations will be created during transfer execution.
     * Analyzes each token transfer recipient to determine if they already have the token associated.
     *
     * @param op the CryptoTransfer operation
     * @param tokenStore the token store, or null if unavailable
     * @param accountStore the account store, or null if unavailable
     * @param tokenRelStore the token relation store, or null if unavailable
     * @return predicted number of auto-associations that will be created
     */
    private long predictAutoAssociations(
            @NonNull final CryptoTransferTransactionBody op,
            @Nullable final ReadableTokenStore tokenStore,
            @Nullable final ReadableAccountStore accountStore,
            @Nullable final ReadableTokenRelationStore tokenRelStore) {
        if (tokenStore == null || accountStore == null || tokenRelStore == null) {
            return 0;
        }

        long predictedAutoAssociations = 0;

        for (final var ttl : op.tokenTransfers()) {
            final var tokenId = ttl.tokenOrThrow();
            final var token = tokenStore.get(tokenId);

            if (token == null || token.hasKycKey() || token.hasFreezeKey()) {
                continue;
            }

            final Set<AccountID> recipients = new HashSet<>();
            ttl.transfers().forEach(aa -> recipients.add(aa.accountIDOrThrow()));
            ttl.nftTransfers().forEach(nft -> recipients.add(nft.receiverAccountIDOrThrow()));

            for (final var recipientId : recipients) {
                final var account = accountStore.getAliasedAccountById(recipientId);
                if (account == null) {
                    continue;
                }

                final var tokenRel = tokenRelStore.get(recipientId, tokenId);
                if (tokenRel != null) {
                    continue;
                }

                final int maxAutoAssociations = account.maxAutoAssociations();
                final int usedAutoAssociations = account.usedAutoAssociations();

                if (maxAutoAssociations > 0 && usedAutoAssociations < maxAutoAssociations) {
                    predictedAutoAssociations++;
                }
            }
        }

        return predictedAutoAssociations;
    }

    /**
     * Predicts how many hollow accounts will be created during transfer execution.
     * Checks if transfers are being sent to aliases that don't correspond to existing accounts.
     *
     * @param op the CryptoTransfer operation
     * @param accountStore the account store, or null if unavailable
     * @return predicted number of hollow accounts that will be created
     */
    private long predictHollowAccounts(
            @NonNull final CryptoTransferTransactionBody op, @Nullable final ReadableAccountStore accountStore) {
        if (accountStore == null) {
            return 0;
        }

        long predictedHollowAccounts = 0;
        final Set<AccountID> checkedAccounts = new HashSet<>();

        for (final var accountAmount : op.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
            final var accountId = accountAmount.accountIDOrThrow();
            if (!accountId.hasAlias() || checkedAccounts.contains(accountId)) {
                continue;
            }
            checkedAccounts.add(accountId);
            final var account = accountStore.getAliasedAccountById(accountId);
            if (account == null) {
                predictedHollowAccounts++;
            }
        }

        for (final var ttl : op.tokenTransfers()) {
            for (final var accountAmount : ttl.transfers()) {
                final var accountId = accountAmount.accountIDOrThrow();
                if (!accountId.hasAlias() || checkedAccounts.contains(accountId)) {
                    continue;
                }
                checkedAccounts.add(accountId);
                final var account = accountStore.getAliasedAccountById(accountId);
                if (account == null) {
                    predictedHollowAccounts++;
                }
            }

            for (final var nft : ttl.nftTransfers()) {
                final var accountId = nft.receiverAccountIDOrThrow();
                if (!accountId.hasAlias() || checkedAccounts.contains(accountId)) {
                    continue;
                }
                checkedAccounts.add(accountId);
                final var account = accountStore.getAliasedAccountById(accountId);
                if (account == null) {
                    predictedHollowAccounts++;
                }
            }
        }

        return predictedHollowAccounts;
    }

    /**
     * Determines which CRYPTO_TRANSFER_* extra should be charged as the transaction's base fee.
     * The priority order ensures higher-tier transactions are charged appropriately:
     * 1. CRYPTO_TRANSFER_WITH_HOOKS (if hooks present)
     * 2. CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES (if custom fee NFTs)
     * 3. CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE (if standard NFTs)
     * 4. CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES (if custom fee fungible)
     * 5. CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON (if standard fungible)
     * 6. null (HBAR-only transfer, no transaction-type-specific base fee)
     *
     * @param op the CryptoTransfer operation
     * @param tokenCounts analyzed token counts
     * @return the Extra to use for base fee, or null for HBAR-only transfers
     */
    @Nullable
    private Extra determineTransferType(
            @NonNull final CryptoTransferTransactionBody op, @NonNull final TokenCounts tokenCounts) {
        // TODO: Check for hooks when CryptoTransferTransactionBody supports them
        // if (op.hasHooks()) {
        //     return CRYPTO_TRANSFER_WITH_HOOKS;
        // }

        // Check for NFTs with custom fees (highest priority after hooks)
        if (tokenCounts.customFeeNft() > 0) {
            return CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
        }

        // Check for standard NFTs
        if (tokenCounts.standardNft() > 0) {
            return CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE;
        }

        // Check for fungible tokens with custom fees
        if (tokenCounts.customFeeFungible() > 0) {
            return CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
        }

        // Check for standard fungible tokens
        if (tokenCounts.standardFungible() > 0) {
            return CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON;
        }

        // HBAR-only transfer - no transaction-type-specific base fee
        return null;
    }

    /**
     * Record holding token transfer counts.
     */
    private record TokenCounts(int standardFungible, int standardNft, int customFeeFungible, int customFeeNft) {}
}

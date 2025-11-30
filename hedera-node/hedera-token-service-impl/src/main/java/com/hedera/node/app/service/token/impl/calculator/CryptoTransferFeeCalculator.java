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
import static org.hiero.hapi.support.fees.Extra.HOOKS;
import static org.hiero.hapi.support.fees.Extra.STANDARD_FUNGIBLE_TOKENS;
import static org.hiero.hapi.support.fees.Extra.STANDARD_NON_FUNGIBLE_TOKENS;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
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

    /** Value indicating unlimited auto-associations for an account. */
    private static final int UNLIMITED_AUTO_ASSOCIATIONS = -1;

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
        final ReadableAccountStore accountStore = feeContext.readableStore(ReadableAccountStore.class);
        final ReadableTokenRelationStore tokenRelStore = feeContext.readableStore(ReadableTokenRelationStore.class);
        final var op = txnBody.cryptoTransferOrThrow();
        final long numAccounts = countUniqueAccounts(op);
        final long numHooks = countHooks(op);
        final TokenCounts tokenCounts = analyzeTokenTransfers(op, tokenStore);
        final long numCreatedAutoAssociations = predictAutoAssociations(op, tokenStore, accountStore, tokenRelStore);
        final long numCreatedAccounts = predictAutoAccountCreations(op, accountStore);

        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.CRYPTO_TRANSFER);

        final Extra transferType = determineTransferType(tokenCounts, numHooks);
        if (transferType != null) {
            feeResult.addServiceFee(1, serviceDef.baseFee());
            addExtraFeeWithIncludedCount(feeResult, transferType, feeSchedule, serviceDef, 1);
        }

        addExtraFeeWithIncludedCount(feeResult, HOOKS, feeSchedule, serviceDef, numHooks);
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

    /** Returns the CRYPTO_TRANSFER_* extra for base fee, or null for HBAR-only transfers. */
    @Nullable
    private Extra determineTransferType(@NonNull final TokenCounts tokenCounts, final long numHooks) {
        if (numHooks > 0) {
            return CRYPTO_TRANSFER_WITH_HOOKS;
        }
        if (tokenCounts.customFeeNft() > 0) {
            return CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
        }
        if (tokenCounts.standardNft() > 0) {
            return CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE;
        }
        if (tokenCounts.customFeeFungible() > 0) {
            return CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
        }
        if (tokenCounts.standardFungible() > 0) {
            return CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON;
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
            @NonNull final CryptoTransferTransactionBody op,
            @NonNull final ReadableTokenStore tokenStore) {
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

    /** Predicts auto-associations for recipients lacking existing token relations. */
    private long predictAutoAssociations(
            @NonNull final CryptoTransferTransactionBody op,
            @NonNull final ReadableTokenStore tokenStore,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ReadableTokenRelationStore tokenRelStore) {
        long predictedAutoAssociations = 0;

        for (final var ttl : op.tokenTransfers()) {
            final var tokenId = ttl.tokenOrThrow();
            final var token = tokenStore.get(tokenId);

            if (token.hasKycKey() || token.hasFreezeKey()) {
                continue;
            }

            for (final var recipientId : collectRecipients(ttl)) {
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

                if (canAutoAssociate(maxAutoAssociations, usedAutoAssociations)) {
                    predictedAutoAssociations++;
                }
            }
        }

        return predictedAutoAssociations;
    }

    /** Predicts auto-creations for alias-based transfers to non-existent accounts. */
    private long predictAutoAccountCreations(
            @NonNull final CryptoTransferTransactionBody op, @NonNull final ReadableAccountStore accountStore) {
        long count = 0;
        final Set<AccountID> checked = new HashSet<>();

        for (final var aa : op.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
            if (isNewAutoAccountCreation(aa.accountIDOrThrow(), checked, accountStore)) {
                count++;
            }
        }

        for (final var ttl : op.tokenTransfers()) {
            for (final var aa : ttl.transfers()) {
                if (isNewAutoAccountCreation(aa.accountIDOrThrow(), checked, accountStore)) {
                    count++;
                }
            }
            for (final var nft : ttl.nftTransfers()) {
                if (isNewAutoAccountCreation(nft.receiverAccountIDOrThrow(), checked, accountStore)) {
                    count++;
                }
            }
        }

        return count;
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

    /** Returns true if the account has remaining auto-association slots (or unlimited). */
    private boolean canAutoAssociate(final int maxAutoAssociations, final int usedAutoAssociations) {
        return maxAutoAssociations == UNLIMITED_AUTO_ASSOCIATIONS
                || (maxAutoAssociations > 0 && usedAutoAssociations < maxAutoAssociations);
    }

    /** Collects all recipient account IDs from fungible and NFT transfers. */
    private Set<AccountID> collectRecipients(@NonNull final TokenTransferList ttl) {
        final Set<AccountID> recipients = new HashSet<>();
        ttl.transfers().forEach(aa -> recipients.add(aa.accountIDOrThrow()));
        ttl.nftTransfers().forEach(nft -> recipients.add(nft.receiverAccountIDOrThrow()));
        return recipients;
    }

    /** Returns true if this alias will trigger auto-creation; updates checkedAccounts. */
    private boolean isNewAutoAccountCreation(
            @NonNull final AccountID accountId,
            @NonNull final Set<AccountID> checkedAccounts,
            @NonNull final ReadableAccountStore accountStore) {
        if (!accountId.hasAlias() || checkedAccounts.contains(accountId)) {
            return false;
        }
        checkedAccounts.add(accountId);
        return accountStore.getAliasedAccountById(accountId) == null;
    }

    /**
     * Record holding token transfer counts.
     */
    private record TokenCounts(int standardFungible, int standardNft, int customFeeFungible, int customFeeNft) {}
}

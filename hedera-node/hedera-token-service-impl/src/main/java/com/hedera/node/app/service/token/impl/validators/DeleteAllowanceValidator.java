// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_ALLOWANCES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FUNGIBLE_TOKEN_IN_NFT_ALLOWANCES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.CryptoDeleteAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftRemoveAllowance;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.data.HederaConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Validator for {@link CryptoDeleteAllowanceTransactionBody}.
 */
@Singleton
public class DeleteAllowanceValidator extends AllowanceValidator {
    /**
     * Constructs a {@link DeleteAllowanceValidator} instance.
     */
    @Inject
    public DeleteAllowanceValidator() {
        // Dagger Injection
    }

    /**
     * Validates all allowances provided in {@link CryptoDeleteAllowanceTransactionBody}.
     *
     * @param handleContext handle context
     * @param nftAllowances given nft serials allowances to remove
     * @param payerAccount payer for the transaction
     * @param accountStore account store
     */
    public void validate(
            final HandleContext handleContext,
            final List<NftRemoveAllowance> nftAllowances,
            final Account payerAccount,
            final ReadableAccountStore accountStore) {
        final var storeFactory = handleContext.storeFactory();
        final var tokenStore = storeFactory.readableStore(ReadableTokenStore.class);
        final var tokenRelStore = storeFactory.readableStore(ReadableTokenRelationStore.class);
        final var nftStore = storeFactory.readableStore(ReadableNftStore.class);
        final var hederaConfig = handleContext.configuration().getConfigData(HederaConfig.class);

        validateAllowancesCount(nftAllowances, hederaConfig);

        validateNftDeleteAllowances(
                nftAllowances,
                payerAccount,
                accountStore,
                tokenStore,
                tokenRelStore,
                nftStore,
                handleContext.expiryValidator());
    }

    /**
     * Validates all the {@link NftRemoveAllowance}s in the {@link
     * com.hederahashgraph.api.proto.java.CryptoDeleteAllowance} transaction.
     *
     * @param nftAllowances nft remove allowances
     * @param payerAccount payer for the txn
     * @param accountStore account store
     * @param tokenStore read only token store
     */
    private void validateNftDeleteAllowances(
            final List<NftRemoveAllowance> nftAllowances,
            final Account payerAccount,
            final ReadableAccountStore accountStore,
            final ReadableTokenStore tokenStore,
            final ReadableTokenRelationStore tokenRelStore,
            final ReadableNftStore nftStore,
            @NonNull final ExpiryValidator expiryValidator) {
        if (nftAllowances.isEmpty()) {
            return;
        }
        for (final var allowance : nftAllowances) {
            final var ownerId = allowance.ownerOrElse(AccountID.DEFAULT);
            // pureChecks() ensures that tokenId is not null
            final var tokenId = allowance.tokenIdOrThrow();
            final var serialNums = allowance.serialNumbers();

            // Paused tokens are OK here, so we only check for existence and deletion
            final Token token = tokenStore.get(allowance.tokenIdOrElse(TokenID.DEFAULT));
            validateTrue(token != null, INVALID_TOKEN_ID);
            validateTrue(!token.deleted(), TOKEN_WAS_DELETED);
            validateFalse(token.tokenType().equals(TokenType.FUNGIBLE_COMMON), FUNGIBLE_TOKEN_IN_NFT_ALLOWANCES);

            final var effectiveOwner = getEffectiveOwner(ownerId, payerAccount, accountStore, expiryValidator);

            final var relation = tokenRelStore.get(effectiveOwner.accountId(), token.tokenId());
            validateTrue(relation != null, TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);

            validateDeleteSerialNums(serialNums, tokenId, nftStore);
        }
    }

    private void validateDeleteSerialNums(
            final List<Long> serialNums, final TokenID tokenId, final ReadableNftStore nftStore) {
        validateFalse(serialNums.isEmpty(), EMPTY_ALLOWANCES);
        validateSerialNums(serialNums, tokenId, nftStore);
    }

    private void validateAllowancesCount(
            @NonNull final List<NftRemoveAllowance> nftAllowances, @NonNull final HederaConfig hederaConfig) {
        // each serial number of an NFT is considered as an allowance.
        // So for Nft allowances aggregated amount is considered for transaction limit calculation.
        // Number of serials will not be counted for allowance on account.
        validateTotalAllowancesPerTxn(aggregateNftDeleteAllowances(nftAllowances), hederaConfig);
    }

    /**
     * Gets sum of number of serials in the nft allowances. Considers duplicate serial numbers as
     * well.
     *
     * @param nftAllowances give nft allowances
     * @return number of serials
     */
    public static int aggregateNftDeleteAllowances(final List<NftRemoveAllowance> nftAllowances) {
        var count = 0;
        for (final var allowance : nftAllowances) {
            count += allowance.serialNumbers().size();
        }
        return count;
    }
}

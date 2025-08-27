// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.AMOUNT_EXCEEDS_TOKEN_MAX_SUPPLY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.DELEGATING_SPENDER_CANNOT_GRANT_APPROVE_FOR_ALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FUNGIBLE_TOKEN_IN_NFT_ALLOWANCES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALLOWANCE_SPENDER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_DELEGATING_SPENDER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NFT_IN_FUNGIBLE_TOKEN_ALLOWANCES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SPENDER_ACCOUNT_SAME_AS_OWNER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.TokenValidations.PERMIT_PAUSED;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenSupplyType;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountApprovalForAllAllowance;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.data.HederaConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class validates the {@link CryptoApproveAllowanceTransactionBody} transaction.
 */
@Singleton
public class ApproveAllowanceValidator extends AllowanceValidator {
    /**
     * Default constructor for Dagger injection.
     */
    @Inject
    public ApproveAllowanceValidator() {
        // Dagger
    }

    /**
     * Validates the {@link CryptoApproveAllowanceTransactionBody} transaction.
     * It validates the total number of allowances has not exceeded limit, all crypto allowances, fungible token
     * allowances and nft allowances.
     * @param context handle context
     * @param payerAccount payer account for the approveAllowance txn
     * @param accountStore readable account store
     */
    public void validate(
            @NonNull final HandleContext context, final Account payerAccount, final ReadableAccountStore accountStore) {
        // create stores and config from context
        final var storeFactory = context.storeFactory();
        final var tokenStore = storeFactory.readableStore(ReadableTokenStore.class);
        final var tokenRelStore = storeFactory.readableStore(ReadableTokenRelationStore.class);
        final var nftStore = storeFactory.readableStore(ReadableNftStore.class);
        final var hederaConfig = context.configuration().getConfigData(HederaConfig.class);

        final var txn = context.body();
        final var op = txn.cryptoApproveAllowanceOrThrow();

        final var cryptoAllowances = op.cryptoAllowances();
        final var tokenAllowances = op.tokenAllowances();
        final var nftAllowances = op.nftAllowances();

        // validate total count of allowances does not exceed the configured maximum
        validateAllowanceCount(cryptoAllowances, tokenAllowances, nftAllowances, hederaConfig);
        // validate all allowances
        final var expiryValidator = context.expiryValidator();
        validateCryptoAllowances(cryptoAllowances, payerAccount, accountStore, expiryValidator);
        validateFungibleTokenAllowances(
                tokenAllowances, payerAccount, accountStore, tokenStore, tokenRelStore, expiryValidator);
        validateNftAllowances(
                nftAllowances, payerAccount, accountStore, tokenStore, tokenRelStore, nftStore, expiryValidator);
    }

    /**
     * Validates the CryptoAllowances given in {@link CryptoApproveAllowanceTransactionBody}.
     * @param cryptoAllowances crypto allowances list
     * @param payer payer account for the approveAllowance txn
     * @param accountStore readable account store
     */
    void validateCryptoAllowances(
            @NonNull final List<CryptoAllowance> cryptoAllowances,
            @NonNull final Account payer,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ExpiryValidator expiryValidator) {
        for (final var allowance : cryptoAllowances) {
            final var owner = allowance.owner();
            final var spender = allowance.spenderOrThrow(); // validated in pure checks to not be null

            // check if owner specified in allowances exists.
            // If not set, owner will be treated as payer for the transaction
            final var effectiveOwner = getEffectiveOwner(owner, payer, accountStore, expiryValidator);
            // validate spender account
            final var spenderAccount = accountStore.getAccountById(spender);

            validateSpender(allowance.amount(), spenderAccount);
            validateFalse(spender.equals(effectiveOwner.accountId()), SPENDER_ACCOUNT_SAME_AS_OWNER);
        }
    }

    /**
     * Validates the FungibleTokenAllowances given in {@link CryptoApproveAllowanceTransactionBody}.
     * It validates the token exists, owner exists, spender exists, token amount is valid and token is not NFT.
     * @param tokenAllowances token allowances
     * @param payer payer account for approveAllowance txn
     * @param accountStore account store
     * @param tokenStore token store
     * @param tokenRelStore token relation store
     * @param expiryValidator expiry validator
     */
    private void validateFungibleTokenAllowances(
            final List<TokenAllowance> tokenAllowances,
            @NonNull final Account payer,
            final ReadableAccountStore accountStore,
            final ReadableTokenStore tokenStore,
            final ReadableTokenRelationStore tokenRelStore,
            @NonNull final ExpiryValidator expiryValidator) {
        for (final var allowance : tokenAllowances) {
            final var owner = allowance.owner();
            final var spender = allowance.spenderOrThrow();
            // pureChecks() ensures that tokenId is not null
            final var token = getIfUsable(allowance.tokenIdOrElse(TokenID.DEFAULT), tokenStore, PERMIT_PAUSED);

            // check if owner specified in allowances exists.
            // If not set, owner will be treated as payer for the transaction
            final var effectiveOwner = getEffectiveOwner(owner, payer, accountStore, expiryValidator);
            // validate spender account
            final var spenderAccount = accountStore.getAccountById(spender);
            validateTrue(TokenType.FUNGIBLE_COMMON.equals(token.tokenType()), NFT_IN_FUNGIBLE_TOKEN_ALLOWANCES);

            // validate token amount
            final var amount = allowance.amount();
            validateSpender(amount, spenderAccount);
            validateFalse(
                    TokenSupplyType.FINITE.equals(token.supplyType()) && amount > token.maxSupply(),
                    AMOUNT_EXCEEDS_TOKEN_MAX_SUPPLY);
            // validate
            validateTokenBasics(effectiveOwner, spender, token, tokenRelStore);
        }
    }

    /**
     * Validate nft allowances list in {@link CryptoApproveAllowanceTransactionBody}.
     * @param nftAllowancesList nft allowances
     * @param payer payer for approveAllowance txn
     * @param accountStore account store
     * @param tokenStore token store
     */
    private void validateNftAllowances(
            final List<NftAllowance> nftAllowancesList,
            @NonNull final Account payer,
            final ReadableAccountStore accountStore,
            final ReadableTokenStore tokenStore,
            final ReadableTokenRelationStore tokenRelStore,
            final ReadableNftStore uniqueTokenStore,
            @NonNull final ExpiryValidator expiryValidator) {
        for (final var allowance : nftAllowancesList) {
            final var owner = allowance.owner();
            final var spender = allowance.spenderOrThrow();
            // pureChecks() ensures that tokenId is not null
            final var tokenId = allowance.tokenIdOrThrow();
            final var serialNums = allowance.serialNumbers();

            final var token = getIfUsable(tokenId, tokenStore, PERMIT_PAUSED);
            validateFalse(TokenType.FUNGIBLE_COMMON.equals(token.tokenType()), FUNGIBLE_TOKEN_IN_NFT_ALLOWANCES);

            final var spenderAccount = accountStore.getAccountById(spender);

            if (Boolean.TRUE.equals(allowance.approvedForAll())) {
                validateNFTSpender(spenderAccount);
            } else {
                validateNFTSpender(serialNums, spenderAccount);
            }

            final var effectiveOwner = getEffectiveOwner(owner, payer, accountStore, expiryValidator);
            validateTokenBasics(effectiveOwner, spender, token, tokenRelStore);

            // If a spender has been given approveForAll privileges, then it has the same privileges as owner of NFT.
            // But, the spender is not allowed to grant approveForAll privileges to anyone else.
            if (allowance.hasDelegatingSpender()
                    && allowance.delegatingSpenderOrThrow().accountNumOrThrow() != 0) {

                // validate delegating spender
                getIfUsable(
                        allowance.delegatingSpenderOrThrow(),
                        accountStore,
                        expiryValidator,
                        INVALID_DELEGATING_SPENDER,
                        INVALID_DELEGATING_SPENDER,
                        TokenHandlerHelper.AccountIDType.ALIASED_ID);

                if (allowance.hasApprovedForAll()) {
                    validateFalse(
                            allowance.approvedForAll().booleanValue(), DELEGATING_SPENDER_CANNOT_GRANT_APPROVE_FOR_ALL);
                }
                final var approveForAllKey = AccountApprovalForAllAllowance.newBuilder()
                        .tokenId(tokenId)
                        .spenderId(allowance.delegatingSpender())
                        .build();
                validateTrue(
                        effectiveOwner.approveForAllNftAllowances().contains(approveForAllKey),
                        DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL);
            }

            validateSerialNums(serialNums, tokenId, uniqueTokenStore);
        }
    }

    /**
     * Validates the total number of allowances in the transaction. The total number of allowances should not exceed
     * the configured maximum. The total number of allowances includes the number of crypto allowances, the number of
     * fungible token allowances and the number of approvedForAll Nft allowances.
     * @param cryptoAllowances crypto allowances
     * @param tokenAllowances token allowances
     * @param nftAllowances nft allowances
     * @param hederaConfig hedera config
     */
    private void validateAllowanceCount(
            @NonNull final List<CryptoAllowance> cryptoAllowances,
            @NonNull final List<TokenAllowance> tokenAllowances,
            @NonNull final List<NftAllowance> nftAllowances,
            @NonNull final HederaConfig hederaConfig) {
        // each serial number of an NFT is considered as an allowance.
        // So for Nft allowances aggregated amount is considered for limit calculation.
        final var totalAllowances =
                cryptoAllowances.size() + tokenAllowances.size() + aggregateApproveNftAllowances(nftAllowances);
        validateTotalAllowancesPerTxn(totalAllowances, hederaConfig);
    }

    /**
     * Validates some of the token's basic fields like token type and token relation exists.
     * @param owner owner account
     * @param spender spender account
     * @param token token
     * @param tokenRelStore token relation store
     */
    private void validateTokenBasics(
            final Account owner,
            final AccountID spender,
            final Token token,
            final ReadableTokenRelationStore tokenRelStore) {
        final var ownerId = owner.accountId();
        final var tokenId = token.tokenId();
        // ONLY reject self-approval for NFT's; else allow to match OZ ERC-20
        validateFalse(
                token.tokenType() != TokenType.FUNGIBLE_COMMON
                        && owner.accountIdOrThrow().equals(spender),
                SPENDER_ACCOUNT_SAME_AS_OWNER);
        final var relation = tokenRelStore.get(ownerId, tokenId);
        validateTrue(relation != null, TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);
    }

    /**
     * Validates that either the amount to be approved is 0, or the spender account actually exists and has not been
     * deleted.
     *
     * @param amount If 0, then always valid. Otherwise, we check the spender account.
     * @param spenderAccount If the amount is not zero, then this must be non-null and not deleted.
     */
    private void validateSpender(final long amount, @Nullable final Account spenderAccount) {
        validateTrue(
                amount == 0 || (spenderAccount != null && !spenderAccount.deleted()), INVALID_ALLOWANCE_SPENDER_ID);
    }

    /**
     * Validates the NFT serial numbers and the spender account.
     * @param serialNumbers list of serial numbers
     * @param spenderAccount spender account
     */
    private void validateNFTSpender(final List<Long> serialNumbers, final Account spenderAccount) {
        validateTrue(
                serialNumbers.isEmpty() || (spenderAccount != null && !spenderAccount.deleted()),
                INVALID_ALLOWANCE_SPENDER_ID);
    }

    /**
     * Validates the spender account for NFT allowances.
     * @param spenderAccount spender account
     */
    private void validateNFTSpender(final Account spenderAccount) {
        validateTrue((spenderAccount != null && !spenderAccount.deleted()), INVALID_ALLOWANCE_SPENDER_ID);
    }
}

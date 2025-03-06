// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.util;

// SPDX-License-Identifier: Apache-2.0
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hedera.hapi.util.HapiUtils.EMPTY_KEY_LIST;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.TokenValidations.REQUIRE_NOT_PAUSED;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Class for retrieving objects in a certain context. For example, during a {@code handler.handle(...)} call.
 * This allows compartmentalizing common validation logic without requiring store implementations to
 * throw inappropriately-contextual exceptions, and also abstracts duplicated business logic out of
 * multiple handlers.
 */
public class TokenHandlerHelper {

    private TokenHandlerHelper() {
        throw new UnsupportedOperationException("Utility class only");
    }

    /**
     * Enum to determine the type of account ID, aliased or not aliased.
     */
    public enum AccountIDType {
        /**
         * Account ID is aliased.
         */
        ALIASED_ID,
        /**
         * Account ID is not aliased.
         */
        NOT_ALIASED_ID
    }

    /**
     * Returns the account if it exists and is usable. A {@link HandleException} is thrown if the account is invalid.
     * Note that this method should also work with account ID's that represent smart contracts.
     * If the account is deleted the return error code is ACCOUNT_DELETED.
     *
     * @param accountId the ID of the account to get
     * @param accountStore the {@link ReadableTokenStore} to use for account retrieval
     * @param expiryValidator the {@link ExpiryValidator} to determine if the account is expired
     * @param errorIfNotUsable the {@link ResponseCodeEnum} to use if the account is not found/usable
     * @return the account if it exists and is usable
     * @throws HandleException if any of the account conditions are not met
     */
    @NonNull
    public static Account getIfUsable(
            @NonNull final AccountID accountId,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ExpiryValidator expiryValidator,
            @NonNull final ResponseCodeEnum errorIfNotUsable) {
        return getIfUsable(
                accountId,
                accountStore,
                expiryValidator,
                errorIfNotUsable,
                ACCOUNT_DELETED,
                AccountIDType.NOT_ALIASED_ID);
    }

    /**
     * Returns the account if it exists and is usable. A {@link HandleException} is thrown if the account is invalid.
     * @param accountId the ID of the account to get
     * @param accountStore the {@link ReadableTokenStore} to use for account retrieval
     * @param expiryValidator the {@link ExpiryValidator} to determine if the account is expired
     * @param errorIfNotUsable the {@link ResponseCodeEnum} to use if the account is not found/usable
     * @param errorOnAccountDeleted the {@link ResponseCodeEnum} to use if the account is deleted
     * @param accountIDType the type of account ID
     * @return the account if it exists and is usable
     */
    @NonNull
    public static Account getIfUsable(
            @NonNull final AccountID accountId,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ExpiryValidator expiryValidator,
            @NonNull final ResponseCodeEnum errorIfNotUsable,
            @NonNull final ResponseCodeEnum errorOnAccountDeleted,
            @NonNull final AccountIDType accountIDType) {
        requireNonNull(accountId);
        requireNonNull(accountStore);
        requireNonNull(expiryValidator);
        requireNonNull(errorIfNotUsable);
        requireNonNull(errorOnAccountDeleted);

        final Account acct;
        if (accountIDType == AccountIDType.ALIASED_ID) {
            acct = accountStore.getAliasedAccountById(accountId);
        } else {
            acct = accountStore.getAccountById(accountId);
        }
        validateTrue(acct != null, errorIfNotUsable);
        final var isContract = acct.smartContract();

        validateFalse(acct.deleted(), errorOnAccountDeleted);
        final var type = isContract ? EntityType.CONTRACT_BYTECODE : EntityType.ACCOUNT;

        final var expiryStatus =
                expiryValidator.expirationStatus(type, acct.expiredAndPendingRemoval(), acct.tinybarBalance());
        validateTrue(expiryStatus == OK, expiryStatus);

        return acct;
    }

    /**
     * Returns the token if it exists and is usable. A {@link HandleException} is thrown if the token is invalid.
     * @param tokenId the ID of the token to get
     * @param tokenStore the {@link ReadableTokenStore} to use for token retrieval
     * @return the token if it exists and is usable
     */
    public static Token getIfUsable(@NonNull final TokenID tokenId, @NonNull final ReadableTokenStore tokenStore) {
        return getIfUsable(tokenId, tokenStore, REQUIRE_NOT_PAUSED);
    }

    /**
     * Returns the token if it exists and is usable. A {@link HandleException} is thrown if the token is invalid.
     *
     * @param tokenId the ID of the token to get
     * @param tokenStore the {@link ReadableTokenStore} to use for token retrieval
     * @param tokenValidations whether validate paused token status
     * @return the token if it exists and is usable
     * @throws HandleException if any of the token conditions are not met
     */
    @NonNull
    public static Token getIfUsable(
            @NonNull final TokenID tokenId,
            @NonNull final ReadableTokenStore tokenStore,
            @NonNull final TokenValidations tokenValidations) {
        return getIfUsable(tokenId, tokenStore, tokenValidations, null);
    }

    /**
     * Returns the token if it exists and is usable. A {@link HandleException} is thrown if the token is invalid.
     *
     * @param tokenId the ID of the token to get
     * @param tokenStore the {@link ReadableTokenStore} to use for token retrieval
     * @param tokenValidations whether validate paused token status
     * @param errorIfNotUsable the error response code, if token is not usable
     * @return the token if it exists and is usable
     * @throws HandleException if any of the token conditions are not met
     */
    @NonNull
    public static Token getIfUsable(
            @NonNull final TokenID tokenId,
            @NonNull final ReadableTokenStore tokenStore,
            @NonNull final TokenValidations tokenValidations,
            @Nullable final ResponseCodeEnum errorIfNotUsable) {
        requireNonNull(tokenId);
        requireNonNull(tokenStore);
        requireNonNull(tokenValidations);

        final var token = tokenStore.get(tokenId);
        validateTrue(token != null, errorIfNotUsable == null ? INVALID_TOKEN_ID : errorIfNotUsable);
        validateFalse(token.deleted(), errorIfNotUsable == null ? TOKEN_WAS_DELETED : errorIfNotUsable);
        if (tokenValidations == REQUIRE_NOT_PAUSED) {
            validateFalse(token.paused(), errorIfNotUsable == null ? TOKEN_IS_PAUSED : errorIfNotUsable);
        }
        return token;
    }

    /**
     * Returns the token relation if it exists and is usable.
     *
     * @param accountId the ID of the account
     * @param tokenId the ID of the token
     * @param tokenRelStore the {@link ReadableTokenRelationStore} to use for token relation retrieval
     * @return the token relation if it exists and is usable
     * @throws HandleException if any of the token relation conditions are not met
     */
    @NonNull
    public static TokenRelation getIfUsable(
            @NonNull final AccountID accountId,
            @NonNull final TokenID tokenId,
            @NonNull final ReadableTokenRelationStore tokenRelStore) {
        requireNonNull(accountId);
        requireNonNull(tokenId);
        requireNonNull(tokenRelStore);

        final var tokenRel = tokenRelStore.get(accountId, tokenId);

        validateTrue(tokenRel != null, TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);
        validateTrue(!tokenRel.frozen(), ACCOUNT_FROZEN_FOR_TOKEN);
        return tokenRel;
    }

    /**
     * Returns the NFT if it exists and is usable.
     * @param nftId the ID of the NFT
     * @param nftStore the {@link ReadableNftStore} to use for NFT retrieval
     * @return the NFT if it exists and is usable
     * @throws HandleException if any of the NFT conditions are not met
     */
    public static Nft getIfUsable(@NonNull final NftID nftId, @NonNull final ReadableNftStore nftStore) {
        requireNonNull(nftId);
        requireNonNull(nftStore);

        final var nft = nftStore.get(nftId);
        validateTrue(nft != null && nft.nftId() != null && nft.nftId().tokenId() != null, INVALID_NFT_ID);
        return nft;
    }

    /**
     * Returns the account if it exists and is usable. A {@link HandleException} is thrown if the account is invalid.
     * Note that this method should also work with account ID's that represent smart contracts
     * If the account is deleted the return error code is ACCOUNT_DELETED
     *
     * @param accountId the ID of the account to get
     * @param accountStore the {@link ReadableTokenStore} to use for account retrieval
     * @param expiryValidator the {@link ExpiryValidator} to determine if the account is expired
     * @param errorIfNotUsable the {@link ResponseCodeEnum} to use if the account is not found/usable
     * @return the account if it exists and is usable
     * @throws HandleException if any of the account conditions are not met
     */
    @NonNull
    public static Account getIfUsableForAliasedId(
            @NonNull final AccountID accountId,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ExpiryValidator expiryValidator,
            @NonNull final ResponseCodeEnum errorIfNotUsable) {
        return getIfUsable(
                accountId, accountStore, expiryValidator, errorIfNotUsable, ACCOUNT_DELETED, AccountIDType.ALIASED_ID);
    }

    /**
     * Returns the account if it exists and is usable. A {@link HandleException} is thrown if the account is invalid.
     * Note that this method should also work with account ID's that represent smart contracts
     * If the account is deleted the return error code is INVALID_AUTORENEW_ACCOUNT
     *
     * @param accountId the ID of the account to get
     * @param accountStore the {@link ReadableTokenStore} to use for account retrieval
     * @param expiryValidator the {@link ExpiryValidator} to determine if the account is expired
     * @param errorIfNotUsable the {@link ResponseCodeEnum} to use if the account is not found/usable
     * @return the account if it exists and is usable
     * @throws HandleException if any of the account conditions are not met
     */
    @NonNull
    public static Account getIfUsableForAutoRenew(
            @NonNull final AccountID accountId,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ExpiryValidator expiryValidator,
            @NonNull final ResponseCodeEnum errorIfNotUsable) {
        return getIfUsable(
                accountId,
                accountStore,
                expiryValidator,
                errorIfNotUsable,
                INVALID_AUTORENEW_ACCOUNT,
                AccountIDType.NOT_ALIASED_ID);
    }

    /**
     * Returns the account if it exists and is usable. A {@link HandleException} is thrown if the account is invalid.
     * Note that this method should also work with account ID's that represent smart contracts.
     * If the account is deleted the return error code is INVALID_TREASURY_ACCOUNT_FOR_TOKEN
     *
     * @param accountId the ID of the account to get
     * @param accountStore the {@link ReadableTokenStore} to use for account retrieval
     * @param expiryValidator the {@link ExpiryValidator} to determine if the account is expired
     * @param errorIfNotUsable the {@link ResponseCodeEnum} to use if the account is not found/usable
     * @return the account if it exists and is usable
     * @throws HandleException if any of the account conditions are not met
     */
    @NonNull
    public static Account getIfUsableWithTreasury(
            @NonNull final AccountID accountId,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ExpiryValidator expiryValidator,
            @NonNull final ResponseCodeEnum errorIfNotUsable) {
        return getIfUsable(
                accountId,
                accountStore,
                expiryValidator,
                errorIfNotUsable,
                INVALID_TREASURY_ACCOUNT_FOR_TOKEN,
                AccountIDType.NOT_ALIASED_ID);
    }

    /**
     * Enum to determine the type of validations to be performed on the token. If the token is allowed to be paused
     * or not
     */
    public enum TokenValidations {
        /**
         * Token should not be paused.
         */
        REQUIRE_NOT_PAUSED,
        /**
         * Token can be paused.
         */
        PERMIT_PAUSED
    }

    /**
     * Returns the token relation if it exists and is usable.
     * @param key the key to check
     * @param responseCode the response code to throw if the key is empty
     * @throws PreCheckException if the key is empty
     */
    public static void verifyNotEmptyKey(@Nullable final Key key, @NonNull final ResponseCodeEnum responseCode)
            throws PreCheckException {
        if (EMPTY_KEY_LIST.equals(key)) {
            throw new PreCheckException(responseCode);
        }
    }
}

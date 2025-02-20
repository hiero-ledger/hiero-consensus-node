// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.util;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenHandlerHelperTest {
    private static final AccountID ACCT_2300 =
            AccountID.newBuilder().accountNum(2300L).build();
    private static final TokenID TOKEN_ID_45 = TokenID.newBuilder().tokenNum(45).build();
    private static final NftID NFT_ID =
            NftID.newBuilder().tokenId(TOKEN_ID_45).serialNumber(123).build();
    protected final Timestamp consensusTimestamp =
            Timestamp.newBuilder().seconds(1_234_567L).build();

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ReadableTokenStore tokenStore;

    @Mock
    private ReadableTokenRelationStore tokenRelStore;

    @Mock
    private ReadableNftStore nftStore;

    @Mock
    private ExpiryValidator expiryValidator;

    @SuppressWarnings("DataFlowIssue")
    @Test
    void account_getIfUsable_nullArg() {
        Assertions.assertThatThrownBy(
                        () -> TokenHandlerHelper.getIfUsable(null, accountStore, expiryValidator, INVALID_ACCOUNT_ID))
                .isInstanceOf(NullPointerException.class);

        final var acctId = ACCT_2300;
        Assertions.assertThatThrownBy(
                        () -> TokenHandlerHelper.getIfUsable(acctId, null, expiryValidator, INVALID_ACCOUNT_ID))
                .isInstanceOf(NullPointerException.class);

        Assertions.assertThatThrownBy(
                        () -> TokenHandlerHelper.getIfUsable(acctId, accountStore, null, INVALID_ACCOUNT_ID))
                .isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() -> TokenHandlerHelper.getIfUsable(acctId, accountStore, expiryValidator, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void account_getIfUsable_nullAccount() {
        given(accountStore.getAccountById(notNull())).willReturn(null);

        Assertions.assertThatThrownBy(() ->
                        TokenHandlerHelper.getIfUsable(ACCT_2300, accountStore, expiryValidator, INVALID_ACCOUNT_ID))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ACCOUNT_ID));
    }

    @Test
    void account_getIfUsable_deletedAccount() {
        given(accountStore.getAccountById(notNull()))
                .willReturn(Account.newBuilder()
                        .accountId(ACCT_2300)
                        .tinybarBalance(0L)
                        .deleted(true)
                        .build());

        Assertions.assertThatThrownBy(() ->
                        TokenHandlerHelper.getIfUsable(ACCT_2300, accountStore, expiryValidator, INVALID_ACCOUNT_ID))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.ACCOUNT_DELETED));
    }

    @Test
    void account_getIfUsable_expiredAndPendingRemovalAccount() {
        given(accountStore.getAccountById(notNull()))
                .willReturn(Account.newBuilder()
                        .accountId(ACCT_2300)
                        .tinybarBalance(0L)
                        .deleted(false)
                        .smartContract(false)
                        .expiredAndPendingRemoval(true)
                        .build());
        given(expiryValidator.expirationStatus(notNull(), anyBoolean(), anyLong()))
                .willReturn(ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);
        Assertions.assertThatThrownBy(() ->
                        TokenHandlerHelper.getIfUsable(ACCT_2300, accountStore, expiryValidator, INVALID_ACCOUNT_ID))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL));
    }

    @Test
    void account_getIfUsable_accountTypeIsExpired() {
        given(accountStore.getAccountById(notNull()))
                .willReturn(Account.newBuilder()
                        .accountId(ACCT_2300)
                        .tinybarBalance(0L)
                        .deleted(false)
                        .smartContract(false)
                        .expiredAndPendingRemoval(false)
                        .build());
        given(expiryValidator.expirationStatus(notNull(), anyBoolean(), anyLong()))
                .willReturn(ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);

        Assertions.assertThatThrownBy(() ->
                        TokenHandlerHelper.getIfUsable(ACCT_2300, accountStore, expiryValidator, INVALID_ACCOUNT_ID))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL));
    }

    @Test
    void contract_getIfUsable_contractTypeIsExpired() {
        given(accountStore.getAccountById(notNull()))
                .willReturn(Account.newBuilder()
                        .accountId(ACCT_2300)
                        .tinybarBalance(0L)
                        .deleted(false)
                        .smartContract(true)
                        .expiredAndPendingRemoval(false)
                        .build());
        given(expiryValidator.expirationStatus(notNull(), anyBoolean(), anyLong()))
                .willReturn(ResponseCodeEnum.CONTRACT_EXPIRED_AND_PENDING_REMOVAL);

        Assertions.assertThatThrownBy(() ->
                        TokenHandlerHelper.getIfUsable(ACCT_2300, accountStore, expiryValidator, INVALID_ACCOUNT_ID))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.CONTRACT_EXPIRED_AND_PENDING_REMOVAL));
    }

    @Test
    void account_getIfUsable_usableAccount() {
        given(accountStore.getAccountById(notNull()))
                .willReturn(Account.newBuilder()
                        .accountId(ACCT_2300)
                        .tinybarBalance(0L)
                        .deleted(false)
                        .smartContract(false)
                        .expiredAndPendingRemoval(false)
                        .build());

        given(expiryValidator.expirationStatus(notNull(), anyBoolean(), anyLong()))
                .willReturn(ResponseCodeEnum.OK);

        final var result = TokenHandlerHelper.getIfUsable(ACCT_2300, accountStore, expiryValidator, INVALID_ACCOUNT_ID);
        Assertions.assertThat(result).isNotNull();
    }

    @Test
    void contract_getIfUsable_usableContract() {
        given(accountStore.getAccountById(notNull()))
                .willReturn(Account.newBuilder()
                        .accountId(ACCT_2300)
                        .tinybarBalance(0L)
                        .deleted(false)
                        .smartContract(true)
                        .expiredAndPendingRemoval(false)
                        .build());

        given(expiryValidator.expirationStatus(notNull(), anyBoolean(), anyLong()))
                .willReturn(ResponseCodeEnum.OK);

        final var result = TokenHandlerHelper.getIfUsable(ACCT_2300, accountStore, expiryValidator, INVALID_ACCOUNT_ID);
        Assertions.assertThat(result).isNotNull();
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void token_getIfUsable_nullArg() {
        Assertions.assertThatThrownBy(() -> TokenHandlerHelper.getIfUsable(null, tokenStore))
                .isInstanceOf(NullPointerException.class);

        Assertions.assertThatThrownBy(() -> getIfUsable(TOKEN_ID_45, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void token_getIfUsable_nullToken() {
        given(tokenStore.get(notNull())).willReturn(null);

        Assertions.assertThatThrownBy(() -> getIfUsable(TOKEN_ID_45, tokenStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOKEN_ID));
    }

    @Test
    void token_getIfUsable_deletedToken() {
        given(tokenStore.get(notNull()))
                .willReturn(Token.newBuilder()
                        .tokenId(TOKEN_ID_45)
                        .deleted(true)
                        .paused(false)
                        .build());

        Assertions.assertThatThrownBy(() -> getIfUsable(TOKEN_ID_45, tokenStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.TOKEN_WAS_DELETED));
    }

    @Test
    void token_getIfUsable_pausedToken() {
        given(tokenStore.get(notNull()))
                .willReturn(Token.newBuilder()
                        .tokenId(TOKEN_ID_45)
                        .deleted(false)
                        .paused(true)
                        .build());

        Assertions.assertThatThrownBy(() -> getIfUsable(TOKEN_ID_45, tokenStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.TOKEN_IS_PAUSED));
    }

    @Test
    void token_getIfUsable_usableToken() {
        given(tokenStore.get(notNull()))
                .willReturn(Token.newBuilder()
                        .tokenId(TOKEN_ID_45)
                        .deleted(false)
                        .paused(false)
                        .build());

        final var result = getIfUsable(TOKEN_ID_45, tokenStore);
        Assertions.assertThat(result).isNotNull();
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void tokenRel_getIfUsable_nullArg() {
        Assertions.assertThatThrownBy(() -> TokenHandlerHelper.getIfUsable(null, TOKEN_ID_45, tokenRelStore))
                .isInstanceOf(NullPointerException.class);

        Assertions.assertThatThrownBy(() -> getIfUsable(ACCT_2300, null, tokenRelStore))
                .isInstanceOf(NullPointerException.class);

        Assertions.assertThatThrownBy(() -> getIfUsable(ACCT_2300, TOKEN_ID_45, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void tokenRel_getIfUsable_notFound() {
        Assertions.assertThatThrownBy(() -> getIfUsable(ACCT_2300, TOKEN_ID_45, tokenRelStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
    }

    @Test
    void tokenRel_getIfUsable_usableTokenRel() {
        given(tokenRelStore.get(notNull(), notNull()))
                .willReturn(TokenRelation.newBuilder()
                        .accountId(ACCT_2300)
                        .tokenId(TOKEN_ID_45)
                        .balance(0)
                        .build());

        final var result = getIfUsable(ACCT_2300, TOKEN_ID_45, tokenRelStore);
        Assertions.assertThat(result).isNotNull();
    }

    @Test
    void nft_getIfUsable_usableNft() {
        given(nftStore.get(notNull()))
                .willReturn(Nft.newBuilder()
                        .nftId(NFT_ID)
                        .ownerId(ACCT_2300)
                        .mintTime(consensusTimestamp)
                        .metadata(Bytes.wrap(("apple")))
                        .build());

        final var result = getIfUsable(NFT_ID, nftStore);
        Assertions.assertThat(result).isNotNull();
    }

    @Test
    void nft_getIfUsable_nullTokenID() {
        given(nftStore.get(notNull()))
                .willReturn(Nft.newBuilder()
                        .nftId(NftID.newBuilder().build())
                        .ownerId(ACCT_2300)
                        .mintTime(consensusTimestamp)
                        .metadata(Bytes.wrap(("apple")))
                        .build());

        Assertions.assertThatThrownBy(() -> getIfUsable(NFT_ID, nftStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_NFT_ID));
    }
}

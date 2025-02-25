// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.MISC_ACCOUNT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_WIPE_KT;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.TokenRevokeKycTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenRevokeKycFromAccountHandler;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenRevokeKycFromAccountHandlerTest {

    private static final AccountID PBJ_PAYER_ID =
            AccountID.newBuilder().accountNum(3).build();
    private static final TokenID TOKEN_10 = TokenID.newBuilder().tokenNum(10).build();
    private static final AccountID ACCOUNT_100 =
            AccountID.newBuilder().accountNum(100).build();

    private TokenRevokeKycFromAccountHandler subject;

    @Mock
    private PureChecksContext pureChecksContext;

    @BeforeEach
    void setUp() {
        subject = new TokenRevokeKycFromAccountHandler();
    }

    @Nested
    class PreHandleTests {
        @Test
        @DisplayName("When op token ID is null, tokenOrThrow throws an exception")
        void nullTokenIdThrowsException() {
            final var txn = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder().accountID(PBJ_PAYER_ID))
                    .tokenRevokeKyc(TokenRevokeKycTransactionBody.newBuilder()
                            .token((TokenID) null)
                            .account(AccountID.newBuilder().accountNum(MISC_ACCOUNT.getAccountNum()))
                            .build())
                    .build();
            given(pureChecksContext.body()).willReturn(txn);

            assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_TOKEN_ID);
        }

        @Test
        @DisplayName("When op account ID is null, accountOrThrow throws an exception")
        void nullAccountIdThrowsException() {
            final var txn = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder().accountID(PBJ_PAYER_ID))
                    .tokenRevokeKyc(TokenRevokeKycTransactionBody.newBuilder()
                            .token(TOKEN_10)
                            .account((AccountID) null)
                            .build())
                    .build();
            given(pureChecksContext.body()).willReturn(txn);

            assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_ACCOUNT_ID);
        }
    }

    @Nested
    class HandleTests {

        @Mock(strictness = LENIENT)
        private HandleContext handleContext;

        @Mock
        private WritableTokenRelationStore tokenRelStore;

        @Mock
        private ReadableTokenStore readableTokenStore;

        @Mock
        private ReadableAccountStore readableAccountStore;

        @Mock
        private ExpiryValidator expiryValidator;

        @Mock(strictness = LENIENT)
        private StoreFactory storeFactory;

        private static final AccountID TREASURY_ACCOUNT_9876 = BaseCryptoHandler.asAccount(0L, 0L, 9876);

        private static final Token newToken10 = Token.newBuilder()
                .tokenId(TOKEN_10)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .treasuryAccountId(TREASURY_ACCOUNT_9876)
                .wipeKey(TOKEN_WIPE_KT.asPbjKey())
                .totalSupply(1000L)
                .build();

        @BeforeEach
        void setUp() {
            given(handleContext.storeFactory()).willReturn(storeFactory);
            given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(tokenRelStore);
            given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(readableTokenStore);
            given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
            given(handleContext.expiryValidator()).willReturn(expiryValidator);
        }

        @Test
        @DisplayName("Any null input argument should throw an exception")
        @SuppressWarnings("DataFlowIssue")
        void nullArgsThrowException() {
            assertThatThrownBy(() -> subject.handle(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("When op tokenRevokeKyc is null, tokenRevokeKycOrThrow throws an " + "exception")
        void nullTokenRevokeKycThrowsException() {
            final var txnBody = TransactionBody.newBuilder().build();
            given(handleContext.body()).willReturn(txnBody);

            assertThatThrownBy(() -> subject.handle(handleContext)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("When get returns empty, should not put or commit")
        void emptyGetShouldNotPersist() {
            given(readableAccountStore.getAccountById(ACCOUNT_100))
                    .willReturn(Account.newBuilder().accountId(ACCOUNT_100).build());
            given(readableTokenStore.get(TOKEN_10)).willReturn(newToken10);
            given(tokenRelStore.get(notNull(), notNull())).willReturn(null);
            given(expiryValidator.expirationStatus(EntityType.ACCOUNT, false, 0))
                    .willReturn(OK);

            final var txnBody = newTxnBody();
            given(handleContext.body()).willReturn(txnBody);

            assertThatThrownBy(() -> subject.handle(handleContext))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));

            verify(tokenRelStore, never()).put(any(TokenRelation.class));
        }

        @Test
        @DisplayName("When the token is paused, tokenRevokeKycOrThrow throws an exception")
        void tokenIsPaused() {
            given(readableAccountStore.getAccountById(ACCOUNT_100))
                    .willReturn(Account.newBuilder().accountId(ACCOUNT_100).build());
            given(readableTokenStore.get(TOKEN_10))
                    .willReturn(newToken10.copyBuilder().paused(true).build());
            given(expiryValidator.expirationStatus(EntityType.ACCOUNT, false, 0))
                    .willReturn(OK);

            final var txnBody = newTxnBody();
            given(handleContext.body()).willReturn(txnBody);

            assertThatThrownBy(() -> subject.handle(handleContext))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_IS_PAUSED));

            verify(tokenRelStore, never()).put(any(TokenRelation.class));
        }

        @Test
        @DisplayName("When the token is deleted, tokenRevokeKycOrThrow throws an exception")
        void tokenIsDeleted() {
            given(readableAccountStore.getAccountById(ACCOUNT_100))
                    .willReturn(Account.newBuilder().accountId(ACCOUNT_100).build());
            given(readableTokenStore.get(TOKEN_10))
                    .willReturn(newToken10.copyBuilder().deleted(true).build());
            given(expiryValidator.expirationStatus(EntityType.ACCOUNT, false, 0))
                    .willReturn(OK);

            final var txnBody = newTxnBody();
            given(handleContext.body()).willReturn(txnBody);

            assertThatThrownBy(() -> subject.handle(handleContext))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_WAS_DELETED));

            verify(tokenRelStore, never()).put(any(TokenRelation.class));
        }

        @Test
        @DisplayName("Valid inputs should grant KYC and commit changes")
        void kycRevokedAndPersisted() {
            final var stateTokenRel = newTokenRelationBuilder()
                    .tokenId(TOKEN_10)
                    .accountId(ACCOUNT_100)
                    .kycGranted(true)
                    .build();
            given(readableAccountStore.getAccountById(ACCOUNT_100))
                    .willReturn(Account.newBuilder().accountId(ACCOUNT_100).build());
            given(readableTokenStore.get(TOKEN_10)).willReturn(newToken10);
            given(tokenRelStore.get(ACCOUNT_100, TOKEN_10)).willReturn(stateTokenRel);
            given(expiryValidator.expirationStatus(EntityType.ACCOUNT, false, 0))
                    .willReturn(OK);

            final var txnBody = newTxnBody();
            given(handleContext.body()).willReturn(txnBody);

            subject.handle(handleContext);

            verify(tokenRelStore)
                    .put(newTokenRelationBuilder().kycGranted(false).build());
        }

        private TokenRelation.Builder newTokenRelationBuilder() {
            return TokenRelation.newBuilder().tokenId(TOKEN_10).accountId(ACCOUNT_100);
        }

        private TransactionBody newTxnBody() {
            TokenRevokeKycTransactionBody.Builder builder = TokenRevokeKycTransactionBody.newBuilder();
            builder.token(TOKEN_10);
            builder.account(ACCOUNT_100);
            return TransactionBody.newBuilder()
                    .tokenRevokeKyc(builder.build())
                    .memo(this.getClass().getName() + System.currentTimeMillis())
                    .build();
        }
    }
}

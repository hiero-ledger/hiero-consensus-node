// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.FIRST_TOKEN_SENDER_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_NO_SPECIAL_KEYS;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_WITH_FREEZE;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.TokenFreezeAccountTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.handlers.TokenFreezeAccountHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.ParityTestBase;
import com.hedera.node.app.spi.fixtures.Assertions;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

class TokenFreezeAccountHandlerTest {
    private static final AccountID ACCOUNT_13257 =
            AccountID.newBuilder().accountNum(13257).build();

    private TokenFreezeAccountHandler subject;

    @BeforeEach
    void setUp() {
        subject = new TokenFreezeAccountHandler();
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class PreHandleTests extends ParityTestBase {
        @Mock
        private PureChecksContext pureChecksContext;

        @Test
        void tokenFreezeWithNoToken() throws PreCheckException {
            final var theTxn = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_13257))
                    .tokenFreeze(TokenFreezeAccountTransactionBody.newBuilder().account(ACCOUNT_13257))
                    .build();

            final var context = new FakePreHandleContext(readableAccountStore, theTxn);
            context.registerStore(ReadableTokenStore.class, readableTokenStore);
            Assertions.assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_TOKEN_ID);
        }

        @Test
        void tokenFreezeWithNoAccount() {
            final var theTxn = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_13257))
                    .tokenFreeze(TokenFreezeAccountTransactionBody.newBuilder()
                            .token(TokenID.newBuilder().tokenNum(123L)))
                    .build();
            given(pureChecksContext.body()).willReturn(theTxn);

            Assertions.assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_ACCOUNT_ID);
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class HandleTests {
        private static final TokenID MISSING_TOKEN_12345 =
                TokenID.newBuilder().tokenNum(12345).build();

        @Mock(strictness = Strictness.LENIENT)
        private HandleContext context;

        @Mock(strictness = Strictness.LENIENT)
        private StoreFactory storeFactory;

        @Mock
        private ReadableTokenStore readableTokenStore;

        @Mock
        private ReadableAccountStore readableAccountStore;

        @Mock
        private WritableTokenRelationStore tokenRelStore;

        @Mock
        private ExpiryValidator expiryValidator;

        @BeforeEach
        void setup() {
            given(context.storeFactory()).willReturn(storeFactory);
            given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(readableTokenStore);
            given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
            given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(tokenRelStore);
            given(context.expiryValidator()).willReturn(expiryValidator);
        }

        @SuppressWarnings("DataFlowIssue")
        @Test
        void nullArgThrowsException() {
            assertThatThrownBy(() -> subject.handle(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void tokenNotPresentInTxnBody() {
            final var noTokenTxn = newFreezeTxn(null, ACCOUNT_13257);
            given(context.body()).willReturn(noTokenTxn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TOKEN_ID));
            verifyNoPut();
        }

        @Test
        void accountNotPresentInTxnBody() {
            final var pbjToken = toPbj(KNOWN_TOKEN_WITH_FREEZE);
            final var noAcctTxn = newFreezeTxn(pbjToken, null);
            given(readableTokenStore.get(pbjToken))
                    .willReturn(Token.newBuilder().tokenId(pbjToken).build());
            given(context.body()).willReturn(noAcctTxn);
            given(readableTokenStore.getTokenMeta(pbjToken)).willReturn(tokenMetaWithFreezeKey());

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_ACCOUNT_ID));
            verifyNoPut();
        }

        @Test
        void tokenNotFound() {
            final var token = MISSING_TOKEN_12345;
            final var txn = newFreezeTxn(token);
            given(context.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TOKEN_ID));
            verifyNoPut();
        }

        @Test
        void tokenPaused() throws HandleException {
            final var token = toPbj(KNOWN_TOKEN_WITH_FREEZE);
            given(readableTokenStore.get(token))
                    .willReturn(Token.newBuilder().tokenId(token).paused(true).build());
            final var txn = newFreezeTxn(token);
            given(context.body()).willReturn(txn);

            AssertionsForClassTypes.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_IS_PAUSED));
            verifyNoPut();
        }

        @Test
        void tokenDeleted() throws HandleException {
            final var token = toPbj(KNOWN_TOKEN_WITH_FREEZE);
            given(readableTokenStore.get(token))
                    .willReturn(Token.newBuilder().tokenId(token).deleted(true).build());
            final var txn = newFreezeTxn(token);
            given(context.body()).willReturn(txn);

            AssertionsForClassTypes.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_WAS_DELETED));
            verifyNoPut();
        }

        @Test
        void tokenHasNoFreezeKey() {
            final var token = toPbj(KNOWN_TOKEN_NO_SPECIAL_KEYS);
            given(readableTokenStore.get(token))
                    .willReturn(Token.newBuilder().tokenId(token).build());
            given(readableTokenStore.getTokenMeta(token)).willReturn(tokenMetaWithFreezeKey(null));
            final var txn = newFreezeTxn(token);
            given(context.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_HAS_NO_FREEZE_KEY));
            verifyNoPut();
        }

        @Test
        void accountNotFound() {
            final var token = toPbj(KNOWN_TOKEN_WITH_FREEZE);
            given(readableTokenStore.get(token))
                    .willReturn(Token.newBuilder()
                            .tokenId(token)
                            .freezeKey(FIRST_TOKEN_SENDER_KT.asPbjKey())
                            .build());
            given(readableTokenStore.getTokenMeta(token)).willReturn(tokenMetaWithFreezeKey());
            given(readableAccountStore.getAccountById(ACCOUNT_13257)).willReturn(null);
            final var txn = newFreezeTxn(token);
            given(context.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_ACCOUNT_ID));
            verifyNoPut();
        }

        @Test
        void tokenRelNotFound() throws HandleException {
            final var token = toPbj(KNOWN_TOKEN_WITH_FREEZE);
            given(readableTokenStore.get(token))
                    .willReturn(Token.newBuilder().tokenId(token).build());
            given(readableTokenStore.getTokenMeta(token)).willReturn(tokenMetaWithFreezeKey());
            given(readableAccountStore.getAccountById(ACCOUNT_13257))
                    .willReturn(Account.newBuilder().accountId(ACCOUNT_13257).build());
            given(tokenRelStore.get(ACCOUNT_13257, token)).willReturn(null);
            given(expiryValidator.expirationStatus(EntityType.ACCOUNT, false, 0))
                    .willReturn(OK);
            final var txn = newFreezeTxn(token);
            given(context.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
            verifyNoPut();
        }

        @Test
        void tokenRelFreezeSuccessful() {
            final var token = toPbj(KNOWN_TOKEN_WITH_FREEZE);
            given(readableTokenStore.get(token))
                    .willReturn(Token.newBuilder().tokenId(token).build());
            given(readableTokenStore.getTokenMeta(token)).willReturn(tokenMetaWithFreezeKey());
            given(readableAccountStore.getAccountById(ACCOUNT_13257))
                    .willReturn(Account.newBuilder().accountId(ACCOUNT_13257).build());
            given(tokenRelStore.get(ACCOUNT_13257, token))
                    .willReturn(TokenRelation.newBuilder()
                            .tokenId(token)
                            .accountId(ACCOUNT_13257)
                            .build());
            given(expiryValidator.expirationStatus(EntityType.ACCOUNT, false, 0))
                    .willReturn(OK);
            final var txn = newFreezeTxn(token);
            given(context.body()).willReturn(txn);

            subject.handle(context);
            verify(tokenRelStore)
                    .put(TokenRelation.newBuilder()
                            .tokenId(token)
                            .accountId(ACCOUNT_13257)
                            .frozen(true)
                            .build());
        }

        private void verifyNoPut() {
            verify(tokenRelStore, never()).put(any());
        }

        private ReadableTokenStore.TokenMetadata tokenMetaWithFreezeKey() {
            return tokenMetaWithFreezeKey(FIRST_TOKEN_SENDER_KT.asPbjKey());
        }

        private ReadableTokenStore.TokenMetadata tokenMetaWithFreezeKey(Key freezeKey) {
            return new ReadableTokenStore.TokenMetadata(
                    null, null, null, freezeKey, null, null, null, null, false, asAccount(0L, 0L, 25L), 2);
        }

        private TransactionBody newFreezeTxn(TokenID token) {
            return newFreezeTxn(token, ACCOUNT_13257);
        }

        private TransactionBody newFreezeTxn(TokenID token, AccountID account) {
            TokenFreezeAccountTransactionBody.Builder freezeTxnBodyBuilder =
                    TokenFreezeAccountTransactionBody.newBuilder();
            if (token != null) freezeTxnBodyBuilder.token(token);
            if (account != null) freezeTxnBodyBuilder.account(account);
            return TransactionBody.newBuilder()
                    .transactionID(
                            TransactionID.newBuilder().accountID(ACCOUNT_13257).build())
                    .tokenFreeze(freezeTxnBodyBuilder)
                    .build();
        }
    }
}

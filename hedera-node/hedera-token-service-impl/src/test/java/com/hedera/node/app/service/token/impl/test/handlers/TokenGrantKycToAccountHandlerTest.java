// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_WIPE_KT;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.verify;
import static org.mockito.Mock.Strictness.LENIENT;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.TokenGrantKycTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler;
import com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenGrantKycToAccountHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.TokenHandlerTestBase;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenGrantKycToAccountHandlerTest extends TokenHandlerTestBase {
    private final TokenGrantKycToAccountHandler subject = new TokenGrantKycToAccountHandler();

    @Mock
    private PureChecksContext pureChecksContext;

    @Test
    void txnHasNoToken() {
        final var missingTokenTxn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(TEST_DEFAULT_PAYER))
                .tokenGrantKyc(TokenGrantKycTransactionBody.newBuilder()
                        .account(AccountID.newBuilder().accountNum(1L))
                        .build())
                .build();
        given(pureChecksContext.body()).willReturn(missingTokenTxn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TOKEN_ID));
    }

    @Test
    void txnHasNoAccount() {
        final var missingAcctTxn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(TEST_DEFAULT_PAYER))
                .tokenGrantKyc(
                        TokenGrantKycTransactionBody.newBuilder().token(tokenId).build())
                .build();
        given(pureChecksContext.body()).willReturn(missingAcctTxn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ACCOUNT_ID));
    }

    @Nested
    class HandleTests {
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
        private static final TokenID TOKEN_531 = BaseTokenHandler.asToken(531);

        private static final Token newToken531 = Token.newBuilder()
                .tokenId(TOKEN_531)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .treasuryAccountId(TREASURY_ACCOUNT_9876)
                .wipeKey(TOKEN_WIPE_KT.asPbjKey())
                .totalSupply(1000L)
                .build();

        @Mock(strictness = LENIENT)
        private HandleContext handleContext;

        @BeforeEach
        void setup() {
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
        @DisplayName("When op tokenGrantKyc is null, tokenGrantKycOrThrow throws an exception")
        void nullTokenGrantKycThrowsException() {
            assertThatThrownBy(() -> subject.handle(handleContext)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("When op token ID is null, tokenOrThrow throws an exception")
        void nullTokenIdThrowsException() {
            assertThatThrownBy(() -> subject.handle(handleContext)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("When op account ID is null, accountOrThrow throws an exception")
        void nullAccountIdThrowsException() {
            final var txnBody = newTxnBody(false, true);
            given(handleContext.body()).willReturn(txnBody);

            assertThatThrownBy(() -> subject.handle(handleContext)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("When TokenRelStore.get() returns empty, should not put or commit")
        void emptyGetShouldNotPersist() {
            given(readableAccountStore.getAccountById(payerId))
                    .willReturn(Account.newBuilder().accountId(payerId).build());
            given(readableTokenStore.get(tokenId)).willReturn(newToken531);
            given(tokenRelStore.get(notNull(), notNull())).willReturn(null);
            given(expiryValidator.expirationStatus(EntityType.ACCOUNT, false, 0))
                    .willReturn(OK);

            final var txnBody = newTxnBody(true, true);
            given(handleContext.body()).willReturn(txnBody);
            assertThatThrownBy(() -> subject.handle(handleContext))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));

            verify(tokenRelStore, never()).put(any(TokenRelation.class));
        }

        @Test
        @DisplayName("Valid inputs should grant KYC and commit changes")
        void kycGrantedAndPersisted() {
            final var stateTokenRel =
                    newTokenRelationBuilder().kycGranted(false).build();
            given(readableAccountStore.getAccountById(payerId))
                    .willReturn(Account.newBuilder().accountId(payerId).build());
            given(readableTokenStore.get(tokenId)).willReturn(newToken531);
            given(tokenRelStore.get(payerId, tokenId)).willReturn(stateTokenRel);
            given(expiryValidator.expirationStatus(EntityType.ACCOUNT, false, 0))
                    .willReturn(OK);

            final var txnBody = newTxnBody(true, true);
            given(handleContext.body()).willReturn(txnBody);
            subject.handle(handleContext);

            verify(tokenRelStore).put(newTokenRelationBuilder().kycGranted(true).build());
        }

        private TokenRelation.Builder newTokenRelationBuilder() {
            return TokenRelation.newBuilder().tokenId(token.tokenId()).accountId(payerId);
        }

        private TransactionBody newTxnBody(final boolean tokenPresent, final boolean accountPresent) {
            TokenGrantKycTransactionBody.Builder builder = TokenGrantKycTransactionBody.newBuilder();
            if (tokenPresent) {
                builder.token(tokenId);
            }
            if (accountPresent) {
                builder.account(payerId);
            }
            return TransactionBody.newBuilder()
                    .tokenGrantKyc(builder.build())
                    .memo(this.getClass().getName() + System.currentTimeMillis())
                    .build();
        }
    }
}

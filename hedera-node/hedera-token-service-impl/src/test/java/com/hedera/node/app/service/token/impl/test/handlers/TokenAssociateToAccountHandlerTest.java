// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.DELETED_TOKEN;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_IMMUTABLE;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_WITH_FEE_SCHEDULE_KEY;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_WITH_FREEZE;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_WITH_KYC;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_WITH_PAUSE;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_WITH_WIPE;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.MISC_ACCOUNT;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.TokenAssociateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.handlers.TokenAssociateToAccountHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.ParityTestBase;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.TokensConfig;
import com.swirlds.config.api.Configuration;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenAssociateToAccountHandlerTest {
    private static final AccountID ACCOUNT_888 =
            AccountID.newBuilder().accountNum(888).build();
    private static final AccountID ACCOUNT_1339 =
            AccountID.newBuilder().accountNum(MISC_ACCOUNT.getAccountNum()).build();
    private static final TokenID TOKEN_300 = TokenID.newBuilder().tokenNum(300).build();
    private static final TokenID TOKEN_400 = TokenID.newBuilder().tokenNum(400).build();

    private TokenAssociateToAccountHandler subject;

    @Mock
    private HandleContext context;

    @Mock
    private StoreFactory storeFactory;

    @Mock(strictness = LENIENT)
    private ExpiryValidator expiryValidator;

    @Mock
    private PureChecksContext pureChecksContext;

    @BeforeEach
    void setUp() {
        lenient().when(context.expiryValidator()).thenReturn(expiryValidator);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        subject = new TokenAssociateToAccountHandler();
    }

    @Nested
    class PreHandleTests extends ParityTestBase {
        @SuppressWarnings("DataFlowIssue")
        @Test
        void nullArgsThrows() {
            assertThatThrownBy(() -> subject.preHandle(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void txnWithoutAccountThrows() throws PreCheckException {
            final var txn = newAssociateTxn(null, List.of(TOKEN_300));
            final var preHandleContext = new FakePreHandleContext(readableAccountStore, txn);

            assertThatThrownBy(() -> subject.preHandle(preHandleContext))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_ACCOUNT_ID));
        }

        @Test
        void txnWithRepeatedTokenIdsThrows() throws PreCheckException {
            final var txn = newAssociateTxn(ACCOUNT_888, List.of(TOKEN_300, TOKEN_400, TOKEN_300));
            given(pureChecksContext.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(TOKEN_ID_REPEATED_IN_TOKEN_LIST));
        }

        @Test
        void txnWithEmptyTokenIdsSucceeds() throws PreCheckException {
            final var txn = newAssociateTxn(ACCOUNT_888, Collections.emptyList());
            final var preHandleContext = new FakePreHandleContext(readableAccountStore, txn);

            subject.preHandle(preHandleContext);
            Assertions.assertThat(preHandleContext.requiredNonPayerKeys()).isNotEmpty();
        }

        private TransactionBody newAssociateTxn(AccountID account, List<TokenID> tokens) {
            TokenAssociateTransactionBody.Builder associateTxnBodyBuilder = TokenAssociateTransactionBody.newBuilder();
            if (tokens != null) associateTxnBodyBuilder.tokens(tokens);
            if (account != null) associateTxnBodyBuilder.account(account);
            return TransactionBody.newBuilder()
                    .transactionID(
                            TransactionID.newBuilder().accountID(ACCOUNT_1339).build())
                    .tokenAssociate(associateTxnBodyBuilder)
                    .build();
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class HandleTests extends ParityTestBase {
        @SuppressWarnings("DataFlowIssue")
        @Test
        void nullArgs() {
            assertThatThrownBy(() -> subject.handle(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void tokenStoreNotCreated() {
            given(context.storeFactory()).willReturn(storeFactory);
            given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(null);

            assertThatThrownBy(() -> subject.handle(context)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void newTokenRelsExceedsSystemMax() {
            mockContext(1, 2000L);
            final var txn = newAssociateTxn(toPbj(KNOWN_TOKEN_IMMUTABLE), toPbj(KNOWN_TOKEN_WITH_KYC));
            given(context.body()).willReturn(txn);
            given(context.storeFactory()).willReturn(storeFactory);
            given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
            given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED));
        }

        @Test
        void accountNotFound() {
            mockContext();
            final var missingAcctId = AccountID.newBuilder().accountNum(99999L);
            final var txn = TransactionBody.newBuilder()
                    .transactionID(
                            TransactionID.newBuilder().accountID(ACCOUNT_888).build())
                    .tokenAssociate(TokenAssociateTransactionBody.newBuilder()
                            .account(missingAcctId)
                            .tokens(toPbj(KNOWN_TOKEN_IMMUTABLE))
                            .build())
                    .build();
            given(context.body()).willReturn(txn);
            given(context.storeFactory()).willReturn(storeFactory);
            given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
            given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_ACCOUNT_ID));
        }

        @Test
        void anyTokenNotFound() {
            mockContext();
            final var txn = newAssociateTxn(toPbj(KNOWN_TOKEN_IMMUTABLE), TOKEN_300);
            given(context.body()).willReturn(txn);
            given(context.storeFactory()).willReturn(storeFactory);
            given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
            given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TOKEN_ID));
        }

        @Test
        void tokenIsDeleted() {
            mockContext();
            final var txn = newAssociateTxn(toPbj(KNOWN_TOKEN_IMMUTABLE), toPbj(DELETED_TOKEN));
            given(context.body()).willReturn(txn);
            given(context.storeFactory()).willReturn(storeFactory);
            given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
            given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_WAS_DELETED));
        }

        @Test
        void tokenIsPaused() {
            mockContext();
            final var txn = newAssociateTxn(toPbj(KNOWN_TOKEN_IMMUTABLE), toPbj(KNOWN_TOKEN_WITH_PAUSE));
            given(context.body()).willReturn(txn);
            given(context.storeFactory()).willReturn(storeFactory);
            given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
            given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_IS_PAUSED));
        }

        @Test
        void exceedsTokenAssociationLimitForAccount() {
            // There are 3 tokens already associated with the account we're putting in the transaction, so we
            // need maxTokensPerAccount to be at least 3
            mockConfig(2000L, true, 3);
            final var txn = newAssociateTxn(toPbj(KNOWN_TOKEN_WITH_FREEZE));
            given(context.body()).willReturn(txn);
            given(context.storeFactory()).willReturn(storeFactory);
            given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
            given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED));
        }

        @Test
        void tokenAlreadyAssociatedWithAccount() {
            mockContext();
            final var txn = newAssociateTxn(toPbj(KNOWN_TOKEN_WITH_KYC));
            given(context.body()).willReturn(txn);
            given(context.storeFactory()).willReturn(storeFactory);
            given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
            given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT));
        }

        @Test
        void tokensAssociateToAccountWithNoTokenRels() {
            // Set maxTokensPerAccount to a value that will fail if areTokenAssociationsLimited
            // is incorrectly ignored
            mockConfig(2000L, false, 4);
            given(context.storeFactory()).willReturn(storeFactory);
            given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(readableTokenStore);

            // Put a new account into the account store that has no tokens associated with it
            final var newAcctNum = 12345L;
            final var newAcctId = AccountID.newBuilder().accountNum(newAcctNum).build();
            writableAccountStore.put(Account.newBuilder()
                    .accountId(newAcctId)
                    .headTokenId(TokenID.DEFAULT)
                    .build());
            final var txn = TransactionBody.newBuilder()
                    .transactionID(
                            TransactionID.newBuilder().accountID(ACCOUNT_888).build())
                    .tokenAssociate(TokenAssociateTransactionBody.newBuilder()
                            .account(newAcctId)
                            .tokens(toPbj(KNOWN_TOKEN_WITH_FEE_SCHEDULE_KEY), toPbj(KNOWN_TOKEN_WITH_WIPE))
                            .build())
                    .build();
            given(context.body()).willReturn(txn);
            given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
            given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

            subject.handle(context);
            Assertions.assertThat(writableTokenRelStore.modifiedTokens())
                    .contains(
                            EntityIDPair.newBuilder()
                                    .accountId(newAcctId)
                                    .tokenId(toPbj(KNOWN_TOKEN_WITH_FEE_SCHEDULE_KEY))
                                    .build(),
                            EntityIDPair.newBuilder()
                                    .accountId(newAcctId)
                                    .tokenId(toPbj(KNOWN_TOKEN_WITH_WIPE))
                                    .build());
            final var headToken = writableAccountStore.getAccountById(newAcctId).headTokenId();
            final var headTokenRel = writableTokenRelStore.get(newAcctId, headToken);
            Assertions.assertThat(headTokenRel.frozen()).isFalse();
            Assertions.assertThat(headTokenRel.kycGranted()).isTrue();
            Assertions.assertThat(headTokenRel.previousToken()).isNull();
            Assertions.assertThat(headTokenRel.tokenId()).isEqualTo(toPbj(KNOWN_TOKEN_WITH_WIPE));
            Assertions.assertThat(headTokenRel.nextToken()).isEqualTo(toPbj(KNOWN_TOKEN_WITH_FEE_SCHEDULE_KEY));
            final var nextToHeadTokenRel = writableTokenRelStore.get(newAcctId, headTokenRel.nextToken());
            Assertions.assertThat(nextToHeadTokenRel.frozen()).isFalse();
            Assertions.assertThat(nextToHeadTokenRel.kycGranted()).isTrue();
            Assertions.assertThat(nextToHeadTokenRel.previousToken()).isEqualTo(toPbj(KNOWN_TOKEN_WITH_WIPE));
            Assertions.assertThat(nextToHeadTokenRel.tokenId()).isEqualTo(toPbj(KNOWN_TOKEN_WITH_FEE_SCHEDULE_KEY));
            Assertions.assertThat(nextToHeadTokenRel.nextToken()).isNull();
        }

        @Test
        void tokensAssociateToAccountWithExistingTokenRels() {
            mockContext();
            final var newAcctNum = 21212L;
            final var newAcctId = AccountID.newBuilder().accountNum(newAcctNum).build();
            // put a new account into the account store that has two tokens associated with it
            writableAccountStore.put(Account.newBuilder()
                    .accountId(newAcctId)
                    .headTokenId(TokenID.newBuilder().tokenNum(774L))
                    .build());

            // put the pre-existing token rels into the rel store
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(newAcctId)
                    .tokenId(toPbj(KNOWN_TOKEN_WITH_WIPE))
                    .nextToken(toPbj(KNOWN_TOKEN_WITH_FEE_SCHEDULE_KEY))
                    .balance(100)
                    .build());
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(newAcctId)
                    .tokenId(toPbj(KNOWN_TOKEN_WITH_FEE_SCHEDULE_KEY))
                    .previousToken(toPbj(KNOWN_TOKEN_WITH_WIPE))
                    .balance(200)
                    .build());

            final var txn = TransactionBody.newBuilder()
                    .transactionID(
                            TransactionID.newBuilder().accountID(ACCOUNT_888).build())
                    .tokenAssociate(TokenAssociateTransactionBody.newBuilder()
                            .account(newAcctId)
                            .tokens(toPbj(KNOWN_TOKEN_WITH_FREEZE), toPbj(KNOWN_TOKEN_WITH_KYC))
                            .build())
                    .build();
            given(context.body()).willReturn(txn);
            given(context.storeFactory()).willReturn(storeFactory);
            given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
            given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

            subject.handle(context);

            Assertions.assertThat(writableTokenRelStore.modifiedTokens())
                    .contains(
                            EntityIDPair.newBuilder()
                                    .accountId(newAcctId)
                                    .tokenId(toPbj(KNOWN_TOKEN_WITH_FREEZE))
                                    .build(),
                            EntityIDPair.newBuilder()
                                    .accountId(newAcctId)
                                    .tokenId(toPbj(KNOWN_TOKEN_WITH_KYC))
                                    .build());
            final var headTokenId =
                    writableAccountStore.getAccountById(newAcctId).headTokenId();
            final var headTokenRel = writableTokenRelStore.get(newAcctId, headTokenId);
            Assertions.assertThat(headTokenRel.previousToken()).isNull();
            Assertions.assertThat(headTokenRel.tokenId()).isEqualTo(toPbj(KNOWN_TOKEN_WITH_KYC));
            Assertions.assertThat(headTokenRel.nextToken()).isEqualTo(toPbj(KNOWN_TOKEN_WITH_FREEZE));
            Assertions.assertThat(headTokenRel.frozen()).isFalse();
            Assertions.assertThat(headTokenRel.kycGranted()).isFalse();
            Assertions.assertThat(headTokenRel.automaticAssociation()).isFalse();

            final var nextToHeadTokenRel = writableTokenRelStore.get(newAcctId, headTokenRel.nextToken());
            Assertions.assertThat(nextToHeadTokenRel.previousToken().tokenNum())
                    .isEqualTo(KNOWN_TOKEN_WITH_KYC.getTokenNum());
            Assertions.assertThat(nextToHeadTokenRel.tokenId()).isEqualTo(toPbj(KNOWN_TOKEN_WITH_FREEZE));
            Assertions.assertThat(nextToHeadTokenRel.nextToken()).isEqualTo(toPbj(KNOWN_TOKEN_WITH_WIPE));
            Assertions.assertThat(nextToHeadTokenRel.frozen()).isTrue();
            // Note: this token doesn't actually have a KYC key even though its name implies that
            // it does
            Assertions.assertThat(nextToHeadTokenRel.kycGranted()).isTrue();
            Assertions.assertThat(nextToHeadTokenRel.automaticAssociation()).isFalse();

            final var thirdTokenRel = writableTokenRelStore.get(newAcctId, nextToHeadTokenRel.nextToken());
            Assertions.assertThat(thirdTokenRel.previousToken()).isEqualTo(toPbj(KNOWN_TOKEN_WITH_FREEZE));
            Assertions.assertThat(thirdTokenRel.tokenId()).isEqualTo(toPbj(KNOWN_TOKEN_WITH_WIPE));
            Assertions.assertThat(thirdTokenRel.nextToken()).isEqualTo(toPbj(KNOWN_TOKEN_WITH_FEE_SCHEDULE_KEY));
            Assertions.assertThat(thirdTokenRel.frozen()).isFalse();
            Assertions.assertThat(thirdTokenRel.kycGranted()).isFalse();
            Assertions.assertThat(thirdTokenRel.automaticAssociation()).isFalse();

            final var fourthTokenRel = writableTokenRelStore.get(newAcctId, thirdTokenRel.nextToken());
            Assertions.assertThat(fourthTokenRel.previousToken()).isEqualTo(toPbj(KNOWN_TOKEN_WITH_WIPE));
            Assertions.assertThat(fourthTokenRel.tokenId()).isEqualTo(toPbj(KNOWN_TOKEN_WITH_FEE_SCHEDULE_KEY));
            Assertions.assertThat(fourthTokenRel.nextToken()).isNull();
            Assertions.assertThat(fourthTokenRel.frozen()).isFalse();
            Assertions.assertThat(fourthTokenRel.kycGranted()).isFalse();
            Assertions.assertThat(fourthTokenRel.automaticAssociation()).isFalse();
        }

        @Test
        void missingAccountHeadTokenDoesntStopTokenAssociation() {
            mockContext();
            final var newAcctNum = 21212L;
            final var newAcctId = AccountID.newBuilder().accountNum(newAcctNum).build();
            // put a new account into the account store that has a bogus head token number
            writableAccountStore.put(Account.newBuilder()
                    .accountId(newAcctId)
                    .headTokenId(TOKEN_300)
                    .build());

            final var txn = TransactionBody.newBuilder()
                    .transactionID(
                            TransactionID.newBuilder().accountID(ACCOUNT_888).build())
                    .tokenAssociate(TokenAssociateTransactionBody.newBuilder()
                            .account(newAcctId)
                            .tokens(toPbj(KNOWN_TOKEN_WITH_FREEZE), toPbj(KNOWN_TOKEN_WITH_KYC))
                            .build())
                    .build();
            given(context.body()).willReturn(txn);
            given(context.storeFactory()).willReturn(storeFactory);
            given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
            given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

            subject.handle(context);

            Assertions.assertThat(writableTokenRelStore.modifiedTokens())
                    .contains(
                            EntityIDPair.newBuilder()
                                    .accountId(newAcctId)
                                    .tokenId(toPbj(KNOWN_TOKEN_WITH_FREEZE))
                                    .build(),
                            EntityIDPair.newBuilder()
                                    .accountId(newAcctId)
                                    .tokenId(toPbj(KNOWN_TOKEN_WITH_KYC))
                                    .build());
            final var updatedAcct = writableAccountStore.getAccountById(newAcctId);
            Assertions.assertThat(updatedAcct).isNotNull();
            // The account's updated head token num will point to the first new token
            Assertions.assertThat(updatedAcct.headTokenId().tokenNum()).isEqualTo(KNOWN_TOKEN_WITH_KYC.getTokenNum());
            // And new token relations will still exist for the new token IDs
            Assertions.assertThat(writableTokenRelStore.get(newAcctId, toPbj(KNOWN_TOKEN_WITH_FREEZE)))
                    .isNotNull();
            Assertions.assertThat(writableTokenRelStore.get(newAcctId, toPbj(KNOWN_TOKEN_WITH_KYC)))
                    .isNotNull();
        }

        private TransactionBody newAssociateTxn(TokenID... ids) {
            return TransactionBody.newBuilder()
                    .transactionID(
                            TransactionID.newBuilder().accountID(ACCOUNT_888).build())
                    .tokenAssociate(TokenAssociateTransactionBody.newBuilder()
                            .account(ACCOUNT_1339)
                            .tokens(ids)
                            .build())
                    .build();
        }

        private void mockContext() {
            mockContext(1000L, 2000L);
        }

        private void mockContext(final long maxNumTokenRels, final long maxTokensPerAccount) {
            mockConfig(maxNumTokenRels, false, (int) maxTokensPerAccount);

            given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(readableTokenStore);
        }

        // The context passed in needs to be a mock
        private void mockConfig(final long maxAggregateRels, final boolean limitedRels, final int maxRelsPerAccount) {
            lenient().when(storeFactory.readableStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
            final var config = mock(Configuration.class);
            lenient().when(context.configuration()).thenReturn(config);
            final var tokensConfig = mock(TokensConfig.class);
            lenient().when(tokensConfig.maxAggregateRels()).thenReturn(maxAggregateRels);
            lenient().when(tokensConfig.maxPerAccount()).thenReturn(maxRelsPerAccount);
            lenient().when(config.getConfigData(TokensConfig.class)).thenReturn(tokensConfig);
            final var entitiesConfig = mock(EntitiesConfig.class);
            lenient().when(config.getConfigData(EntitiesConfig.class)).thenReturn(entitiesConfig);
            lenient().when(entitiesConfig.limitTokenAssociations()).thenReturn(limitedRels);
        }
    }
}

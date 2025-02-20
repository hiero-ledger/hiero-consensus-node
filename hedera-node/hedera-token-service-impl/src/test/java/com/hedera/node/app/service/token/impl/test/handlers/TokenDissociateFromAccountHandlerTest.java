// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_STILL_OWNS_NFTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newReadableStoreWithTokens;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newWritableStoreWithAccounts;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.MISC_ACCOUNT;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.TokenDissociateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenDissociateFromAccountHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.ParityTestBase;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenDissociateFromAccountHandlerTest extends ParityTestBase {
    @Mock(strictness = Mock.Strictness.LENIENT)
    private ExpiryValidator expiryValidator;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private HandleContext handleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    private static final AccountID ACCOUNT_1339 =
            AccountID.newBuilder().accountNum(MISC_ACCOUNT.getAccountNum()).build();
    private static final AccountID ACCOUNT_2020 = BaseCryptoHandler.asAccount(0L, 0L, 2020);
    private static final TokenID TOKEN_555_ID =
            TokenID.newBuilder().tokenNum(555).build();
    private static final TokenID TOKEN_666_ID =
            TokenID.newBuilder().tokenNum(666).build();

    private final TokenDissociateFromAccountHandler subject = new TokenDissociateFromAccountHandler();

    @Nested
    class PreHandleTests {
        @SuppressWarnings("DataFlowIssue")
        @Test
        void nullArgsThrows() {
            assertThatThrownBy(() -> subject.preHandle(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void pureChecksRejectsDissociateWithMissingAccount() {
            final var txn = newDissociateTxn(null, List.of(TOKEN_555_ID));
            given(pureChecksContext.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_ACCOUNT_ID));
        }

        @Test
        void pureChecksRejectsDissociateWithRepeatedTokenId() {
            final var txn = newDissociateTxn(ACCOUNT_1339, List.of(TOKEN_555_ID, TOKEN_666_ID, TOKEN_555_ID));
            given(pureChecksContext.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(TOKEN_ID_REPEATED_IN_TOKEN_LIST));
        }
    }

    @Nested
    class HandleTests {
        @Test
        void rejectsNonexistingAccount() {
            final var context = mockContext();
            final var txn = newDissociateTxn(AccountID.newBuilder().build(), List.of(TOKEN_555_ID));
            given(context.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_ACCOUNT_ID));
        }

        @Test
        void rejectsExpiredAccount() {
            // Create an account that is expired
            final var accountNumber = 12345L;
            final AccountID accountId =
                    AccountID.newBuilder().accountNum(accountNumber).build();
            writableAccountStore.put(Account.newBuilder()
                    .accountId(accountId)
                    .expiredAndPendingRemoval(true)
                    .deleted(false)
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(accountId, List.of(TOKEN_555_ID));
            given(context.body()).willReturn(txn);
            given(expiryValidator.expirationStatus(eq(EntityType.ACCOUNT), eq(true), anyLong()))
                    .willReturn(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);
            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL));
        }

        @Test
        void rejectsDeletedAccount() {
            // Create an account that is deleted
            final var accountNumber = 53135;
            final AccountID accountId =
                    AccountID.newBuilder().accountNum(accountNumber).build();
            writableAccountStore.put(Account.newBuilder()
                    .accountId(accountId)
                    .expiredAndPendingRemoval(false)
                    .deleted(true)
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(accountId, List.of(TOKEN_555_ID));
            given(context.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(ACCOUNT_DELETED));
        }

        @Test
        void rejectsNonexistingTokenRel() {
            final var context = mockContext();
            final var txn = newDissociateTxn(ACCOUNT_1339, List.of(TOKEN_555_ID));
            given(context.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
        }

        @Test
        void rejectsPausedToken() {
            // Create a readable store with a paused token
            final var pausedToken =
                    Token.newBuilder().tokenId(TOKEN_555_ID).paused(true).build();
            readableTokenStore = newReadableStoreWithTokens(pausedToken);

            // Create the token rel for the paused token
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_555_ID)
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(ACCOUNT_1339, List.of(TOKEN_555_ID));
            given(context.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_IS_PAUSED));
        }

        @Test
        void rejectsTreasuryAccount() {
            // Create a readable store that has a token with a treasury account
            final var tokenWithTreasury = Token.newBuilder()
                    .tokenId(TOKEN_555_ID)
                    .treasuryAccountId(ACCOUNT_1339)
                    .build();
            readableTokenStore = newReadableStoreWithTokens(tokenWithTreasury);

            // Create the token rel
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_555_ID)
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(ACCOUNT_1339, List.of(TOKEN_555_ID));
            given(context.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(ACCOUNT_IS_TREASURY));
        }

        @Test
        void rejectsFrozenToken() {
            // Create the readable store with a token
            final var tokenWithTreasury =
                    Token.newBuilder().tokenId(TOKEN_555_ID).build();
            readableTokenStore = newReadableStoreWithTokens(tokenWithTreasury);

            // Create the frozen token rel
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_555_ID)
                    .frozen(true)
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(ACCOUNT_1339, List.of(TOKEN_555_ID));
            given(context.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(ACCOUNT_FROZEN_FOR_TOKEN));
        }

        @Test
        void rejectsAccountThatStillOwnsNfts() {
            // Create the readable store with a token that still owns an NFT
            final var tokenWithTreasury = Token.newBuilder()
                    .tokenId(TOKEN_555_ID)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .build();
            readableTokenStore = newReadableStoreWithTokens(tokenWithTreasury);

            // Create the token rel with a non-zero NFT balance
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_555_ID)
                    .balance(1L)
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(ACCOUNT_1339, List.of(TOKEN_555_ID));
            given(context.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(ACCOUNT_STILL_OWNS_NFTS));
        }

        //        @Test
        //        void rejectsAccountThatStillOwnsUnexpiredFungibleUnits() {
        //            // @future('6864'): implement when token expiry is implemented
        //        }

        @Test
        void tokenRelForDeletedTokenIsRemoved() {
            // Create the writable account store with an account
            final var accountWithTokenRels = Account.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .headTokenId(TOKEN_555_ID)
                    .numberAssociations(1)
                    .usedAutoAssociations(0)
                    .build();
            writableAccountStore = newWritableStoreWithAccounts(accountWithTokenRels);

            // Create the readable token store with a deleted token
            final var tokenWithTreasury =
                    Token.newBuilder().tokenId(TOKEN_555_ID).deleted(true).build();
            readableTokenStore = newReadableStoreWithTokens(tokenWithTreasury);

            // Create the token rel for the deleted token
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_555_ID)
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(ACCOUNT_1339, List.of(TOKEN_555_ID));
            given(context.body()).willReturn(txn);

            subject.handle(context);

            // Verify the account is in its expected state
            final var savedAcct = writableAccountStore.get(ACCOUNT_1339);
            Assertions.assertThat(savedAcct).isNotNull();
            // Since only one token was associated with the account before handle(), the number of
            // associations should now be zero and the head token number should not exist (i.e. be -1)
            Assertions.assertThat(savedAcct.numberAssociations()).isZero();
            Assertions.assertThat(savedAcct.headTokenId().tokenNum()).isEqualTo(-1);
            // Since the account had no auto-associated tokens, usedAutoAssociations should still be zero
            Assertions.assertThat(savedAcct.usedAutoAssociations()).isZero();
            // Since the account had no positive balances, its number of positive balances should still be zero
            Assertions.assertThat(savedAcct.numberPositiveBalances()).isZero();
            // Verify that the token rel was removed
            final var supposedlyDeletedTokenRel = writableTokenRelStore.get(ACCOUNT_1339, TOKEN_555_ID);
            Assertions.assertThat(supposedlyDeletedTokenRel).isNull();
        }

        @Test
        void tokenRelForNonexistingTokenIsRemoved() {
            // Create the writable account store with an account
            final var accountWithTokenRels = Account.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .headTokenId(TOKEN_555_ID)
                    .numberAssociations(1)
                    .usedAutoAssociations(0)
                    .build();
            writableAccountStore = newWritableStoreWithAccounts(accountWithTokenRels);

            // Verify that the token doesn't exist
            final var token = readableTokenStore.get(TOKEN_555_ID);
            Assertions.assertThat(token).isNull();

            // Create the token rel for the nonexistent token
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_555_ID)
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(ACCOUNT_1339, List.of(TOKEN_555_ID));
            given(context.body()).willReturn(txn);

            subject.handle(context);

            // Verify the account is in its expected state
            final var savedAcct = writableAccountStore.get(ACCOUNT_1339);
            Assertions.assertThat(savedAcct).isNotNull();
            // Since only one token was associated with the account before handle(), the number of
            // associations should now be zero and the head token number should not exist (i.e. be -1)
            Assertions.assertThat(savedAcct.numberAssociations()).isZero();
            Assertions.assertThat(savedAcct.headTokenId().tokenNum()).isEqualTo(-1);
            // Since the account had no auto-associated tokens, auto associations should still be zero
            Assertions.assertThat(savedAcct.usedAutoAssociations()).isZero();
            // Since the account had no positive balances, its number of positive balances should still be zero
            Assertions.assertThat(savedAcct.numberPositiveBalances()).isZero();
            // Verify that the token rel was removed
            final var supposedlyDeletedTokenRel = writableTokenRelStore.get(ACCOUNT_1339, TOKEN_555_ID);
            Assertions.assertThat(supposedlyDeletedTokenRel).isNull();
        }

        @Test
        void rejectsAccountWithBalance() {
            // Create the readable store with a token
            final var tokenWithTreasury = Token.newBuilder()
                    .expirationSecond(1_234_567L)
                    .tokenId(TOKEN_555_ID)
                    .build();
            readableTokenStore = newReadableStoreWithTokens(tokenWithTreasury);

            // Create the frozen token rel
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_555_ID)
                    .balance(1000L)
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(ACCOUNT_1339, List.of(TOKEN_555_ID));
            given(context.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES));
        }

        //        @Test
        // Enable when token expiration is implemented
        void tokenRelAndTreasuryTokenRelAreUpdatedForExpiredFungible() {
            // i.e. verify the token rel is removed and the treasury token rel balance is updated

            // Create the writable account store with account 1339 and the treasury account
            final var accountWithTokenRels = Account.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .headTokenId(TOKEN_555_ID)
                    .numberAssociations(1)
                    .usedAutoAssociations(0)
                    .numberPositiveBalances(1)
                    .build();
            final var treasuryAccount = Account.newBuilder()
                    .accountId(ACCOUNT_2020)
                    .headTokenId(TOKEN_555_ID)
                    .numberAssociations(1)
                    .usedAutoAssociations(1)
                    .build();
            writableAccountStore = newWritableStoreWithAccounts(accountWithTokenRels, treasuryAccount);

            // Create the readable store with a token that:
            // 1. still has a fungible token balance
            // 2. has a treasury account
            final var totalSupply = 3000L;
            final var tokenWithTreasury = Token.newBuilder()
                    .tokenId(TOKEN_555_ID)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .treasuryAccountId(ACCOUNT_2020)
                    .totalSupply(totalSupply)
                    .build();
            readableTokenStore = newReadableStoreWithTokens(tokenWithTreasury);

            // Create the token rel with a non-zero fungible balance
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_555_ID)
                    .balance(1000)
                    .build());
            // Create the treasury token rel
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_2020)
                    .tokenId(TOKEN_555_ID)
                    .balance(2000L)
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(ACCOUNT_1339, List.of(TOKEN_555_ID));
            given(context.body()).willReturn(txn);

            subject.handle(context);

            // Verify the account is in its expected state
            final var savedAcct = writableAccountStore.get(ACCOUNT_1339);
            Assertions.assertThat(savedAcct).isNotNull();
            // Since only one token was associated with the account before handle(), the number of
            // associations should now be zero and the head token number should not exist (i.e. be -1)
            Assertions.assertThat(savedAcct.numberAssociations()).isZero();
            Assertions.assertThat(savedAcct.headTokenId().tokenNum()).isEqualTo(-1);
            // Since the account had no auto-associated tokens, auto associations should still be zero
            Assertions.assertThat(savedAcct.usedAutoAssociations()).isZero();
            // Since the removed token rel had a positive balance, the account's number of positive balances should also
            // be reduced
            Assertions.assertThat(savedAcct.numberPositiveBalances()).isZero();

            // Verify the treasury account is in its expected state
            final var treasuryAcct = writableAccountStore.get(ACCOUNT_2020);
            Assertions.assertThat(treasuryAcct).isNotNull();
            // The treasury should still be associated with the token
            Assertions.assertThat(treasuryAcct.numberAssociations()).isEqualTo(1);
            Assertions.assertThat(treasuryAcct.headTokenId()).isEqualTo(TOKEN_555_ID);

            // Verify that the token rel with account 1339 was removed
            final var supposedlyDeletedTokenRel = writableTokenRelStore.get(ACCOUNT_1339, TOKEN_555_ID);
            Assertions.assertThat(supposedlyDeletedTokenRel).isNull();

            // Verify that the token rel with the treasury account was updated
            final var treasuryTokenRel = writableTokenRelStore.get(ACCOUNT_2020, TOKEN_555_ID);
            Assertions.assertThat(treasuryTokenRel).isNotNull();
            // Verify that the treasury balance is now equal to its supply
            Assertions.assertThat(treasuryTokenRel.balance()).isEqualTo(totalSupply);
        }

        @Test
        void multipleTokenRelsAreRemoved() {
            // Represents a token that won't be found
            final var token444Id = asToken(444);
            // Represents a token that is deleted
            final var token555 =
                    Token.newBuilder().tokenId(TOKEN_555_ID).deleted(true).build();
            // Represents an active token
            final var token666 = Token.newBuilder()
                    .tokenId(TOKEN_666_ID)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .build();

            // Create the writable account store with an account
            final var accountWithTokenRels = Account.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .headTokenId(token444Id)
                    .numberAssociations(3)
                    .usedAutoAssociations(1) // We'll set up the active token rel as auto-associated
                    .numberPositiveBalances(2)
                    .build();
            writableAccountStore = newWritableStoreWithAccounts(accountWithTokenRels);

            // Create the readable token store with a deleted token
            readableTokenStore = newReadableStoreWithTokens(
                    token555, token666); // 444 not added intentionally to simulate it not being in the store

            // Create the token rel for each token
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(token444Id)
                    .previousToken(asToken(-1)) // start of the account's token list
                    .nextToken(TOKEN_555_ID)
                    .balance(20)
                    .build());
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_555_ID)
                    .previousToken(token444Id)
                    .nextToken(TOKEN_666_ID)
                    .balance(30)
                    .automaticAssociation(true)
                    .build());
            writableTokenRelStore.put(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_666_ID)
                    .previousToken(TOKEN_555_ID)
                    .nextToken((TokenID) null) // end of the account's token list
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(ACCOUNT_1339, List.of(token444Id, TOKEN_555_ID, TOKEN_666_ID));
            given(context.body()).willReturn(txn);

            subject.handle(context);

            // Verify the account is in its expected state
            final var savedAcct = writableAccountStore.get(ACCOUNT_1339);
            Assertions.assertThat(savedAcct).isNotNull();
            // After deleting all three token relations, the number of associations should now be
            // zero and the head token number should not exist (i.e. be -1)
            Assertions.assertThat(savedAcct.numberAssociations()).isZero();
            Assertions.assertThat(savedAcct.headTokenId().tokenNum()).isEqualTo(-1);
            // The active token, which is the only auto-associated token rel, should have been removed
            Assertions.assertThat(savedAcct.usedAutoAssociations()).isZero();
            // Both token rels with positive balances should have been removed, so the account's number of positive
            // balances should now be zero
            Assertions.assertThat(savedAcct.numberPositiveBalances()).isZero();

            // Verify that the token rels were removed
            final var token444Rel = writableTokenRelStore.get(ACCOUNT_1339, token444Id);
            Assertions.assertThat(token444Rel).isNull();
            final var token555Rel = writableTokenRelStore.get(ACCOUNT_1339, TOKEN_555_ID);
            Assertions.assertThat(token555Rel).isNull();
            final var token666Rel = writableTokenRelStore.get(ACCOUNT_1339, TOKEN_666_ID);
            Assertions.assertThat(token666Rel).isNull();
        }
    }

    private HandleContext mockContext() {
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.expirationStatus(notNull(), anyBoolean(), anyLong()))
                .willReturn(ResponseCodeEnum.OK);

        given(handleContext.consensusNow()).willReturn(Instant.ofEpochMilli(0L));

        final var storeFactory = mock(StoreFactory.class);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        lenient().when(storeFactory.writableStore(WritableAccountStore.class)).thenReturn(writableAccountStore);
        lenient().when(storeFactory.readableStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        lenient()
                .when(storeFactory.writableStore(WritableTokenRelationStore.class))
                .thenReturn(writableTokenRelStore);

        return handleContext;
    }

    private TransactionBody newDissociateTxn(AccountID account, List<TokenID> tokens) {
        TokenDissociateTransactionBody.Builder associateTxnBodyBuilder = TokenDissociateTransactionBody.newBuilder();
        if (account != null) associateTxnBodyBuilder.account(account);
        if (tokens != null) associateTxnBodyBuilder.tokens(tokens);
        return TransactionBody.newBuilder()
                .transactionID(
                        TransactionID.newBuilder().accountID(ACCOUNT_1339).build())
                .tokenDissociate(associateTxnBodyBuilder)
                .build();
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DOES_NOT_OWN_WIPED_NFT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_WIPING_AMOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newWritableStoreWithAccounts;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newWritableStoreWithNfts;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newWritableStoreWithTokenRels;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newWritableStoreWithTokens;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_WIPE_KT;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.TokenBurnTransactionBody;
import com.hedera.hapi.node.token.TokenWipeAccountTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler;
import com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenAccountWipeHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.ParityTestBase;
import com.hedera.node.app.service.token.impl.validators.TokenSupplyChangeOpsValidator;
import com.hedera.node.app.service.token.records.TokenAccountWipeStreamBuilder;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenAccountWipeHandlerTest extends ParityTestBase {

    private static final AccountID ACCOUNT_4680 = BaseCryptoHandler.asAccount(0L, 0L, 4680);
    private static final AccountID TREASURY_ACCOUNT_9876 = BaseCryptoHandler.asAccount(0L, 0L, 9876);
    private static final TokenID TOKEN_531 = BaseTokenHandler.asToken(531);
    private final TokenSupplyChangeOpsValidator validator = new TokenSupplyChangeOpsValidator();
    private final TokenAccountWipeHandler subject = new TokenAccountWipeHandler(validator);

    private Configuration configuration;

    @Mock
    private TokenAccountWipeStreamBuilder recordBuilder;

    @Mock
    private PureChecksContext pureChecksContext;

    @BeforeEach
    @Override
    public void setUp() {
        super.setUp();
        configuration = HederaTestConfigBuilder.create()
                .withValue("tokens.nfts.maxBatchSizeWipe", 100)
                .getOrCreateConfig();
    }

    @Nested
    class PureChecks {
        @SuppressWarnings("DataFlowIssue")
        @Test
        void nullArgsThrows() {
            assertThatThrownBy(() -> subject.pureChecks(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void noWipeTxnPresent() {
            final var nonWipeTxnBody = TokenBurnTransactionBody.newBuilder();
            final var txn = TransactionBody.newBuilder()
                    .transactionID(
                            TransactionID.newBuilder().accountID(ACCOUNT_4680).build())
                    .tokenBurn(nonWipeTxnBody)
                    .build();
            given(pureChecksContext.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.pureChecks(pureChecksContext)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void noAccountIdPresent() {
            final var txn = newWipeTxn(null, TOKEN_531, 1);
            given(pureChecksContext.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_ACCOUNT_ID));
        }

        @Test
        void noTokenPresent() {
            final var txn = newWipeTxn(ACCOUNT_4680, null, 1);
            given(pureChecksContext.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_TOKEN_ID));
        }

        @Test
        void fungibleAndNonFungibleGiven() {
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 1, 1L);
            given(pureChecksContext.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_TRANSACTION_BODY));
        }

        @Test
        void nonPositiveFungibleAmountGiven() {
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, -1);
            given(pureChecksContext.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_WIPING_AMOUNT));
        }

        @Test
        void emptyNftSerialNumbers() {
            // This is a success case
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 0);
            given(pureChecksContext.body()).willReturn(txn);

            assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        }

        @Test
        void invalidNftSerialNumber() {
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 0, 1L, 2L, 0L);
            given(pureChecksContext.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_NFT_ID));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Handle {
        @Mock
        private ExpiryValidator validator;

        private WritableTokenStore writableTokenStore;
        private WritableNftStore writableNftStore;

        @SuppressWarnings("DataFlowIssue")
        @Test
        void nullArgsThrows() {
            assertThatThrownBy(() -> subject.handle(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void invalidFungibleAmount() {

            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, -1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_WIPING_AMOUNT));
        }

        @Test
        void accountDoesntExist() {
            // Both stores are intentionally empty
            writableAccountStore = newWritableStoreWithAccounts();
            writableTokenStore = newWritableStoreWithTokens();
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_ACCOUNT_ID));
        }

        @Test
        void nftAmountExceedsBatchSize() {
            configuration = HederaTestConfigBuilder.create()
                    .withValue("tokens.nfts.maxBatchSizeWipe", 2)
                    .getOrCreateConfig();
            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder().accountId(ACCOUNT_4680).build(),
                    Account.newBuilder().accountId(TREASURY_ACCOUNT_9876).build());
            writableTokenStore = newWritableStoreWithTokens();
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 0, 1L, 2L, 3L);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(BATCH_SIZE_LIMIT_EXCEEDED));
        }

        @Test
        void tokenIdNotFound() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder().accountId(ACCOUNT_4680).build(),
                    Account.newBuilder().accountId(TREASURY_ACCOUNT_9876).build());
            writableTokenStore = newWritableStoreWithTokens(); // Intentionally empty
            final var txn = newWipeTxn(ACCOUNT_4680, BaseTokenHandler.asToken(999), 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TOKEN_ID));
        }

        @Test
        void tokenIsDeleted() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder().accountId(ACCOUNT_4680).build(),
                    Account.newBuilder().accountId(TREASURY_ACCOUNT_9876).build());
            writableTokenStore = newWritableStoreWithTokens(newFungibleToken531(5)
                    .copyBuilder()
                    .deleted(true) // Intentionally deleted
                    .build());
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_WAS_DELETED));
        }

        @Test
        void tokenIsPaused() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder().accountId(ACCOUNT_4680).build(),
                    Account.newBuilder().accountId(TREASURY_ACCOUNT_9876).build());
            writableTokenStore = newWritableStoreWithTokens(newNftToken531(5)
                    .copyBuilder()
                    .paused(true) // Intentionally paused
                    .build());
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_IS_PAUSED));
        }

        @Test
        void tokenDoesntHaveWipeKey() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder().accountId(ACCOUNT_4680).build(),
                    Account.newBuilder().accountId(TREASURY_ACCOUNT_9876).build());
            final var totalFungibleSupply = 5;
            writableTokenStore = newWritableStoreWithTokens(newFungibleToken531(totalFungibleSupply)
                    .copyBuilder()
                    .wipeKey((Key) null) // Intentionally missing wipe key
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(newAccount4680Token531Rel(totalFungibleSupply));
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, totalFungibleSupply + 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_HAS_NO_WIPE_KEY));
        }

        @Test
        void accountRelDoesntExist() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder().accountId(ACCOUNT_4680).build(),
                    Account.newBuilder().accountId(TREASURY_ACCOUNT_9876).build());
            writableTokenStore = newWritableStoreWithTokens(newNftToken531(5));
            // Intentionally has no token rels:
            writableTokenRelStore = newWritableStoreWithTokenRels();
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
        }

        @Test
        void givenAccountIsTreasury() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder().accountId(ACCOUNT_4680).build(),
                    Account.newBuilder().accountId(TREASURY_ACCOUNT_9876).build());
            writableTokenStore = newWritableStoreWithTokens(newNftToken531(5));
            writableTokenRelStore = newWritableStoreWithTokenRels(newTreasuryToken531Rel(0));
            final var txn = newWipeTxn(TREASURY_ACCOUNT_9876, TOKEN_531, 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT));
        }

        @Test
        void fungibleAmountNegatesSupply() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder()
                            .accountId(ACCOUNT_4680)
                            .numberTreasuryTitles(0)
                            .numberPositiveBalances(1)
                            .build(),
                    Account.newBuilder()
                            .accountId(TREASURY_ACCOUNT_9876)
                            .numberTreasuryTitles(1)
                            .numberPositiveBalances(0)
                            .build());
            final var totalTokenSupply = 5;
            writableTokenStore = newWritableStoreWithTokens(newFungibleToken531(totalTokenSupply));
            writableTokenRelStore = newWritableStoreWithTokenRels(
                    newTreasuryToken531Rel(0), newAccount4680Token531Rel(totalTokenSupply));
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, totalTokenSupply + 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_WIPING_AMOUNT));
        }

        @Test
        void fungibleAmountNegatesBalance() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder()
                            .accountId(ACCOUNT_4680)
                            .numberTreasuryTitles(0)
                            .numberPositiveBalances(1)
                            .build(),
                    Account.newBuilder()
                            .accountId(TREASURY_ACCOUNT_9876)
                            .numberTreasuryTitles(1)
                            .numberPositiveBalances(0)
                            .build());
            final var currentTokenBalance = 3;
            writableTokenStore = newWritableStoreWithTokens(newFungibleToken531(currentTokenBalance + 2));
            writableTokenRelStore = newWritableStoreWithTokenRels(
                    newTreasuryToken531Rel(0), newAccount4680Token531Rel(currentTokenBalance));
            // The fungible amount is less than the total token supply, but one more than the token balance. Therefore,
            // we should see an error thrown
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, currentTokenBalance + 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_WIPING_AMOUNT));
        }

        @Test
        void fungibleAmountBurnedWithLeftoverAccountBalance() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder()
                            .accountId(ACCOUNT_4680)
                            .numberTreasuryTitles(0)
                            .numberPositiveBalances(1)
                            .build(),
                    Account.newBuilder()
                            .accountId(TREASURY_ACCOUNT_9876)
                            .numberTreasuryTitles(1)
                            .numberPositiveBalances(1)
                            .build());
            writableTokenStore = newWritableStoreWithTokens(newFungibleToken531(5));
            writableTokenRelStore =
                    newWritableStoreWithTokenRels(newTreasuryToken531Rel(1), newAccount4680Token531Rel(4));
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 3);
            final var context = mockContext(txn);

            subject.handle(context);

            final var acct = writableAccountStore.get(ACCOUNT_4680);
            Assertions.assertThat(acct.numberPositiveBalances()).isEqualTo(1);
            final var treasuryAcct = writableAccountStore.get(TREASURY_ACCOUNT_9876);
            Assertions.assertThat(treasuryAcct.numberTreasuryTitles()).isEqualTo(1);
            Assertions.assertThat(treasuryAcct.numberPositiveBalances()).isEqualTo(1);
            final var token = writableTokenStore.get(TOKEN_531);
            Assertions.assertThat(token.totalSupply()).isEqualTo(2);
            final var acctTokenRel = writableTokenRelStore.get(ACCOUNT_4680, TOKEN_531);
            Assertions.assertThat(acctTokenRel.balance()).isEqualTo(1);
            final var treasuryTokenRel = writableTokenRelStore.get(TREASURY_ACCOUNT_9876, TOKEN_531);
            // Nothing should've happened to the treasury token balance
            Assertions.assertThat(treasuryTokenRel.balance()).isEqualTo(1);
        }

        @Test
        void fungibleAmountBurnedWithNoLeftoverAccountBalance() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder()
                            .accountId(ACCOUNT_4680)
                            .numberTreasuryTitles(0)
                            .numberPositiveBalances(1)
                            .build(),
                    Account.newBuilder()
                            .accountId(TREASURY_ACCOUNT_9876)
                            .numberTreasuryTitles(1)
                            .numberPositiveBalances(1)
                            .build());
            writableTokenStore = newWritableStoreWithTokens(newFungibleToken531(5));
            writableTokenRelStore =
                    newWritableStoreWithTokenRels(newTreasuryToken531Rel(3), newAccount4680Token531Rel(2));
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 2);
            final var context = mockContext(txn);

            subject.handle(context);

            final var acct = writableAccountStore.get(ACCOUNT_4680);
            Assertions.assertThat(acct.numberPositiveBalances()).isZero();
            final var treasuryAcct = writableAccountStore.get(TREASURY_ACCOUNT_9876);
            Assertions.assertThat(treasuryAcct.numberTreasuryTitles()).isEqualTo(1);
            Assertions.assertThat(treasuryAcct.numberPositiveBalances()).isEqualTo(1);
            final var token = writableTokenStore.get(TOKEN_531);
            Assertions.assertThat(token.totalSupply()).isEqualTo(3);
            final var acctTokenRel = writableTokenRelStore.get(ACCOUNT_4680, TOKEN_531);
            Assertions.assertThat(acctTokenRel.balance()).isZero();
            final var treasuryTokenRel = writableTokenRelStore.get(TREASURY_ACCOUNT_9876, TOKEN_531);
            // Nothing should've happened to the treasury token balance
            Assertions.assertThat(treasuryTokenRel.balance()).isEqualTo(3);
        }

        @Test
        void nftSerialNumDoesntExist() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder().accountId(ACCOUNT_4680).build(),
                    Account.newBuilder()
                            .accountId(TREASURY_ACCOUNT_9876)
                            .numberTreasuryTitles(1)
                            .build());
            writableTokenStore = newWritableStoreWithTokens(newNftToken531(10));
            writableTokenRelStore = newWritableStoreWithTokenRels(newAccount4680Token531Rel(0));
            writableNftStore = newWritableStoreWithNfts(); // Intentionally empty

            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 0, 1L);
            final var context = mockContext(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_NFT_ID));
        }

        @Test
        void nftNotOwnedByAccount() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder().accountId(ACCOUNT_4680).build(),
                    Account.newBuilder()
                            .accountId(TREASURY_ACCOUNT_9876)
                            .numberTreasuryTitles(1)
                            .build());
            writableTokenStore = newWritableStoreWithTokens(newNftToken531(10));
            writableTokenRelStore = newWritableStoreWithTokenRels(newAccount4680Token531Rel(0));
            writableNftStore = newWritableStoreWithNfts(Nft.newBuilder()
                    .nftId(NftID.newBuilder().tokenId(TOKEN_531).serialNumber(1).build())
                    .ownerId(TREASURY_ACCOUNT_9876)
                    .build());

            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 0, 1L);
            final var context = mockContext(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(ACCOUNT_DOES_NOT_OWN_WIPED_NFT));
        }

        @Test
        void numNftSerialsNegatesSupply() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder()
                            .accountId(ACCOUNT_4680)
                            .numberTreasuryTitles(0)
                            .numberPositiveBalances(1)
                            .build(),
                    Account.newBuilder()
                            .accountId(TREASURY_ACCOUNT_9876)
                            .numberTreasuryTitles(1)
                            .numberPositiveBalances(0)
                            .build());
            final var totalTokenSupply = 1;
            writableTokenStore = newWritableStoreWithTokens(newNftToken531(totalTokenSupply));
            writableTokenRelStore = newWritableStoreWithTokenRels(
                    newTreasuryToken531Rel(0), newAccount4680Token531Rel(totalTokenSupply));
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 0, 1L, 2L);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_WIPING_AMOUNT));
        }

        @Test
        void nftSerialNumsIsEmpty() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder()
                            .accountId(ACCOUNT_4680)
                            .numberTreasuryTitles(0)
                            .numberPositiveBalances(1)
                            .build(),
                    Account.newBuilder()
                            .accountId(TREASURY_ACCOUNT_9876)
                            .numberTreasuryTitles(1)
                            .numberPositiveBalances(0)
                            .build());
            writableTokenStore = newWritableStoreWithTokens(newNftToken531(5));
            writableTokenRelStore =
                    newWritableStoreWithTokenRels(newTreasuryToken531Rel(0), newAccount4680Token531Rel(5));
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 0);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_WIPING_AMOUNT));
        }

        @Test
        void nftSerialsWipedWithLeftoverNftSerials() {
            // i.e. leftover NFT serials remaining with the owning account

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder()
                            .accountId(ACCOUNT_4680)
                            .numberTreasuryTitles(0)
                            .numberPositiveBalances(1)
                            .numberOwnedNfts(3)
                            .build(),
                    Account.newBuilder()
                            .accountId(TREASURY_ACCOUNT_9876)
                            .numberTreasuryTitles(1)
                            .numberPositiveBalances(1)
                            .numberOwnedNfts(1)
                            .build());
            writableTokenStore = newWritableStoreWithTokens(newNftToken531(10));
            writableTokenRelStore =
                    newWritableStoreWithTokenRels(newAccount4680Token531Rel(3), newTreasuryToken531Rel(1));
            writableNftStore = newWritableStoreWithNfts(
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_531)
                                    .serialNumber(1)
                                    .build())
                            // do not set ownerId - default to null, meaning treasury owns this NFT
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_531)
                                    .serialNumber(2)
                                    .build())
                            .ownerId(ACCOUNT_4680)
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_531)
                                    .serialNumber(3)
                                    .build())
                            .ownerId(ACCOUNT_4680)
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_531)
                                    .serialNumber(4)
                                    .build())
                            .ownerId(ACCOUNT_4680)
                            .build());
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 0, 2L, 3L);
            final var context = mockContext(txn);

            subject.handle(context);

            final var acct = writableAccountStore.get(ACCOUNT_4680);
            Assertions.assertThat(acct.numberOwnedNfts()).isEqualTo(1);
            Assertions.assertThat(acct.numberPositiveBalances()).isEqualTo(1);
            final var treasuryAcct = writableAccountStore.get(TREASURY_ACCOUNT_9876);
            // The treasury still owns its NFT, so its counts shouldn't change
            Assertions.assertThat(treasuryAcct.numberOwnedNfts()).isEqualTo(1);
            Assertions.assertThat(treasuryAcct.numberTreasuryTitles()).isEqualTo(1);
            Assertions.assertThat(treasuryAcct.numberPositiveBalances()).isEqualTo(1);
            final var token = writableTokenStore.get(TOKEN_531);
            // Verify that 2 NFTs were removed from the total supply
            Assertions.assertThat(token.totalSupply()).isEqualTo(8);
            final var tokenRel = writableTokenRelStore.get(ACCOUNT_4680, TOKEN_531);
            Assertions.assertThat(tokenRel.balance()).isEqualTo(1);
            // Verify the treasury's NFT wasn't removed
            Assertions.assertThat(writableNftStore.get(new NftID(TOKEN_531, 1))).isNotNull();
            // Verify that two of the account's NFTs were removed, and that the final one remains
            Assertions.assertThat(writableNftStore.get(new NftID(TOKEN_531, 2))).isNull();
            Assertions.assertThat(writableNftStore.get(new NftID(TOKEN_531, 3))).isNull();
            Assertions.assertThat(writableNftStore.get(new NftID(TOKEN_531, 4))).isNotNull();
        }

        @Test
        void nftSerialsWipedWithNoLeftoverNftSerials() {

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder()
                            .accountId(ACCOUNT_4680)
                            .numberTreasuryTitles(0)
                            .numberPositiveBalances(1)
                            .numberOwnedNfts(3)
                            .build(),
                    Account.newBuilder()
                            .accountId(TREASURY_ACCOUNT_9876)
                            .numberTreasuryTitles(1)
                            .numberPositiveBalances(1)
                            .numberOwnedNfts(1)
                            .build());
            writableTokenStore = newWritableStoreWithTokens(newNftToken531(10));
            writableTokenRelStore =
                    newWritableStoreWithTokenRels(newAccount4680Token531Rel(3), newTreasuryToken531Rel(1));
            writableNftStore = newWritableStoreWithNfts(
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_531)
                                    .serialNumber(1)
                                    .build())
                            // do not set ownerId - default to null, meaning treasury owns this NFT
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_531)
                                    .serialNumber(2)
                                    .build())
                            .ownerId(ACCOUNT_4680)
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_531)
                                    .serialNumber(3)
                                    .build())
                            .ownerId(ACCOUNT_4680)
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_531)
                                    .serialNumber(4)
                                    .build())
                            .ownerId(ACCOUNT_4680)
                            .build());
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 0, 2L, 3L, 4L);
            final var context = mockContext(txn);

            subject.handle(context);

            final var acct = writableAccountStore.get(ACCOUNT_4680);
            Assertions.assertThat(acct.numberOwnedNfts()).isZero();
            Assertions.assertThat(acct.numberPositiveBalances()).isZero();
            final var treasuryAcct = writableAccountStore.get(TREASURY_ACCOUNT_9876);
            // The treasury still owns its NFT, so its counts shouldn't change
            Assertions.assertThat(treasuryAcct.numberOwnedNfts()).isEqualTo(1);
            Assertions.assertThat(treasuryAcct.numberTreasuryTitles()).isEqualTo(1);
            Assertions.assertThat(treasuryAcct.numberPositiveBalances()).isEqualTo(1);
            final var token = writableTokenStore.get(TOKEN_531);
            // Verify that 3 NFTs were removed from the total supply
            Assertions.assertThat(token.totalSupply()).isEqualTo(7);
            final var tokenRel = writableTokenRelStore.get(ACCOUNT_4680, TOKEN_531);
            Assertions.assertThat(tokenRel.balance()).isZero();
            // Verify the treasury's NFT wasn't removed
            Assertions.assertThat(writableNftStore.get(new NftID(TOKEN_531, 1))).isNotNull();
            // Verify that the account's NFTs were removed
            Assertions.assertThat(writableNftStore.get(new NftID(TOKEN_531, 2))).isNull();
            Assertions.assertThat(writableNftStore.get(new NftID(TOKEN_531, 3))).isNull();
            Assertions.assertThat(writableNftStore.get(new NftID(TOKEN_531, 4))).isNull();
        }

        @Test
        void duplicateNftSerials() {
            // This is a success case, and should be identical to the case without no duplicates above

            mockOkExpiryValidator();
            writableAccountStore = newWritableStoreWithAccounts(
                    Account.newBuilder()
                            .accountId(ACCOUNT_4680)
                            .numberTreasuryTitles(0)
                            .numberPositiveBalances(1)
                            .numberOwnedNfts(3)
                            .build(),
                    Account.newBuilder()
                            .accountId(TREASURY_ACCOUNT_9876)
                            .numberTreasuryTitles(1)
                            .numberPositiveBalances(1)
                            .numberOwnedNfts(1)
                            .build());
            writableTokenStore = newWritableStoreWithTokens(newNftToken531(10));
            writableTokenRelStore =
                    newWritableStoreWithTokenRels(newAccount4680Token531Rel(3), newTreasuryToken531Rel(1));
            writableNftStore = newWritableStoreWithNfts(
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_531)
                                    .serialNumber(1)
                                    .build())
                            // do not set ownerId - default to null, meaning treasury owns this NFT
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_531)
                                    .serialNumber(2)
                                    .build())
                            .ownerId(ACCOUNT_4680)
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_531)
                                    .serialNumber(3)
                                    .build())
                            .ownerId(ACCOUNT_4680)
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_531)
                                    .serialNumber(4)
                                    .build())
                            .ownerId(ACCOUNT_4680)
                            .build());
            final var txn = newWipeTxn(ACCOUNT_4680, TOKEN_531, 0, 2L, 2L, 3L, 3L, 4L, 4L, 2L, 3L, 4L);
            final var context = mockContext(txn);

            subject.handle(context);

            final var acct = writableAccountStore.get(ACCOUNT_4680);
            Assertions.assertThat(acct.numberOwnedNfts()).isZero();
            Assertions.assertThat(acct.numberPositiveBalances()).isZero();
            final var treasuryAcct = writableAccountStore.get(TREASURY_ACCOUNT_9876);
            // The treasury still owns its NFT, so its counts shouldn't change
            Assertions.assertThat(treasuryAcct.numberOwnedNfts()).isEqualTo(1);
            Assertions.assertThat(treasuryAcct.numberTreasuryTitles()).isEqualTo(1);
            Assertions.assertThat(treasuryAcct.numberPositiveBalances()).isEqualTo(1);
            final var token = writableTokenStore.get(TOKEN_531);
            // Verify that 3 NFTs were removed from the total supply
            Assertions.assertThat(token.totalSupply()).isEqualTo(7);
            final var tokenRel = writableTokenRelStore.get(ACCOUNT_4680, TOKEN_531);
            Assertions.assertThat(tokenRel.balance()).isZero();
            // Verify the treasury's NFT wasn't removed
            Assertions.assertThat(writableNftStore.get(new NftID(TOKEN_531, 1))).isNotNull();
            // Verify that the account's NFTs were removed
            Assertions.assertThat(writableNftStore.get(new NftID(TOKEN_531, 2))).isNull();
            Assertions.assertThat(writableNftStore.get(new NftID(TOKEN_531, 3))).isNull();
            Assertions.assertThat(writableNftStore.get(new NftID(TOKEN_531, 4))).isNull();
        }

        private Token newFungibleToken531(final long totalSupply) {
            return newToken531(TokenType.FUNGIBLE_COMMON, totalSupply);
        }

        private Token newNftToken531(final long totalSupply) {
            return newToken531(TokenType.NON_FUNGIBLE_UNIQUE, totalSupply);
        }

        private Token newToken531(final TokenType type, final long totalSupply) {
            return Token.newBuilder()
                    .tokenId(TOKEN_531)
                    .tokenType(type)
                    .treasuryAccountId(TREASURY_ACCOUNT_9876)
                    .wipeKey(TOKEN_WIPE_KT.asPbjKey())
                    .totalSupply(totalSupply)
                    .build();
        }

        private TokenRelation newTreasuryToken531Rel(final long balance) {
            return newToken531Rel(TREASURY_ACCOUNT_9876, balance);
        }

        private TokenRelation newAccount4680Token531Rel(final long balance) {
            return newToken531Rel(ACCOUNT_4680, balance);
        }

        private TokenRelation newToken531Rel(final AccountID accountId, final long balance) {
            final var builder = TokenRelation.newBuilder().accountId(accountId).tokenId(TOKEN_531);
            if (balance > 0) builder.balance(balance);
            return builder.build();
        }

        private void mockOkExpiryValidator() {
            given(validator.expirationStatus(notNull(), anyBoolean(), anyLong()))
                    .willReturn(OK);
        }

        private HandleContext mockContext(TransactionBody txn) {
            final var context = mock(HandleContext.class);
            final var stack = mock(HandleContext.SavepointStack.class);

            given(context.body()).willReturn(txn);

            final var storeFactory = mock(StoreFactory.class);
            given(context.storeFactory()).willReturn(storeFactory);
            given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
            given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
            given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);
            given(storeFactory.writableStore(WritableNftStore.class)).willReturn(writableNftStore);
            given(context.configuration()).willReturn(configuration);

            given(context.expiryValidator()).willReturn(validator);

            lenient().when(context.savepointStack()).thenReturn(stack);
            lenient()
                    .when(stack.getBaseBuilder(TokenAccountWipeStreamBuilder.class))
                    .thenReturn(recordBuilder);

            return context;
        }
    }

    private TransactionBody newWipeTxn(AccountID accountId, TokenID token, long fungibleAmount, Long... nftSerialNums) {
        final TokenWipeAccountTransactionBody.Builder wipeTxnBodyBuilder = TokenWipeAccountTransactionBody.newBuilder();
        if (accountId != null) wipeTxnBodyBuilder.account(accountId);
        if (token != null) wipeTxnBodyBuilder.token(token);
        wipeTxnBodyBuilder.amount(fungibleAmount);
        wipeTxnBodyBuilder.serialNumbers(nftSerialNums);
        return TransactionBody.newBuilder()
                .transactionID(
                        TransactionID.newBuilder().accountID(ACCOUNT_4680).build())
                .tokenWipe(wipeTxnBodyBuilder)
                .build();
    }
}

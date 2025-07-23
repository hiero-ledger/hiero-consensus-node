// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_BURN_AMOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_BURN_METADATA;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TREASURY_MUST_OWN_BURNED_NFT;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.NFTS_KEY;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newWritableStoreWithAccounts;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newWritableStoreWithNfts;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newWritableStoreWithTokenRels;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newWritableStoreWithTokens;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_SUPPLY_KT;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.hedera.hapi.node.token.TokenAssociateTransactionBody;
import com.hedera.hapi.node.token.TokenBurnTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler;
import com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenBurnHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.ParityTestBase;
import com.hedera.node.app.service.token.impl.validators.TokenSupplyChangeOpsValidator;
import com.hedera.node.app.service.token.records.TokenBurnStreamBuilder;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenBurnHandlerTest extends ParityTestBase {

    private static final AccountID ACCOUNT_1339 = BaseCryptoHandler.asAccount(0L, 0L, 1339);
    private static final TokenID TOKEN_123 = BaseTokenHandler.asToken(123);
    private TokenSupplyChangeOpsValidator validator = new TokenSupplyChangeOpsValidator();
    private final TokenBurnHandler subject = new TokenBurnHandler(validator);
    private Configuration configuration;

    @Mock
    private PureChecksContext pureChecksContext;

    @Override
    @BeforeEach
    public void setUp() {
        super.setUp();
        configuration = HederaTestConfigBuilder.create()
                .withValue("tokens.nfts.maxBatchSizeBurn", 100)
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
        void noBurnTxnPresent() {
            final var nonBurnTxnBody = TokenAssociateTransactionBody.newBuilder();
            final var txn = TransactionBody.newBuilder()
                    .transactionID(
                            TransactionID.newBuilder().accountID(ACCOUNT_1339).build())
                    .tokenAssociate(nonBurnTxnBody)
                    .build();
            given(pureChecksContext.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.pureChecks(pureChecksContext)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void noTokenPresent() {
            final var txn = newBurnTxn(null, 1);
            given(pureChecksContext.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_TOKEN_ID));
        }

        @Test
        void fungibleAndNonFungibleGiven() {
            final var txn = newBurnTxn(TOKEN_123, 1, 1L);
            given(pureChecksContext.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_TRANSACTION_BODY));
        }

        @Test
        void nonPositiveFungibleAmountGiven() {
            final var txn = newBurnTxn(TOKEN_123, -1);
            given(pureChecksContext.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_TOKEN_BURN_AMOUNT));
        }

        @Test
        void emptyNftSerialNumbers() {
            // This is a success case
            final var txn = newBurnTxn(TOKEN_123, 0);
            given(pureChecksContext.body()).willReturn(txn);

            assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        }

        @Test
        void invalidNftSerialNumber() {
            final var txn = newBurnTxn(TOKEN_123, 0, 1L, 2L, 0L);
            given(pureChecksContext.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_NFT_ID));
        }
    }

    @Nested
    class Handle {
        private WritableTokenStore writableTokenStore;
        private WritableNftStore writableNftStore;

        @SuppressWarnings("DataFlowIssue")
        @Test
        void nullArg() {
            assertThatThrownBy(() -> subject.handle(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void invalidFungibleAmount() {

            final var txn = newBurnTxn(TOKEN_123, -1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TOKEN_BURN_AMOUNT));
        }

        @Test
        void tokenIdNotFound() {

            writableTokenStore = newWritableStoreWithTokens();
            final var txn = newBurnTxn(BaseTokenHandler.asToken(999), 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TOKEN_ID));
        }

        @Test
        void tokenIsDeleted() {

            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .deleted(true) // Intentionally deleted
                    .build());

            final var txn = newBurnTxn(TOKEN_123, 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_WAS_DELETED));
        }

        @Test
        void tokenIsPaused() {

            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .paused(true) // Intentionally paused
                    .build());
            final var txn = newBurnTxn(TOKEN_123, 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_IS_PAUSED));
        }

        @Test
        void tokenDoesntHaveSupplyKey() {

            final var totalFungibleSupply = 5;
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey((Key) null) // Intentionally missing supply key
                    .totalSupply(totalFungibleSupply)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(totalFungibleSupply)
                    .build());
            final var txn = newBurnTxn(TOKEN_123, totalFungibleSupply + 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_HAS_NO_SUPPLY_KEY));
        }

        @Test
        void tokenTreasuryRelDoesntExist() {

            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .build());
            // Intentionally has no token rels:
            writableTokenRelStore = newWritableStoreWithTokenRels();
            final var txn = newBurnTxn(TOKEN_123, 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
        }

        @Test
        void fungibleTokenTreasuryAccountDoesntExist() {

            // Intentionally has no treasury account:
            writableAccountStore = newWritableStoreWithAccounts();
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(10)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(10)
                    .build());
            final var txn = newBurnTxn(TOKEN_123, 10);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TREASURY_ACCOUNT_FOR_TOKEN));
        }

        @Test
        void fungibleAmountExceedsSupply() {

            final var totalFungibleSupply = 5;
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(totalFungibleSupply)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(totalFungibleSupply)
                    .build());
            final var txn = newBurnTxn(TOKEN_123, totalFungibleSupply + 1);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TOKEN_BURN_AMOUNT));
        }

        @Test
        void fungibleAmountExceedsBalance() {
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(10)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(8)
                    .build());
            // The token treasury has a balance of 8. The token supply is 10, so a fungible amount of 9 exceed the total
            // supply of available tokens, but 9 is one more than the current treasury balance, so this scenario should
            // throw an exception
            final var txn = newBurnTxn(TOKEN_123, 9);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INSUFFICIENT_TOKEN_BALANCE));
        }

        @Test
        void fungibleAmountBurnedWithLeftoverTreasuryBalance() {
            writableAccountStore = newWritableStoreWithAccounts(Account.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .numberTreasuryTitles(1)
                    .numberPositiveBalances(1)
                    .build());
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(10)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(9)
                    .build());
            final var txn = newBurnTxn(TOKEN_123, 8);
            final var context = mockContext(txn);
            final var recordBuilder = new RecordStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER);
            given(context.savepointStack().getBaseBuilder(TokenBurnStreamBuilder.class))
                    .willReturn(recordBuilder);

            subject.handle(context);

            final var updatedToken = writableTokenStore.get(TOKEN_123);
            // Total supply of 10 is reduced by the burn of 8 units, so the new total supply should be 2
            Assertions.assertThat(updatedToken.totalSupply()).isEqualTo(2);
            Assertions.assertThat(recordBuilder.getNewTotalSupply()).isEqualTo(2);
            final var updatedTreasuryRel = writableTokenRelStore.get(ACCOUNT_1339, TOKEN_123);
            Assertions.assertThat(updatedTreasuryRel.balance()).isEqualTo(1);
            final var updatedTreasuryAcct = writableAccountStore.get(ACCOUNT_1339);
            Assertions.assertThat(updatedTreasuryAcct.numberTreasuryTitles()).isEqualTo(1);
            // There is still a positive balance in the treasury account, so its positive balances shouldn't change
            Assertions.assertThat(updatedTreasuryAcct.numberPositiveBalances()).isEqualTo(1);
        }

        @Test
        void fungibleAmountBurnedWithZeroTreasuryBalance() {

            writableAccountStore = newWritableStoreWithAccounts(Account.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .numberTreasuryTitles(1)
                    .numberPositiveBalances(1)
                    .build());
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(10)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(8)
                    .build());
            final var txn = newBurnTxn(TOKEN_123, 8);
            final var context = mockContext(txn);
            final var recordBuilder = new RecordStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER);
            given(context.savepointStack().getBaseBuilder(TokenBurnStreamBuilder.class))
                    .willReturn(recordBuilder);

            subject.handle(context);

            final var updatedToken = writableTokenStore.get(TOKEN_123);
            // Total supply of 10 is reduced by the burn of 8 units, so the new total supply should be 2
            Assertions.assertThat(updatedToken.totalSupply()).isEqualTo(2);
            // The record also contains the new total supply
            Assertions.assertThat(recordBuilder.getNewTotalSupply()).isEqualTo(2);
            final var updatedTreasuryRel = writableTokenRelStore.get(ACCOUNT_1339, TOKEN_123);
            Assertions.assertThat(updatedTreasuryRel.balance()).isZero();
            final var updatedTreasuryAcct = writableAccountStore.get(ACCOUNT_1339);
            // The treasury account is still listed as the treasury for the token, so its number of treasury titles
            // shouldn't decrease
            Assertions.assertThat(updatedTreasuryAcct.numberTreasuryTitles()).isEqualTo(1);
            // There is no balance left in the treasury account, so its positive balances should be reduced
            Assertions.assertThat(updatedTreasuryAcct.numberPositiveBalances()).isZero();
        }

        @Test
        void nftSerialCountExceedsBatchSize() {
            configuration = HederaTestConfigBuilder.create()
                    .withValue("tokens.nfts.maxBatchSizeBurn", 1)
                    .getOrCreateConfig();
            validator = new TokenSupplyChangeOpsValidator();

            final var txn = newBurnTxn(TOKEN_123, 0, 1L, 2L);
            final var context = mockContext(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(BATCH_SIZE_LIMIT_EXCEEDED));
        }

        @Test
        void invalidNftSerial() {

            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(10)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .build());
            writableNftStore = newWritableStoreWithNfts();
            final var txn = newBurnTxn(TOKEN_123, 0, -1L);
            final var context = mockContext(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_NFT_ID));
        }

        @Test
        void nftSerialNotFound() {

            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(10)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(10)
                    .build());
            writableNftStore = new WritableNftStore(
                    new MapWritableStates(Map.of(
                            NFTS_KEY,
                            MapWritableKVState.builder(TokenService.NAME, NFTS_KEY)
                                    .build())),
                    mock(WritableEntityCounters.class));

            final var txn = newBurnTxn(TOKEN_123, 0, 1L);
            final var context = mockContext(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_NFT_ID));
        }

        @Test
        void nftSerialNumsEmpty() {

            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(10)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(10)
                    .build());
            final var txn = newBurnTxn(TOKEN_123, 0);
            final var context = mockContext(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TOKEN_BURN_METADATA));
        }

        @Test
        void nftNotOwnedByTreasury() {

            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(10)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(10)
                    .build());
            // this owner number isn't the treasury
            AccountID ownerId = AccountID.newBuilder().accountNum(999).build();
            writableNftStore = newWritableStoreWithNfts(Nft.newBuilder()
                    .nftId(NftID.newBuilder()
                            .tokenId(TOKEN_123)
                            .serialNumber(1L)
                            .build())
                    .ownerId(ownerId)
                    .build());

            final var txn = newBurnTxn(TOKEN_123, 0, 1L);
            final var context = mockContext(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TREASURY_MUST_OWN_BURNED_NFT));
        }

        @Test
        void nftTreasuryAccountDoesntExist() {

            // Intentionally has no treasury account:
            writableAccountStore = newWritableStoreWithAccounts();
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(10)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(10)
                    .build());
            writableNftStore = newWritableStoreWithNfts(Nft.newBuilder()
                    .nftId(NftID.newBuilder()
                            .tokenId(TOKEN_123)
                            .serialNumber(1L)
                            .build())
                    // do not set ownerId - default to null
                    .build());
            final var txn = newBurnTxn(TOKEN_123, 0, 1L);
            final var context = mockContext(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TREASURY_ACCOUNT_FOR_TOKEN));
        }

        @Test
        void numNftSerialsExceedsNftSupply() {

            writableAccountStore = newWritableStoreWithAccounts(Account.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .numberTreasuryTitles(1)
                    .numberPositiveBalances(1)
                    .build());
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(1)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(1)
                    .build());
            writableNftStore = newWritableStoreWithNfts(
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_123)
                                    .serialNumber(1L)
                                    .build())
                            // do not set ownerId - default to null
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_123)
                                    .serialNumber(2L)
                                    .build())
                            // do not set ownerId - default to null
                            .build());
            final var txn = newBurnTxn(TOKEN_123, 0, 1L, 2L);
            final var context = mockContext(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(FAIL_INVALID));
        }

        @Test
        void nftSerialsBurnedWithLeftoverTreasuryBalance() {

            writableAccountStore = newWritableStoreWithAccounts(Account.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .numberTreasuryTitles(1)
                    .numberPositiveBalances(1)
                    .numberOwnedNfts(3)
                    .build());
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(3)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(3)
                    .build());
            writableNftStore = newWritableStoreWithNfts(
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_123)
                                    .serialNumber(1L)
                                    .build())
                            // do not set ownerId - default to null
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_123)
                                    .serialNumber(2L)
                                    .build())
                            // do not set ownerId - default to null
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_123)
                                    .serialNumber(3L)
                                    .build())
                            // do not set ownerId - default to null
                            .build());
            final var txn = newBurnTxn(TOKEN_123, 0, 1L, 2L);
            final var context = mockContext(txn);
            final var recordBuilder = new RecordStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER);
            given(context.savepointStack().getBaseBuilder(TokenBurnStreamBuilder.class))
                    .willReturn(recordBuilder);

            subject.handle(context);
            final var treasuryAcct = writableAccountStore.get(ACCOUNT_1339);
            Assertions.assertThat(treasuryAcct.numberTreasuryTitles()).isEqualTo(1);
            // The treasury still owns at least one of the NFTs, so its positive balances shouldn't change
            Assertions.assertThat(treasuryAcct.numberPositiveBalances()).isEqualTo(1);
            Assertions.assertThat(treasuryAcct.numberOwnedNfts()).isEqualTo(1);
            final var treasuryRel = writableTokenRelStore.get(ACCOUNT_1339, TOKEN_123);
            Assertions.assertThat(treasuryRel.balance()).isEqualTo(1);
            Assertions.assertThat(writableNftStore.get(TOKEN_123, 1L)).isNull();
            Assertions.assertThat(writableNftStore.get(TOKEN_123, 2L)).isNull();
            // Only serials 1 and 2 were removed, not serial 3
            Assertions.assertThat(writableNftStore.get(TOKEN_123, 3L)).isNotNull();
        }

        @Test
        void nftSerialsBurnedWithNoLeftoverTreasuryBalance() {

            writableAccountStore = newWritableStoreWithAccounts(Account.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .numberTreasuryTitles(1)
                    .numberPositiveBalances(1)
                    .numberOwnedNfts(3)
                    .build());
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(10)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(3)
                    .build());
            writableNftStore = newWritableStoreWithNfts(
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_123)
                                    .serialNumber(1L)
                                    .build())
                            // do not set ownerId - default to null
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_123)
                                    .serialNumber(2L)
                                    .build())
                            // do not set ownerId - default to null
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_123)
                                    .serialNumber(3L)
                                    .build())
                            // do not set ownerId - default to null
                            .build());
            final var txn = newBurnTxn(TOKEN_123, 0, 1L, 2L, 3L);
            final var context = mockContext(txn);
            final var recordBuilder = new RecordStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER);
            given(context.savepointStack().getBaseBuilder(TokenBurnStreamBuilder.class))
                    .willReturn(recordBuilder);

            subject.handle(context);
            final var treasuryAcct = writableAccountStore.get(ACCOUNT_1339);
            Assertions.assertThat(treasuryAcct).isNotNull();
            Assertions.assertThat(treasuryAcct.numberTreasuryTitles()).isEqualTo(1);
            // The treasury no longer owns any NFTs, so its positive balances should decrease by 1
            Assertions.assertThat(treasuryAcct.numberPositiveBalances()).isZero();
            Assertions.assertThat(treasuryAcct.numberOwnedNfts()).isZero();
            final var treasuryRel = writableTokenRelStore.get(ACCOUNT_1339, TOKEN_123);
            Assertions.assertThat(treasuryRel).isNotNull();
            Assertions.assertThat(treasuryRel.balance()).isZero();
            Assertions.assertThat(writableNftStore.get(TOKEN_123, 1L)).isNull();
            Assertions.assertThat(writableNftStore.get(TOKEN_123, 2L)).isNull();
            Assertions.assertThat(writableNftStore.get(TOKEN_123, 3L)).isNull();
        }

        @Test
        void duplicateNftSerials() {
            // This is a success case, and should be identical to the case without no duplicates above

            writableAccountStore = newWritableStoreWithAccounts(Account.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .numberTreasuryTitles(1)
                    .numberPositiveBalances(1)
                    .build());
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_123)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .treasuryAccountId(ACCOUNT_1339)
                    .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                    .totalSupply(10)
                    .build());
            writableTokenRelStore = newWritableStoreWithTokenRels(TokenRelation.newBuilder()
                    .accountId(ACCOUNT_1339)
                    .tokenId(TOKEN_123)
                    .balance(3)
                    .build());
            writableNftStore = newWritableStoreWithNfts(
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_123)
                                    .serialNumber(1L)
                                    .build())
                            // do not set ownerId - default to null
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_123)
                                    .serialNumber(2L)
                                    .build())
                            // do not set ownerId - default to null
                            .build(),
                    Nft.newBuilder()
                            .nftId(NftID.newBuilder()
                                    .tokenId(TOKEN_123)
                                    .serialNumber(3L)
                                    .build())
                            // do not set ownerId - default to null
                            .build());
            final var txn = newBurnTxn(TOKEN_123, 0, 1L, 2L, 3L, 1L, 2L, 3L, 3L, 1L, 1L, 2L);
            final var context = mockContext(txn);
            final var recordBuilder = new RecordStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER);
            given(context.savepointStack().getBaseBuilder(TokenBurnStreamBuilder.class))
                    .willReturn(recordBuilder);

            subject.handle(context);
            final var treasuryAcct = writableAccountStore.get(ACCOUNT_1339);
            Assertions.assertThat(treasuryAcct).isNotNull();
            Assertions.assertThat(treasuryAcct.numberTreasuryTitles()).isEqualTo(1);
            // The treasury no longer owns any NFTs, so its positive balances should decrease by 1
            Assertions.assertThat(treasuryAcct.numberPositiveBalances()).isZero();
            final var treasuryRel = writableTokenRelStore.get(ACCOUNT_1339, TOKEN_123);
            Assertions.assertThat(treasuryRel).isNotNull();
            Assertions.assertThat(treasuryRel.balance()).isZero();
            Assertions.assertThat(writableNftStore.get(TOKEN_123, 1L)).isNull();
            Assertions.assertThat(writableNftStore.get(TOKEN_123, 2L)).isNull();
            Assertions.assertThat(writableNftStore.get(TOKEN_123, 3L)).isNull();
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
            lenient().when(context.savepointStack()).thenReturn(stack);
            final var expiryValidator = mock(ExpiryValidator.class);
            lenient().when(context.expiryValidator()).thenReturn(expiryValidator);
            lenient()
                    .when(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong()))
                    .thenReturn(OK);

            return context;
        }
    }

    private TransactionBody newBurnTxn(TokenID token, long fungibleAmount, Long... nftSerialNums) {
        TokenBurnTransactionBody.Builder burnTxnBodyBuilder = TokenBurnTransactionBody.newBuilder();
        if (token != null) burnTxnBodyBuilder.token(token);
        burnTxnBodyBuilder.amount(fungibleAmount);
        burnTxnBodyBuilder.serialNumbers(nftSerialNums);
        return TransactionBody.newBuilder()
                .transactionID(
                        TransactionID.newBuilder().accountID(ACCOUNT_1339).build())
                .tokenBurn(burnTxnBodyBuilder)
                .build();
    }
}

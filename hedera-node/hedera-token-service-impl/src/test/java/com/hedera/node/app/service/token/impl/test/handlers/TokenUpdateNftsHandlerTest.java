// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_METADATA_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_METADATA_OR_SUPPLY_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.NFTS_KEY;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newReadableStoreWithTokens;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newWritableStoreWithTokenRels;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newWritableStoreWithTokens;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_SUPPLY_KT;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.TokenUpdateNftsTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler;
import com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUpdateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUpdateNftsHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.TokenAttributesValidator;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.workflows.handle.validation.AttributeValidatorImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenUpdateNftsHandlerTest extends CryptoTokenHandlerTestBase {
    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = LENIENT)
    private ConfigProvider configProvider;

    @Mock
    private AttributeValidator attributeValidator;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private TokenUpdateNftsTransactionBody tokenUpdateNftsTransactionBody;

    @Mock
    private ReadableTokenStore readableTokenStore;

    @Mock
    private ReadableNftStore readableNftStore;

    @Mock
    private Token token;

    @Mock
    private Nft nft;

    @Mock
    private Key metadataKey;

    @Mock
    private Key supplyKey;

    @Mock
    private PureChecksContext pureChecksContext;

    private TokenUpdateNftsHandler subject;
    private TransactionBody txn;
    private static final AccountID ACCOUNT_1339 = BaseCryptoHandler.asAccount(0L, 0L, 1339);
    private static final TokenID TOKEN_123 = BaseTokenHandler.asToken(123);

    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshWritableStores();
        final TokenAttributesValidator validator = new TokenAttributesValidator();
        subject = new TokenUpdateNftsHandler(validator);
        givenStoresAndConfig(handleContext);
        setUpTxnContext();
    }

    private void setUpTxnContext() {
        attributeValidator = new AttributeValidatorImpl(handleContext);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(handleContext.configuration()).willReturn(configuration);
        given(handleContext.consensusNow()).willReturn(consensusInstant);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        given(configProvider.getConfiguration()).willReturn(versionedConfig);
    }

    private void mockContext(TransactionBody txn) {
        given(handleContext.body()).willReturn(txn);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(readableTokenStore);
        given(storeFactory.writableStore(WritableNftStore.class)).willReturn(writableNftStore);
        given(handleContext.configuration()).willReturn(configuration);
    }

    private HandleContext keyMockContext(TransactionBody txn) {
        given(handleContext.body()).willReturn(txn);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(readableTokenStore);
        return handleContext;
    }

    @Test
    void happyPathForNonFungibleTokenUpdate() {
        final List<Long> serialNumbers = new ArrayList<>(Arrays.asList(1L, 2L));
        final var metadataForAllNfts = Bytes.wrap("NFT test metadata");

        // TokenUpdateNftBuilder to create mock with serialIds and test metadata
        txn = new TokenUpdateNftBuilder()
                .newNftUpdateTransactionBody(
                        nonFungibleTokenId, metadataForAllNfts, serialNumbers.toArray(new Long[0]));
        given(handleContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));
        final var modifiedToken = writableNftStore.get(nonFungibleTokenId, serialNumbers.get(1));

        if (modifiedToken != null) {
            assertThat(modifiedToken.metadata().asUtf8String()).isEqualTo("NFT test metadata");
            assertThat(modifiedToken.hasNftId()).isTrue();
            assertThat(modifiedToken.nftId().serialNumber()).isEqualTo(2);
        }
    }

    @Test
    void happyPathForNonFungibleTokenUpdateWithNullMetadata() {
        final List<Long> serialNumbers = new ArrayList<>(Arrays.asList(1L, 2L));
        final var existingToken = writableNftStore.get(nonFungibleTokenId, serialNumbers.get(1));
        final var existingMetadata = existingToken.metadata();
        // TokenUpdateNftBuilder to create mock with serialIds and test metadata
        txn = new TokenUpdateNftBuilder()
                .newNftUpdateTransactionBody(nonFungibleTokenId, null, serialNumbers.toArray(new Long[0]));
        given(handleContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));
        final var modifiedToken = writableNftStore.get(nonFungibleTokenId, serialNumbers.get(1));

        if (modifiedToken != null) {
            assertThat(modifiedToken.metadata()).isEqualTo(existingMetadata);
            assertThat(modifiedToken.hasNftId()).isTrue();
            assertThat(modifiedToken.nftId().serialNumber()).isEqualTo(2);
        }
    }

    @Test
    void validatesInvalidNftsEvenIfMetadataIsNotSet() {
        final List<Long> serialNumbers = new ArrayList<>(Arrays.asList(-1L));
        // TokenUpdateNftBuilder to create mock with serialIds and test metadata
        txn = new TokenUpdateNftBuilder()
                .newNftUpdateTransactionBody(nonFungibleTokenId, null, serialNumbers.toArray(new Long[0]));
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext), "Invalid NFT serial number")
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TOKEN_NFT_SERIAL_NUMBER));
    }

    @Test
    void validatesNonExistingNftsEvenIfMetadataIsNotSet() {
        final List<Long> serialNumbers = new ArrayList<>(Arrays.asList(10L));
        // TokenUpdateNftBuilder to create mock with serialIds and test metadata
        txn = new TokenUpdateNftBuilder()
                .newNftUpdateTransactionBody(nonFungibleTokenId, null, serialNumbers.toArray(new Long[0]));
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext), "Non-existing NFT serial number")
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_NFT_ID));
    }

    @Test
    void doesntFailWhenMetadataIsEmpty() {
        final List<Long> serialNumbers = new ArrayList<>(Arrays.asList(1L, 2L));

        final var totalFungibleSupply = 2;
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
        final var txn = new TokenUpdateNftBuilder()
                .newNftUpdateTransactionBody(TOKEN_123, Bytes.EMPTY, serialNumbers.toArray(new Long[0]));
        given(pureChecksContext.body()).willReturn(txn);

        assertThatCode(() -> subject.pureChecks(pureChecksContext)).doesNotThrowAnyException();
    }

    @Test
    void preHandle_WhenTokenIsNull() {
        when(preHandleContext.body()).thenReturn(transactionBody);
        when(transactionBody.tokenUpdateNftsOrThrow()).thenReturn(tokenUpdateNftsTransactionBody);
        when(tokenUpdateNftsTransactionBody.tokenOrElse(TokenID.DEFAULT)).thenReturn(TokenID.DEFAULT);
        when(preHandleContext.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(readableTokenStore.get(TokenID.DEFAULT)).thenReturn(null);

        Assertions.assertThatThrownBy(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TOKEN_ID));
    }

    @Test
    void preHandle_WhenTokenHasMetadataKeySerialsOutsideTreasury() throws PreCheckException {
        when(preHandleContext.body()).thenReturn(transactionBody);
        when(transactionBody.tokenUpdateNftsOrThrow()).thenReturn(tokenUpdateNftsTransactionBody);
        when(tokenUpdateNftsTransactionBody.tokenOrElse(TokenID.DEFAULT)).thenReturn(nonFungibleTokenId);
        when(tokenUpdateNftsTransactionBody.serialNumbers()).thenReturn(List.of(1L));
        when(preHandleContext.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(preHandleContext.createStore(ReadableNftStore.class)).thenReturn(readableNftStore);
        when(readableTokenStore.get(nonFungibleTokenId)).thenReturn(token);
        when(token.metadataKeyOrThrow()).thenReturn(metadataKey);
        when(readableNftStore.get(nftIdSl1)).thenReturn(nft);
        when(token.tokenIdOrThrow()).thenReturn(nonFungibleTokenId);
        when(nft.ownerId()).thenReturn(AccountID.newBuilder().accountNum(1).build());
        when(token.hasMetadataKey()).thenReturn(true);

        assertThatCode(() -> subject.preHandle(preHandleContext)).doesNotThrowAnyException();
        verify(preHandleContext).requireKey(metadataKey);
    }

    @Test
    void preHandle_WhenTokenHasNoMetadataKeySerialsOutsideTreasury() {
        when(preHandleContext.body()).thenReturn(transactionBody);
        when(transactionBody.tokenUpdateNftsOrThrow()).thenReturn(tokenUpdateNftsTransactionBody);
        when(tokenUpdateNftsTransactionBody.tokenOrElse(TokenID.DEFAULT)).thenReturn(nonFungibleTokenId);
        when(tokenUpdateNftsTransactionBody.serialNumbers()).thenReturn(List.of(1L));
        when(preHandleContext.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(preHandleContext.createStore(ReadableNftStore.class)).thenReturn(readableNftStore);
        when(readableTokenStore.get(nonFungibleTokenId)).thenReturn(token);
        when(readableNftStore.get(nftIdSl1)).thenReturn(nft);
        when(token.tokenIdOrThrow()).thenReturn(nonFungibleTokenId);
        when(nft.ownerId()).thenReturn(AccountID.newBuilder().accountNum(1).build());
        when(token.hasMetadataKey()).thenReturn(false);

        Assertions.assertThatThrownBy(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(TOKEN_HAS_NO_METADATA_KEY));
    }

    @Test
    void preHandle_WhenTokenHasMetadataKeyAndSupplyKey() throws PreCheckException {
        when(preHandleContext.body()).thenReturn(transactionBody);
        when(transactionBody.tokenUpdateNftsOrThrow()).thenReturn(tokenUpdateNftsTransactionBody);
        when(tokenUpdateNftsTransactionBody.tokenOrElse(TokenID.DEFAULT)).thenReturn(nonFungibleTokenId);
        when(preHandleContext.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(readableTokenStore.get(nonFungibleTokenId)).thenReturn(token);
        when(token.hasMetadataKey()).thenReturn(true);
        when(token.hasSupplyKey()).thenReturn(true);

        assertThatCode(() -> subject.preHandle(preHandleContext)).doesNotThrowAnyException();
        verify(preHandleContext)
                .requireKey(TokenUpdateHandler.oneOf(token.metadataKeyOrThrow(), token.supplyKeyOrThrow()));
    }

    @Test
    void preHandle_WhenTokenHasMetadataKeyOnly() throws PreCheckException {
        when(preHandleContext.body()).thenReturn(transactionBody);
        when(transactionBody.tokenUpdateNftsOrThrow()).thenReturn(tokenUpdateNftsTransactionBody);
        when(tokenUpdateNftsTransactionBody.tokenOrElse(TokenID.DEFAULT)).thenReturn(nonFungibleTokenId);
        when(preHandleContext.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(readableTokenStore.get(nonFungibleTokenId)).thenReturn(token);
        when(token.hasMetadataKey()).thenReturn(true);
        when(token.hasSupplyKey()).thenReturn(false);
        when(token.metadataKeyOrThrow()).thenReturn(metadataKey);

        assertThatCode(() -> subject.preHandle(preHandleContext)).doesNotThrowAnyException();
        verify(preHandleContext).requireKey(metadataKey);
    }

    @Test
    void preHandle_WhenTokenHasSupplyKeyOnly() throws PreCheckException {
        when(preHandleContext.body()).thenReturn(transactionBody);
        when(transactionBody.tokenUpdateNftsOrThrow()).thenReturn(tokenUpdateNftsTransactionBody);
        when(tokenUpdateNftsTransactionBody.tokenOrElse(TokenID.DEFAULT)).thenReturn(nonFungibleTokenId);
        when(preHandleContext.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(readableTokenStore.get(nonFungibleTokenId)).thenReturn(token);
        when(token.hasMetadataKey()).thenReturn(false);
        when(token.hasSupplyKey()).thenReturn(true);
        when(token.supplyKeyOrThrow()).thenReturn(supplyKey);

        assertThatCode(() -> subject.preHandle(preHandleContext)).doesNotThrowAnyException();
        verify(preHandleContext).requireKey(supplyKey);
    }

    @Test
    void preHandle_WhenTokenHasNoMetadataKeyAndSupplyKey() {
        when(preHandleContext.body()).thenReturn(transactionBody);
        when(transactionBody.tokenUpdateNftsOrThrow()).thenReturn(tokenUpdateNftsTransactionBody);
        when(tokenUpdateNftsTransactionBody.tokenOrElse(TokenID.DEFAULT)).thenReturn(nonFungibleTokenId);
        when(preHandleContext.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(readableTokenStore.get(nonFungibleTokenId)).thenReturn(token);
        when(token.hasMetadataKey()).thenReturn(false);
        when(token.hasSupplyKey()).thenReturn(false);

        Assertions.assertThatThrownBy(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(TOKEN_HAS_NO_METADATA_OR_SUPPLY_KEY));
    }

    @Test
    void nftSerialNotFound() {
        final List<Long> serialNumbers = new ArrayList<>(Arrays.asList(1L, 2L));
        readableTokenStore = newReadableStoreWithTokens(
                Token.newBuilder().tokenId(TOKEN_123).metadataKey(metadataKey).build());
        writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                .tokenId(TOKEN_123)
                .metadataKey(metadataKey)
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
                        MapWritableKVState.builder(TokenService.NAME, NFTS_KEY).build())),
                writableEntityCounters);

        final var txn = new TokenUpdateNftBuilder()
                .newNftUpdateTransactionBody(
                        TOKEN_123, Bytes.wrap("test metadata"), serialNumbers.toArray(new Long[0]));
        mockContext(txn);

        Assertions.assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_NFT_ID));
    }

    @Test
    void calculateFeesAddsCorrectFeeComponents() {
        final var metadata1 = Bytes.wrap("test metadata one");

        final List<Long> serialNumbers = new ArrayList<>(Arrays.asList(1L, 2L));
        final var txnBody =
                new TokenUpdateNftBuilder().newNftUpdateTransactionBody(TOKEN_123, metadata1, serialNumbers.get(1));
        final var feeCalculator = mock(FeeCalculator.class);
        final var feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        final var feeContext = mock(FeeContext.class);

        given(feeContext.body()).willReturn(txnBody);
        given(feeContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(feeCalculatorFactory.feeCalculator(SubType.TOKEN_NON_FUNGIBLE_UNIQUE))
                .willReturn(feeCalculator);
        given(feeCalculator.addBytesPerTransaction(1L)).willReturn(feeCalculator);
        subject.calculateFees(feeContext);

        verify(feeCalculator).addBytesPerTransaction(1L);
    }

    private class TokenUpdateNftBuilder {
        private String metadata = "test metadata";
        TokenID tokenId = nonFungibleTokenId;
        private final AccountID payer = payerId;

        private TokenUpdateNftBuilder() {}

        private TransactionBody build(List<Long> serialNumbers) {
            final var transactionID =
                    TransactionID.newBuilder().accountID(payer).transactionValidStart(consensusTimestamp);
            final var createTxnBody = TokenUpdateNftsTransactionBody.newBuilder()
                    .token(tokenId)
                    .metadata(Bytes.wrap(metadata))
                    .serialNumbers(serialNumbers);
            return TransactionBody.newBuilder()
                    .transactionID(transactionID)
                    .tokenUpdateNfts(createTxnBody)
                    .build();
        }

        private TransactionBody newNftUpdateTransactionBody(TokenID tokenId, Bytes metadata, Long... nftSerialNums) {
            final var transactionID =
                    TransactionID.newBuilder().accountID(ACCOUNT_1339).build();

            TokenUpdateNftsTransactionBody.Builder nftUpdateTxnBodyBuilder =
                    TokenUpdateNftsTransactionBody.newBuilder();
            if (tokenId != null) nftUpdateTxnBodyBuilder.token(tokenId);
            if (metadata != null) {
                nftUpdateTxnBodyBuilder.metadata(metadata);
            }
            nftUpdateTxnBodyBuilder.serialNumbers(nftSerialNums);
            return TransactionBody.newBuilder()
                    .transactionID(transactionID)
                    .tokenUpdateNfts(nftUpdateTxnBodyBuilder)
                    .build();
        }

        public TokenUpdateNftBuilder withMetadata(final String s) {
            this.metadata = s;
            return this;
        }
    }
}

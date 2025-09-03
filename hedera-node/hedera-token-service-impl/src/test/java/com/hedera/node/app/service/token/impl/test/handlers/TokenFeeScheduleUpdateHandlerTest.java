// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_FEE_SCHEDULE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_STATE_LABEL;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenFeeScheduleUpdateTransactionBody;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.FeeContextImpl;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.TokenFeeScheduleUpdateHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.CustomFeesValidator;
import com.hedera.node.app.service.token.records.TokenBaseStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenFeeScheduleUpdateHandlerTest extends CryptoTokenHandlerTestBase {

    private TokenFeeScheduleUpdateHandler subject;
    private CustomFeesValidator validator;
    private TransactionBody txn;

    @Mock(strictness = LENIENT)
    private HandleContext context;

    @Mock(strictness = LENIENT)
    private StoreFactory storeFactory;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock(strictness = LENIENT)
    private TokenBaseStreamBuilder recordBuilder;

    @Mock
    private TransactionDispatcher transactionDispatcher;

    @Mock(strictness = LENIENT)
    private HandleContext.SavepointStack stack;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ExpiryValidator expiryValidator;

    @BeforeEach
    void setup() {
        super.setUp();
        refreshWritableStores();
        validator = new CustomFeesValidator();
        subject = new TokenFeeScheduleUpdateHandler(validator);
        givenTxn();
        final var config = HederaTestConfigBuilder.create()
                .withValue("tokens.maxCustomFeesAllowed", 1000)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);
        given(context.storeFactory()).willReturn(storeFactory);
        given(context.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(ResponseCodeEnum.OK);

        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        given(storeFactory.readableStore(ReadableTokenRelationStore.class)).willReturn(readableTokenRelStore);
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(any())).willReturn(recordBuilder);
    }

    @Test
    @DisplayName("fee schedule update works as expected for fungible token")
    void handleWorksAsExpectedForFungibleToken() {
        txn = TransactionBody.newBuilder()
                .tokenFeeScheduleUpdate(TokenFeeScheduleUpdateTransactionBody.newBuilder()
                        .tokenId(fungibleTokenId)
                        .customFees(List.of(withFixedFee(htsFixedFee), withFractionalFee(fractionalFee)))
                        .build())
                .build();
        given(context.body()).willReturn(txn);

        // before fee schedule update, validate no custom fees on the token
        final var originalToken = writableTokenStore.get(fungibleTokenId);
        assertThat(originalToken.customFees())
                .isEqualTo(List.of(withFixedFee(hbarFixedFee), withFractionalFee(fractionalFee)));
        assertThat(writableTokenStore.modifiedTokens()).isEmpty();

        subject.handle(context);

        // validate after fee schedule update fixed and fractional custom fees are added to the token
        assertThat(writableTokenStore.modifiedTokens()).hasSize(1);
        assertThat(writableTokenStore.modifiedTokens()).hasSameElementsAs(Set.of(fungibleTokenId));

        final var expectedToken = writableTokenStore.get(fungibleTokenId);
        assertThat(expectedToken.customFees()).hasSize(2);
        assertThat(expectedToken.customFees())
                .hasSameElementsAs(List.of(withFractionalFee(fractionalFee), withFixedFee(htsFixedFee)));
    }

    @Test
    @DisplayName("fee schedule update works as expected for non-fungible token")
    void handleWorksAsExpectedForNonFungibleToken() {
        final var tokenId = nonFungibleTokenId;
        txn = TransactionBody.newBuilder()
                .tokenFeeScheduleUpdate(TokenFeeScheduleUpdateTransactionBody.newBuilder()
                        .tokenId(tokenId)
                        .customFees(List.of(withRoyaltyFee(royaltyFee
                                .copyBuilder()
                                .fallbackFee(htsFixedFee)
                                .build())))
                        .build())
                .build();
        given(context.body()).willReturn(txn);

        // before fee schedule update, validate no custom fees on the token
        final var originalToken = writableTokenStore.get(tokenId);
        assertThat(originalToken.customFees())
                .isEqualTo(List.of(withRoyaltyFee(
                        royaltyFee.copyBuilder().fallbackFee(hbarFixedFee).build())));
        assertThat(writableTokenStore.modifiedTokens()).isEmpty();

        subject.handle(context);

        // validate after fee schedule update royalty custom fees are added to the token
        assertThat(writableTokenStore.modifiedTokens()).hasSize(1);
        assertThat(writableTokenStore.modifiedTokens()).hasSameElementsAs(Set.of(tokenId));

        final var expectedToken = writableTokenStore.get(nonFungibleTokenId);
        assertThat(expectedToken.customFees()).hasSize(1);
        assertThat(expectedToken.customFees())
                .hasSameElementsAs(List.of(withRoyaltyFee(
                        royaltyFee.copyBuilder().fallbackFee(htsFixedFee).build())));
    }

    @Test
    @DisplayName("fee schedule update fails if token has no fee schedule key")
    void validatesTokenHasFeeScheduleKey() {
        final var tokenWithoutFeeScheduleKey =
                fungibleToken.copyBuilder().feeScheduleKey((Key) null).build();
        writableTokenState = MapWritableKVState.<TokenID, Token>builder(TOKENS_STATE_ID, TOKENS_STATE_LABEL)
                .value(fungibleTokenId, tokenWithoutFeeScheduleKey)
                .build();
        given(writableStates.<TokenID, Token>get(TOKENS_STATE_ID)).willReturn(writableTokenState);
        writableTokenStore = new WritableTokenStore(writableStates, writableEntityCounters);
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);

        assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_HAS_NO_FEE_SCHEDULE_KEY));
    }

    @Test
    @DisplayName("fee schedule update fails if token does not exist")
    void rejectsInvalidTokenId() {
        writableTokenState = emptyWritableTokenState();
        given(writableStates.<TokenID, Token>get(TOKENS_STATE_ID)).willReturn(writableTokenState);
        writableTokenStore = new WritableTokenStore(writableStates, writableEntityCounters);
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);

        assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TOKEN_ID));
    }

    @Test
    @DisplayName("fee schedule update fails if token is deleted")
    void rejectsDeletedTokenId() {
        final var deletedToken = givenValidFungibleToken(payerId, true, false, false, false, true);
        writableTokenStore.put(deletedToken);

        assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_WAS_DELETED));
    }

    @Test
    @DisplayName("fee schedule update fails if token is paused")
    void rejectsPausedTokenId() {
        final var pausedToken = givenValidFungibleToken(payerId, false, true, false, false, true);
        writableTokenStore.put(pausedToken);

        assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_IS_PAUSED));
    }

    @Test
    @DisplayName("fee schedule update fails if custom fees list is too long")
    void failsIfTooManyCustomFees() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("tokens.maxCustomFeesAllowed", 1)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);
        assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CUSTOM_FEES_LIST_TOO_LONG));
    }

    @Test
    @DisplayName("fee schedule update fails in pre-handle if transaction has no tokenId specified")
    void failsIfTxnHasNoTokenId() {
        txn = TransactionBody.newBuilder()
                .tokenFeeScheduleUpdate(TokenFeeScheduleUpdateTransactionBody.newBuilder()
                        .customFees(customFees)
                        .build())
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TOKEN_ID));
    }

    @Test
    public void testCalculateFeesHappyPath() {
        TransactionInfo txnInfo = mock(TransactionInfo.class);
        FeeManager feeManager = mock(FeeManager.class);
        FeeCalculator feeCalculator = mock(FeeCalculator.class);
        ReadableStoreFactory storeFactory = mock(ReadableStoreFactory.class);
        TransactionBody transactionBody = mock(TransactionBody.class);
        TokenFeeScheduleUpdateTransactionBody tokenFeeScheduleUpdateTransactionBody =
                mock(TokenFeeScheduleUpdateTransactionBody.class);
        TransactionID transactionID = mock(TransactionID.class);

        List<CustomFee> customFees = new ArrayList<>();
        customFees.add(withFixedFee(hbarFixedFee));
        customFees.add(withFractionalFee(fractionalFee));
        customFees.add(withRoyaltyFee(royaltyFee));

        when(feeManager.createFeeCalculator(any(), any(), any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any()))
                .thenReturn(feeCalculator);
        when(txnInfo.txBody()).thenReturn(transactionBody);
        when(transactionBody.tokenFeeScheduleUpdateOrThrow()).thenReturn(tokenFeeScheduleUpdateTransactionBody);
        when(tokenFeeScheduleUpdateTransactionBody.customFees()).thenReturn(customFees);
        when(tokenFeeScheduleUpdateTransactionBody.tokenIdOrThrow()).thenReturn(fungibleTokenId);
        when(storeFactory.getStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(transactionBody.transactionIDOrThrow()).thenReturn(transactionID);
        when(transactionID.transactionValidStartOrThrow()).thenReturn(consensusTimestamp);
        when(txnInfo.signatureMap()).thenReturn(SignatureMap.DEFAULT);
        when(feeCalculator.addBytesPerTransaction(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.addRamByteSeconds(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.calculate()).thenReturn(Fees.FREE);

        final var feeContext = new FeeContextImpl(
                consensusInstant,
                txnInfo,
                payerKey,
                payerId,
                feeManager,
                storeFactory,
                configuration,
                null,
                -1,
                transactionDispatcher);

        final var calculateFees = subject.calculateFees(feeContext);
        assertEquals(calculateFees, Fees.FREE);
    }

    @Test
    @DisplayName("If the requested token ID does not exist, the fee calculation should not throw an exception")
    void calculateFeesTokenDoesNotExist() {
        TransactionInfo txnInfo = mock(TransactionInfo.class);
        FeeManager feeManager = mock(FeeManager.class);
        FeeCalculator feeCalculator = mock(FeeCalculator.class);
        ReadableStoreFactory storeFactory = mock(ReadableStoreFactory.class);
        TransactionBody transactionBody = mock(TransactionBody.class);
        TokenFeeScheduleUpdateTransactionBody tokenFeeScheduleUpdateTransactionBody =
                mock(TokenFeeScheduleUpdateTransactionBody.class);
        TransactionID transactionID = mock(TransactionID.class);

        when(feeManager.createFeeCalculator(any(), any(), any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any()))
                .thenReturn(feeCalculator);
        when(txnInfo.txBody()).thenReturn(transactionBody);
        when(transactionBody.tokenFeeScheduleUpdateOrThrow()).thenReturn(tokenFeeScheduleUpdateTransactionBody);
        // Any token ID that doesn't exist:
        when(tokenFeeScheduleUpdateTransactionBody.tokenIdOrThrow())
                .thenReturn(TokenID.newBuilder().tokenNum(1500).build());
        when(storeFactory.getStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(transactionBody.transactionIDOrThrow()).thenReturn(transactionID);
        when(transactionID.transactionValidStartOrThrow()).thenReturn(consensusTimestamp);
        when(txnInfo.signatureMap()).thenReturn(SignatureMap.DEFAULT);
        when(feeCalculator.addBytesPerTransaction(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.addRamByteSeconds(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.calculate()).thenReturn(Fees.FREE);

        final var feeContext = new FeeContextImpl(
                consensusInstant,
                txnInfo,
                payerKey,
                payerId,
                feeManager,
                storeFactory,
                configuration,
                null,
                -1,
                transactionDispatcher);

        Assertions.assertThatNoException().isThrownBy(() -> subject.calculateFees(feeContext));
    }

    private void givenTxn() {
        txn = TransactionBody.newBuilder()
                .tokenFeeScheduleUpdate(TokenFeeScheduleUpdateTransactionBody.newBuilder()
                        .tokenId(fungibleTokenId)
                        .customFees(customFees)
                        .build())
                .build();
        given(context.body()).willReturn(txn);
    }
}

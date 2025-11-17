// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_STATE_LABEL;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenPauseTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.ReadableTokenStoreImpl;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.TokenPauseHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.TokenHandlerTestBase;
import com.hedera.node.app.service.token.records.TokenBaseStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenPauseHandlerTest extends TokenHandlerTestBase {

    private TokenPauseHandler subject;
    private TransactionBody tokenPauseTxn;
    private FakePreHandleContext preHandleContext;

    @Mock
    private Account account;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = LENIENT)
    private StoreFactory storeFactory;

    @Mock(strictness = LENIENT)
    private TokenBaseStreamBuilder recordBuilder;

    @Mock(strictness = LENIENT)
    private HandleContext.SavepointStack stack;

    @Mock
    private PureChecksContext pureChecksContext;

    @BeforeEach
    void setUp() throws PreCheckException {
        given(accountStore.getAccountById(AccountID.newBuilder().accountNum(3L).build()))
                .willReturn(account);
        given(account.key()).willReturn(payerKey);

        subject = new TokenPauseHandler();
        givenValidTxn();
        refreshStoresWithCurrentTokenInWritable();
        preHandleContext = new FakePreHandleContext(accountStore, tokenPauseTxn);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(any())).willReturn(recordBuilder);
    }

    @Test
    public void testPureChecksThrowsExceptionWhenDoesNotHaveToken() {
        TokenPauseTransactionBody transactionBody = mock(TokenPauseTransactionBody.class);
        TransactionBody transaction = mock(TransactionBody.class);
        given(transaction.tokenPauseOrThrow()).willReturn(transactionBody);
        given(transactionBody.hasToken()).willReturn(false);
        given(pureChecksContext.body()).willReturn(transaction);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext)).isInstanceOf(PreCheckException.class);
    }

    @Test
    public void testPureChecksDoesNotThrowExceptionWhenHasToken() {
        TokenPauseTransactionBody transactionBody = mock(TokenPauseTransactionBody.class);
        TransactionBody transaction = mock(TransactionBody.class);
        given(transaction.tokenPauseOrThrow()).willReturn(transactionBody);
        given(transactionBody.hasToken()).willReturn(true);
        given(pureChecksContext.body()).willReturn(transaction);

        assertThatCode(() -> subject.pureChecks(pureChecksContext)).doesNotThrowAnyException();
    }

    @Test
    public void testCalculateFeesInvocations() {
        FeeContext feeContext = mock(FeeContext.class);
        FeeCalculatorFactory feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        FeeCalculator feeCalculator = mock(FeeCalculator.class);
        when(feeContext.feeCalculatorFactory()).thenReturn(feeCalculatorFactory);
        when(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).thenReturn(feeCalculator);
        when(feeCalculator.addBytesPerTransaction(anyLong())).thenReturn(feeCalculator);

        subject.calculateFees(feeContext);

        InOrder inOrder = inOrder(feeCalculatorFactory, feeCalculator);
        inOrder.verify(feeCalculatorFactory).feeCalculator(SubType.DEFAULT);
        inOrder.verify(feeCalculator).addBytesPerTransaction(anyLong());
        inOrder.verify(feeCalculator).calculate();
    }

    @Test
    void pausesUnPausedToken() {
        unPauseKnownToken();
        assertFalse(writableTokenStore.get(tokenId).paused());

        subject.handle(handleContext);

        final var unpausedToken = writableTokenStore.get(tokenId);
        assertTrue(unpausedToken.paused());
    }

    @Test
    void pauseTokenFailsIfInvalidToken() {
        givenInvalidTokenInTxn();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INVALID_TOKEN_ID, msg.getStatus());
    }

    @Test
    void failsForNullArguments() {
        assertThrows(NullPointerException.class, () -> subject.handle(null));
    }

    @Test
    void failsInPrecheckIfTxnBodyHasNoToken() throws PreCheckException {
        final var txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId).build())
                .tokenPause(TokenPauseTransactionBody.newBuilder())
                .build();
        preHandleContext = new FakePreHandleContext(accountStore, txn);
        preHandleContext.registerStore(ReadableTokenStore.class, readableTokenStore);
        assertThrowsPreCheck(() -> subject.preHandle(preHandleContext), INVALID_TOKEN_ID);
    }

    @Test
    void validatesTokenExistsInPreHandle() throws PreCheckException {
        givenInvalidTokenInTxn();
        preHandleContext = new FakePreHandleContext(accountStore, tokenPauseTxn);
        preHandleContext.registerStore(ReadableTokenStore.class, readableTokenStore);
        assertThrowsPreCheck(() -> subject.preHandle(preHandleContext), INVALID_TOKEN_ID);
    }

    @Test
    void preHandleAddsPauseKeyToContext() throws PreCheckException {
        preHandleContext.registerStore(ReadableTokenStore.class, readableTokenStore);
        subject.preHandle(preHandleContext);
        assertEquals(1, preHandleContext.requiredNonPayerKeys().size());
    }

    @Test
    void preHandleSetsStatusWhenTokenMissing() throws PreCheckException {
        givenInvalidTokenInTxn();
        preHandleContext = new FakePreHandleContext(accountStore, tokenPauseTxn);
        preHandleContext.registerStore(ReadableTokenStore.class, readableTokenStore);
        assertThrowsPreCheck(() -> subject.preHandle(preHandleContext), INVALID_TOKEN_ID);
    }

    @Test
    void doesntAddAnyKeyIfPauseKeyMissing() throws PreCheckException {
        final var copy = token.copyBuilder().pauseKey(Key.DEFAULT).build();
        readableTokenState = MapReadableKVState.<TokenID, Token>builder(TOKENS_STATE_ID, TOKENS_STATE_LABEL)
                .value(tokenId, copy)
                .build();
        given(readableStates.<TokenID, Token>get(TOKENS_STATE_ID)).willReturn(readableTokenState);
        readableTokenStore = new ReadableTokenStoreImpl(readableStates, readableEntityCounters);
        preHandleContext.registerStore(ReadableTokenStore.class, readableTokenStore);

        subject.preHandle(preHandleContext);

        assertEquals(0, preHandleContext.requiredNonPayerKeys().size());
    }

    private void givenValidTxn() {
        tokenPauseTxn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId).build())
                .tokenPause(TokenPauseTransactionBody.newBuilder().token(tokenId))
                .build();
        given(handleContext.body()).willReturn(tokenPauseTxn);
    }

    private void givenInvalidTokenInTxn() {
        tokenPauseTxn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId).build())
                .tokenPause(TokenPauseTransactionBody.newBuilder()
                        .token(TokenID.newBuilder().tokenNum(2).build()))
                .build();
        given(handleContext.body()).willReturn(tokenPauseTxn);
    }

    private void unPauseKnownToken() {
        final var token =
                writableTokenStore.get(tokenId).copyBuilder().paused(false).build();
        writableTokenStore.put(token);
    }
}

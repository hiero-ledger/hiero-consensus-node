// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.scope;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.AN_ED25519_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_SECP256K1_KEY;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleSystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy.Decision;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.utils.SystemContractUtils;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.key.KeyVerifier;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.signatures.VerificationAssistant;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.HandleContext;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandleSystemContractOperationsTest {

    @Mock
    private HandleContext context;

    @Mock
    private ContractCallStreamBuilder streamBuilder;

    @Mock
    private ExchangeRateInfo exchangeRateInfo;

    @Mock
    private VerificationStrategy strategy;

    @Mock
    private Predicate<Key> callback;

    @Mock
    private SignatureVerification passed;

    @Mock
    private SignatureVerification failed;

    @Mock
    private KeyVerifier keyVerifier;

    @Mock
    private HandleContext.SavepointStack savepointStack;

    private HandleSystemContractOperations subject;

    @BeforeEach
    void setUp() {
        subject = new HandleSystemContractOperations(context, A_SECP256K1_KEY);
    }

    @Test
    void returnsExpectedPrimitiveTest() {
        given(strategy.asPrimitiveSignatureTestIn(context, A_SECP256K1_KEY)).willReturn(callback);
        assertSame(callback, subject.primitiveSignatureTestWith(strategy));
    }

    @Test
    void returnsExpectedTest() {
        final var captor = forClass(VerificationAssistant.class);
        doCallRealMethod().when(strategy).asSignatureTestIn(context, A_SECP256K1_KEY);
        given(strategy.asPrimitiveSignatureTestIn(context, A_SECP256K1_KEY)).willReturn(callback);
        given(context.keyVerifier()).willReturn(keyVerifier);
        given(keyVerifier.verificationFor(eq(Key.DEFAULT), captor.capture())).willReturn(passed);
        given(passed.passed()).willReturn(true);

        final var test = subject.signatureTestWith(strategy);

        assertTrue(test.test(Key.DEFAULT));
        captor.getValue().test(Key.DEFAULT, failed);
        verify(callback).test(Key.DEFAULT);
    }

    @Test
    void dispatchesWithEmptySetOfAuthorizingKeysByDefault() {
        final var mockSubject = mock(HandleSystemContractOperations.class);
        doCallRealMethod().when(mockSubject).dispatch(any(), any(), any(), any());

        mockSubject.dispatch(TransactionBody.DEFAULT, strategy, A_NEW_ACCOUNT_ID, CryptoTransferStreamBuilder.class);

        verify(mockSubject)
                .dispatch(
                        TransactionBody.DEFAULT,
                        strategy,
                        A_NEW_ACCOUNT_ID,
                        CryptoTransferStreamBuilder.class,
                        Set.of(),
                        DispatchOptions.UsePresetTxnId.NO);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchesRespectingGivenStrategy() {
        final var captor = forClass(DispatchOptions.class);
        given(strategy.decideForPrimitive(TestHelpers.A_CONTRACT_KEY)).willReturn(Decision.VALID);
        given(strategy.decideForPrimitive(AN_ED25519_KEY)).willReturn(Decision.DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION);
        given(strategy.decideForPrimitive(TestHelpers.B_SECP256K1_KEY))
                .willReturn(Decision.DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION);
        given(strategy.decideForPrimitive(TestHelpers.A_SECP256K1_KEY)).willReturn(Decision.INVALID);
        given(passed.passed()).willReturn(true);
        given(context.keyVerifier()).willReturn(keyVerifier);
        given(keyVerifier.verificationFor(AN_ED25519_KEY)).willReturn(passed);
        given(keyVerifier.verificationFor(TestHelpers.B_SECP256K1_KEY)).willReturn(failed);
        doCallRealMethod().when(strategy).asPrimitiveSignatureTestIn(context, A_SECP256K1_KEY);

        subject.dispatch(
                TransactionBody.DEFAULT,
                strategy,
                A_NEW_ACCOUNT_ID,
                CryptoTransferStreamBuilder.class,
                Set.of(AN_ED25519_KEY),
                DispatchOptions.UsePresetTxnId.NO);

        verify(context).dispatch(captor.capture());
        final var options = captor.getValue();
        final var test = options.keyVerifier();
        assertTrue(test.test(TestHelpers.A_CONTRACT_KEY));
        assertTrue(test.test(AN_ED25519_KEY));
        assertFalse(test.test(TestHelpers.A_SECP256K1_KEY));
        assertFalse(test.test(TestHelpers.B_SECP256K1_KEY));
        assertThat(options.authorizingKeys()).containsExactly(AN_ED25519_KEY);
    }

    @Test
    void externalizesPreemptedAsExpected() {
        given(context.savepointStack()).willReturn(savepointStack);
        given(savepointStack.addChildRecordBuilder(ContractCallStreamBuilder.class, CRYPTO_TRANSFER))
                .willReturn(streamBuilder);
        given(streamBuilder.transaction(any())).willReturn(streamBuilder);
        given(streamBuilder.status(any())).willReturn(streamBuilder);

        final var preemptedBuilder =
                subject.externalizePreemptedDispatch(TransactionBody.DEFAULT, ACCOUNT_DELETED, CRYPTO_TRANSFER);

        assertSame(streamBuilder, preemptedBuilder);
        verify(streamBuilder).status(ACCOUNT_DELETED);
    }

    @Test
    void externalizeSuccessfulResultWithTransactionBodyTest() {
        var transaction = Transaction.newBuilder()
                .body(TransactionBody.newBuilder()
                        .transactionID(TransactionID.DEFAULT)
                        .build())
                .build();
        var contractFunctionResult = SystemContractUtils.successResultOfZeroValueTraceable(
                0,
                org.apache.tuweni.bytes.Bytes.EMPTY,
                100L,
                org.apache.tuweni.bytes.Bytes.EMPTY,
                AccountID.newBuilder().build());
        var txResult = SystemContractUtils.txSuccessResultOfZeroValueTraceable(
                0,
                org.apache.tuweni.bytes.Bytes.EMPTY,
                100L,
                org.apache.tuweni.bytes.Bytes.EMPTY,
                AccountID.newBuilder().build());

        // given
        given(context.savepointStack()).willReturn(savepointStack);
        given(savepointStack.addChildRecordBuilder(ContractCallStreamBuilder.class, CONTRACT_CALL))
                .willReturn(streamBuilder);
        given(streamBuilder.transaction(transaction)).willReturn(streamBuilder);
        given(streamBuilder.status(ResponseCodeEnum.SUCCESS)).willReturn(streamBuilder);
        given(streamBuilder.contractCallResult(contractFunctionResult)).willReturn(streamBuilder);

        // when
        subject.externalizeResult(contractFunctionResult, ResponseCodeEnum.SUCCESS, transaction, txResult);

        // then
        verify(streamBuilder).status(ResponseCodeEnum.SUCCESS);
    }

    @Test
    void syntheticTransactionForHtsCallTest() {
        assertNotNull(subject.syntheticTransactionForNativeCall(Bytes.EMPTY, ContractID.DEFAULT, true));
    }

    @Test
    void currentExchangeRateTest() {
        given(context.exchangeRateInfo()).willReturn(exchangeRateInfo);
        subject.currentExchangeRate();
        verify(context).exchangeRateInfo();
        verify(exchangeRateInfo).activeRate(any());
    }

    @Test
    void maybeEthSenderKeyTest() {
        assertSame(A_SECP256K1_KEY, subject.maybeEthSenderKey());
    }
}

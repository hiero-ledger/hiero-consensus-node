// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RECEIVING_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.tuweniEncodedRc;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.V2_TRANSFER_DISABLED_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asBytesResult;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.readableRevertReason;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ApprovalSwitchHelper;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.CallStatusStandardizer;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.SpecialRewardReceivers;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.SystemAccountCreditScreen;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.Optional;
import java.util.function.Predicate;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ClassicTransfersCallTest extends CallTestBase {
    private static final TupleType INT64_ENCODER = TupleType.parse(ReturnTypes.INT_64);

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private CallStatusStandardizer callStatusStandardizer;

    @Mock
    private Predicate<Key> signatureTest;

    @Mock
    private ApprovalSwitchHelper approvalSwitchHelper;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private SystemAccountCreditScreen systemAccountCreditScreen;

    @Mock
    private SystemContractGasCalculator systemContractGasCalculator;

    @Mock
    private SpecialRewardReceivers specialRewardReceivers;

    private ClassicTransfersCall subject;

    @Test
    void transferHappyPathCompletesWithSuccessResponseCode() {
        givenRetryingSubject();
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(A_NEW_ACCOUNT_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);
        given(systemContractOperations.signatureTestWith(verificationStrategy)).willReturn(signatureTest);
        given(approvalSwitchHelper.switchToApprovalsAsNeededIn(
                        CryptoTransferTransactionBody.DEFAULT, signatureTest, nativeOperations, A_NEW_ACCOUNT_ID))
                .willReturn(CryptoTransferTransactionBody.DEFAULT);

        givenRetryingSubject();

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(tuweniEncodedRc(SUCCESS), result.output());
    }

    @Test
    void haltsWithMissingTransactionBody() {
        givenHaltingSubject();

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.EXCEPTIONAL_HALT, result.state());
    }

    @Test
    void retryingTransferHappyPathCompletesWithSuccessResponseCode() {
        givenRetryingSubject();
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(A_NEW_ACCOUNT_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);
        given(systemContractOperations.signatureTestWith(verificationStrategy)).willReturn(signatureTest);
        given(approvalSwitchHelper.switchToApprovalsAsNeededIn(
                        CryptoTransferTransactionBody.DEFAULT, signatureTest, nativeOperations, A_NEW_ACCOUNT_ID))
                .willReturn(CryptoTransferTransactionBody.DEFAULT);

        givenRetryingSubject();

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                asBytesResult(INT64_ENCODER.encode(Tuple.singleton((long) SUCCESS.protoOrdinal()))), result.output());
    }

    @Test
    void retryingTransferInvalidSignatureCompletesWithStandardizedResponseCode() {
        givenRetryingSubject();
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(A_NEW_ACCOUNT_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status())
                .willReturn(INVALID_SIGNATURE)
                .willReturn(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE);
        given(systemContractOperations.signatureTestWith(verificationStrategy)).willReturn(signatureTest);
        given(approvalSwitchHelper.switchToApprovalsAsNeededIn(
                        CryptoTransferTransactionBody.DEFAULT, signatureTest, nativeOperations, A_NEW_ACCOUNT_ID))
                .willReturn(CryptoTransferTransactionBody.DEFAULT);
        given(callStatusStandardizer.codeForFailure(
                        INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE, frame, CryptoTransferTransactionBody.DEFAULT))
                .willReturn(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE);

        givenRetryingSubject();

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(tuweniEncodedRc(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE), result.output());
        verify(recordBuilder).status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE);
    }

    @Test
    void unsupportedV2transferHaltsWithNotSupportedReason() {
        givenV2SubjectWithV2Disabled();
        given(systemContractOperations.externalizePreemptedDispatch(
                        any(TransactionBody.class), eq(NOT_SUPPORTED), eq(CRYPTO_TRANSFER)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(NOT_SUPPORTED);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.EXCEPTIONAL_HALT, result.state());
        assertEquals(Optional.of(CustomExceptionalHaltReason.NOT_SUPPORTED), result.haltReason());
    }

    @Test
    void systemAccountCreditReverts() {
        givenRetryingSubject();
        given(systemAccountCreditScreen.creditsToSystemAccount(CryptoTransferTransactionBody.DEFAULT))
                .willReturn(true);
        given(systemContractOperations.externalizePreemptedDispatch(
                        any(TransactionBody.class), eq(INVALID_RECEIVING_NODE_ACCOUNT), eq(CRYPTO_TRANSFER)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(INVALID_RECEIVING_NODE_ACCOUNT);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.state());
        assertEquals(readableRevertReason(INVALID_RECEIVING_NODE_ACCOUNT), result.output());
    }

    @Test
    void supportedV2transferCompletesWithNominalResponseCode() {
        givenV2SubjectWithV2Enabled();
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(A_NEW_ACCOUNT_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SPENDER_DOES_NOT_HAVE_ALLOWANCE);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                asBytesResult(
                        INT64_ENCODER.encode(Tuple.singleton((long) SPENDER_DOES_NOT_HAVE_ALLOWANCE.protoOrdinal()))),
                result.output());
    }

    private static final TransactionBody PRETEND_TRANSFER = TransactionBody.newBuilder()
            .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT)
            .build();

    private void givenRetryingSubject() {
        subject = new ClassicTransfersCall(
                systemContractGasCalculator,
                mockEnhancement(),
                ClassicTransfersTranslator.CRYPTO_TRANSFER.selector(),
                A_NEW_ACCOUNT_ID,
                null,
                PRETEND_TRANSFER,
                DEFAULT_CONFIG,
                approvalSwitchHelper,
                callStatusStandardizer,
                verificationStrategy,
                systemAccountCreditScreen,
                specialRewardReceivers);
    }

    private void givenHaltingSubject() {
        subject = new ClassicTransfersCall(
                systemContractGasCalculator,
                mockEnhancement(),
                ClassicTransfersTranslator.CRYPTO_TRANSFER.selector(),
                A_NEW_ACCOUNT_ID,
                null,
                null,
                DEFAULT_CONFIG,
                approvalSwitchHelper,
                callStatusStandardizer,
                verificationStrategy,
                systemAccountCreditScreen,
                specialRewardReceivers);
    }

    private void givenV2SubjectWithV2Enabled() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.precompile.atomicCryptoTransfer.enabled", "true")
                .getOrCreateConfig();
        subject = new ClassicTransfersCall(
                systemContractGasCalculator,
                mockEnhancement(),
                ClassicTransfersTranslator.CRYPTO_TRANSFER_V2.selector(),
                A_NEW_ACCOUNT_ID,
                null,
                PRETEND_TRANSFER,
                config,
                null,
                callStatusStandardizer,
                verificationStrategy,
                systemAccountCreditScreen,
                specialRewardReceivers);
    }

    private void givenV2SubjectWithV2Disabled() {
        subject = new ClassicTransfersCall(
                systemContractGasCalculator,
                mockEnhancement(),
                ClassicTransfersTranslator.CRYPTO_TRANSFER_V2.selector(),
                A_NEW_ACCOUNT_ID,
                null,
                PRETEND_TRANSFER,
                V2_TRANSFER_DISABLED_CONFIG,
                null,
                callStatusStandardizer,
                verificationStrategy,
                systemAccountCreditScreen,
                specialRewardReceivers);
    }
}

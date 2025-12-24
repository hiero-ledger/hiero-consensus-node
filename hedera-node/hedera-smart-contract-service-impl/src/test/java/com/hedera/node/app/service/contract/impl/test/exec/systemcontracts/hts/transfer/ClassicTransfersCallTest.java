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
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
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
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.log.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.Mockito;

class ClassicTransfersCallTest extends CallTestBase {
    private static final TupleType<Tuple> INT64_ENCODER = TupleType.parse(ReturnTypes.INT_64);

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

    @Mock
    private ReadableAccountStore readableAccountStore;

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

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(tuweniEncodedRc(SUCCESS), result.getOutput());
    }

    @Test
    void haltsWithMissingTransactionBody() {
        givenHaltingSubject();

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.EXCEPTIONAL_HALT, result.getState());
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

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                asBytesResult(INT64_ENCODER.encode(Tuple.singleton((long) SUCCESS.protoOrdinal()))),
                result.getOutput());
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

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(tuweniEncodedRc(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE), result.getOutput());
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

        assertEquals(MessageFrame.State.EXCEPTIONAL_HALT, result.getState());
        assertEquals(Optional.of(CustomExceptionalHaltReason.NOT_SUPPORTED), result.getHaltReason());
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

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(readableRevertReason(INVALID_RECEIVING_NODE_ACCOUNT), result.getOutput());
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

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                asBytesResult(
                        INT64_ENCODER.encode(Tuple.singleton((long) SPENDER_DOES_NOT_HAVE_ALLOWANCE.protoOrdinal()))),
                result.getOutput());
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

    // ---------------------------- ERC events emission tests ----------------------------

    private ClassicTransfersCall givenSubject(byte[] selector) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.precompile.atomicCryptoTransfer.enabled", "true")
                .getOrCreateConfig();
        given(approvalSwitchHelper.switchToApprovalsAsNeededIn(
                        CryptoTransferTransactionBody.DEFAULT, signatureTest, nativeOperations, A_NEW_ACCOUNT_ID))
                .willReturn(CryptoTransferTransactionBody.DEFAULT);
        given(systemContractOperations.signatureTestWith(verificationStrategy)).willReturn(signatureTest);
        return new ClassicTransfersCall(
                systemContractGasCalculator,
                mockEnhancement(),
                selector,
                A_NEW_ACCOUNT_ID,
                null,
                PRETEND_TRANSFER,
                config,
                approvalSwitchHelper,
                callStatusStandardizer,
                verificationStrategy,
                systemAccountCreditScreen,
                specialRewardReceivers);
    }

    public static List<SystemContractMethod> ercEventsEmissionForFTParams() {
        return List.of(
                ClassicTransfersTranslator.CRYPTO_TRANSFER,
                ClassicTransfersTranslator.CRYPTO_TRANSFER_V2,
                ClassicTransfersTranslator.TRANSFER_TOKENS,
                ClassicTransfersTranslator.TRANSFER_TOKEN,
                ClassicTransfersTranslator.TRANSFER_FROM);
    }

    @ParameterizedTest
    @MethodSource("ercEventsEmissionForFTParams")
    void ercEventsEmissionForFT(SystemContractMethod function) {
        final var localSubject = givenSubject(function.selector());
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(A_NEW_ACCOUNT_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);
        given(nativeOperations.readableAccountStore()).willReturn(readableAccountStore);
        final var expectedTransfers = List.of(new TestTokenTransfer(
                FUNGIBLE_TOKEN_ID, false, OWNER_ID, OWNER_ACCOUNT, RECEIVER_ID, ALIASED_RECEIVER, 123));
        given(recordBuilder.tokenTransferLists()).willReturn(tokenTransfersLists(expectedTransfers));
        given(readableAccountStore.getAliasedAccountById(OWNER_ID)).willReturn(OWNER_ACCOUNT);
        given(readableAccountStore.getAliasedAccountById(RECEIVER_ID)).willReturn(ALIASED_RECEIVER);
        final List<Log> logs = new ArrayList<>();
        Mockito.doAnswer(e -> logs.add(e.getArgument(0))).when(frame).addLog(any());

        final var result = localSubject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(tuweniEncodedRc(SUCCESS), result.getOutput());
        // check that events was added
        TransferEventLoggingUtilsTest.verifyFTLogEvent(logs, expectedTransfers);
    }

    public static List<SystemContractMethod> ercEventsEmissionForNFTParams() {
        return List.of(
                ClassicTransfersTranslator.CRYPTO_TRANSFER,
                ClassicTransfersTranslator.CRYPTO_TRANSFER_V2,
                ClassicTransfersTranslator.TRANSFER_NFTS,
                ClassicTransfersTranslator.TRANSFER_NFT,
                ClassicTransfersTranslator.TRANSFER_NFT_FROM);
    }

    @ParameterizedTest
    @MethodSource("ercEventsEmissionForNFTParams")
    void ercEventsEmissionForNFT(SystemContractMethod function) {
        final var localSubject = givenSubject(function.selector());
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(A_NEW_ACCOUNT_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);
        given(nativeOperations.readableAccountStore()).willReturn(readableAccountStore);
        final var expectedTransfers = List.of(new TestTokenTransfer(
                FUNGIBLE_TOKEN_ID, true, OWNER_ID, OWNER_ACCOUNT, RECEIVER_ID, ALIASED_RECEIVER, 123));
        given(recordBuilder.tokenTransferLists()).willReturn(tokenTransfersLists(expectedTransfers));
        given(readableAccountStore.getAliasedAccountById(OWNER_ID)).willReturn(OWNER_ACCOUNT);
        given(readableAccountStore.getAliasedAccountById(RECEIVER_ID)).willReturn(ALIASED_RECEIVER);
        final List<Log> logs = new ArrayList<>();
        Mockito.doAnswer(e -> logs.add(e.getArgument(0))).when(frame).addLog(any());

        final var result = localSubject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(tuweniEncodedRc(SUCCESS), result.getOutput());
        // check that events was added
        TransferEventLoggingUtilsTest.verifyNFTLogEvent(logs, expectedTransfers);
    }
}

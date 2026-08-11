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
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ALIASED_RECEIVER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ACCOUNT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RECEIVER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.V2_TRANSFER_DISABLED_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asBytesResult;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.readableRevertReason;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.tokenTransfersLists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TransferList;
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
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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

    @Test
    void gasRequirementReflectsHbarAutoCreations() {
        final var shardNum = 0L;
        final var realmNum = 0L;

        final var aliasToCreate = Bytes.wrap("alias-to-create".getBytes());
        final var existingAlias = Bytes.wrap("existing-alias".getBytes());

        final var autoCreatedAccountId = AccountID.newBuilder()
                .shardNum(shardNum)
                .realmNum(realmNum)
                .alias(aliasToCreate)
                .build();

        final var existingAccountWithAlias = AccountID.newBuilder()
                .shardNum(shardNum)
                .realmNum(realmNum)
                .alias(existingAlias)
                .build();

        final var payerAccountId = AccountID.newBuilder()
                .shardNum(shardNum)
                .realmNum(realmNum)
                .accountNum(1L)
                .build();

        // One positive HBAR transfer to a new alias (triggers lazy creation)
        final var creditNewAlias = AccountAmount.newBuilder()
                .accountID(autoCreatedAccountId)
                .amount(10L)
                .build();

        // Another positive HBAR transfer to the same alias; should not increase lazy-creation count
        final var anotherCreditSameAlias = AccountAmount.newBuilder()
                .accountID(autoCreatedAccountId)
                .amount(20L)
                .build();

        // Positive HBAR transfer to an existing alias (no lazy creation)
        final var creditExistingAlias = AccountAmount.newBuilder()
                .accountID(existingAccountWithAlias)
                .amount(30L)
                .build();

        // Positive HBAR transfer to a non-aliased account (payer); condition true but no alias
        final var creditPayerNoAlias =
                AccountAmount.newBuilder().accountID(payerAccountId).amount(5L).build();

        // Negative HBAR transfer from a non-aliased account (payer); not considered for lazy creation
        final var debitPayer = AccountAmount.newBuilder()
                .accountID(payerAccountId)
                .amount(-65L)
                .build();

        final var transferList = TransferList.newBuilder()
                .accountAmounts(
                        creditNewAlias, anotherCreditSameAlias, creditExistingAlias, creditPayerNoAlias, debitPayer)
                .build();

        final var op = CryptoTransferTransactionBody.newBuilder()
                .transfers(transferList)
                .build();

        // Only the aliasToCreate is missing, existingAlias is already mapped
        given(readableAccountStore.getAccountIDByAlias(shardNum, realmNum, aliasToCreate))
                .willReturn(null);
        given(readableAccountStore.getAccountIDByAlias(shardNum, realmNum, existingAlias))
                .willReturn(existingAccountWithAlias);

        final long baseUnitAdjustTinyCentPrice = 0L; // No token transfers in this test
        final long baseAdjustTinyCentsPrice = 10L;
        final long baseNftTransferTinyCentsPrice = 0L; // No NFT transfers in this test
        final long baseLazyCreationPrice = 1_000L;

        final long result = ClassicTransfersCall.minimumTinycentPriceGiven(
                op,
                baseUnitAdjustTinyCentPrice,
                baseAdjustTinyCentsPrice,
                baseNftTransferTinyCentsPrice,
                baseLazyCreationPrice,
                readableAccountStore);

        final long numTinyCentsAdjusts = 5L; // five AccountAmount entries in the HBAR TransferList
        final long expected = numTinyCentsAdjusts * baseAdjustTinyCentsPrice
                + baseLazyCreationPrice; // exactly one distinct missing alias

        assertEquals(expected, result);
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

    public static List<SystemContractMethod> ercEventsEmissionForFtParams() {
        return List.of(
                ClassicTransfersTranslator.CRYPTO_TRANSFER,
                ClassicTransfersTranslator.CRYPTO_TRANSFER_V2,
                ClassicTransfersTranslator.TRANSFER_TOKENS,
                ClassicTransfersTranslator.TRANSFER_TOKEN,
                ClassicTransfersTranslator.TRANSFER_FROM);
    }

    @ParameterizedTest
    @MethodSource("ercEventsEmissionForFtParams")
    void ercEventsEmissionForFT(final SystemContractMethod function) {
        final var localSubject = givenSubject(function.selector());
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(A_NEW_ACCOUNT_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);
        given(nativeOperations.readableAccountStore()).willReturn(readableAccountStore);
        final var expectedTransfers = List.of(new TestHelpers.TestTokenTransfer(
                FUNGIBLE_TOKEN_ID, false, OWNER_ID, OWNER_ACCOUNT, RECEIVER_ID, ALIASED_RECEIVER, 123));
        given(recordBuilder.tokenTransferLists()).willReturn(tokenTransfersLists(expectedTransfers));
        given(readableAccountStore.getAliasedAccountById(OWNER_ID)).willReturn(OWNER_ACCOUNT);
        given(readableAccountStore.getAliasedAccountById(RECEIVER_ID)).willReturn(ALIASED_RECEIVER);
        final List<Log> logs = new ArrayList<>();
        Mockito.doAnswer(e -> logs.add(e.getArgument(0))).when(frame).addLog(any());

        final var result = localSubject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(tuweniEncodedRc(SUCCESS), result.output());
        // check that events was added
        TransferEventLoggingUtilsTest.validateFtLogEvent(logs, expectedTransfers);
    }

    public static List<SystemContractMethod> ercEventsEmissionForNftParams() {
        return List.of(
                ClassicTransfersTranslator.CRYPTO_TRANSFER,
                ClassicTransfersTranslator.CRYPTO_TRANSFER_V2,
                ClassicTransfersTranslator.TRANSFER_NFTS,
                ClassicTransfersTranslator.TRANSFER_NFT,
                ClassicTransfersTranslator.TRANSFER_NFT_FROM);
    }

    @ParameterizedTest
    @MethodSource("ercEventsEmissionForNftParams")
    void ercEventsEmissionForNFT(final SystemContractMethod function) {
        final var localSubject = givenSubject(function.selector());
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(A_NEW_ACCOUNT_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);
        given(nativeOperations.readableAccountStore()).willReturn(readableAccountStore);
        final var expectedTransfers = List.of(new TestHelpers.TestTokenTransfer(
                FUNGIBLE_TOKEN_ID, true, OWNER_ID, OWNER_ACCOUNT, RECEIVER_ID, ALIASED_RECEIVER, 123));
        given(recordBuilder.tokenTransferLists()).willReturn(tokenTransfersLists(expectedTransfers));
        given(readableAccountStore.getAliasedAccountById(OWNER_ID)).willReturn(OWNER_ACCOUNT);
        given(readableAccountStore.getAliasedAccountById(RECEIVER_ID)).willReturn(ALIASED_RECEIVER);
        final List<Log> logs = new ArrayList<>();
        Mockito.doAnswer(e -> logs.add(e.getArgument(0))).when(frame).addLog(any());

        final var result = localSubject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(tuweniEncodedRc(SUCCESS), result.output());
        // check that events was added
        TransferEventLoggingUtilsTest.validateNftLogEvent(logs, expectedTransfers);
    }
}

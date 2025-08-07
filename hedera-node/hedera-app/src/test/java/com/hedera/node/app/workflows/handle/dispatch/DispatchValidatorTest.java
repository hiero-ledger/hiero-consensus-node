// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.dispatch;

import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.DuplicateStatus.DUPLICATE;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.DuplicateStatus.NO_DUPLICATE;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.OfferedFeeCheck.CHECK_OFFERED_FEE;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.OfferedFeeCheck.SKIP_OFFERED_FEE_CHECK;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.ServiceFeeStatus.CAN_PAY_SERVICE_FEE;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.ServiceFeeStatus.UNABLE_TO_PAY_SERVICE_FEE;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.WorkflowCheck.NOT_INGEST;
import static com.hedera.node.app.workflows.handle.dispatch.ValidationResult.newCreatorError;
import static com.hedera.node.app.workflows.handle.dispatch.ValidationResult.newPayerError;
import static com.hedera.node.app.workflows.handle.dispatch.ValidationResult.newSuccess;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.NODE_DUE_DILIGENCE_FAILURE;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.PRE_HANDLE_FAILURE;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.SO_FAR_SO_GOOD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.AppFeeCharging;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.signature.AppKeyVerifier;
import com.hedera.node.app.signature.impl.SignatureVerificationImpl;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.InsufficientNonFeeDebitsException;
import com.hedera.node.app.spi.workflows.InsufficientServiceFeeException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.SolvencyPreCheck;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collections;
import java.util.function.Consumer;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DispatchValidatorTest {
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final AccountID PAYER_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(1_234).build();
    private static final AccountID CREATOR_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(3).build();
    private static final NodeId CREATOR_NODE_ID = NodeId.of(0L);

    @Mock
    private NodeInfo creatorInfo;

    @Mock
    private AppKeyVerifier keyVerifier;

    @Mock
    private SolvencyPreCheck solvencyPreCheck;

    @Mock
    private HederaRecordCache recordCache;

    @Mock
    private TransactionChecker transactionChecker;

    @Mock
    private Dispatch dispatch;

    @Mock
    private ReadableStoreFactory storeFactory;

    @Mock
    private ReadableAccountStore readableAccountStore;

    private DispatchValidator subject;

    @BeforeEach
    void setUp() {
        subject = new DispatchValidator(recordCache, transactionChecker, new AppFeeCharging(solvencyPreCheck), null);
    }

    @Test
    void dueDiligencePreHandleIsCreatorError() {
        givenCreatorInfo();
        given(dispatch.preHandleResult()).willReturn(INVALID_PAYER_SIG_PREHANDLE);

        final var report = subject.validateFeeChargingScenario(dispatch);

        assertEquals(newCreatorError(dispatch.creatorInfo().accountId(), INVALID_PAYER_SIGNATURE), report);
    }

    @Test
    void invalidTransactionDurationIsCreatorError() throws PreCheckException {
        givenCreatorInfo();
        givenUserDispatch();
        given(dispatch.txnInfo()).willReturn(TXN_INFO);
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        given(dispatch.consensusNow()).willReturn(CONSENSUS_NOW);
        doThrow(new PreCheckException(INVALID_TRANSACTION_DURATION))
                .when(transactionChecker)
                .checkTimeBox(TXN_BODY, CONSENSUS_NOW, TransactionChecker.RequireMinValidLifetimeBuffer.NO);

        final var report = subject.validateFeeChargingScenario(dispatch);

        assertEquals(newCreatorError(dispatch.creatorInfo().accountId(), INVALID_TRANSACTION_DURATION), report);
    }

    @Test
    void invalidPayerSigIsCreatorError() {
        givenCreatorInfo();
        givenUserDispatch();
        given(dispatch.txnInfo()).willReturn(TXN_INFO);
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        givenPayer(payer -> payer.tinybarBalance(1L));
        givenInvalidPayerSig();

        final var report = subject.validateFeeChargingScenario(dispatch);

        assertEquals(newCreatorError(dispatch.creatorInfo().accountId(), INVALID_PAYER_SIGNATURE), report);
    }

    @Test
    void solvencyCheckDoesNotLookAtOfferedFeesForPrecedingDispatch() throws PreCheckException {
        givenCreatorInfo();
        givenPreceding();
        givenSolvencyCheckSetup();
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        final var payerAccount = givenPayer(payer -> payer.tinybarBalance(1L));
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());

        final var report = subject.validateFeeChargingScenario(dispatch);

        verify(solvencyPreCheck)
                .checkSolvency(
                        TXN_BODY,
                        PAYER_ACCOUNT_ID,
                        TXN_INFO.functionality(),
                        payerAccount,
                        FEES,
                        NOT_INGEST,
                        SKIP_OFFERED_FEE_CHECK);
        assertEquals(newSuccess(dispatch.creatorInfo().accountId(), payerAccount), report);
    }

    @Test
    void hollowUserDoesNotRequireSig() throws PreCheckException {
        givenCreatorInfo();
        givenUserDispatch();
        givenNonDuplicate();
        givenSolvencyCheckSetup();
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        final var payerAccount = givenPayer(payer -> payer.tinybarBalance(1L)
                .alias(Bytes.fromHex("abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd"))
                .key(IMMUTABILITY_SENTINEL_KEY));
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());

        final var report = subject.validateFeeChargingScenario(dispatch);

        verify(solvencyPreCheck)
                .checkSolvency(
                        TXN_BODY,
                        PAYER_ACCOUNT_ID,
                        TXN_INFO.functionality(),
                        payerAccount,
                        FEES,
                        NOT_INGEST,
                        CHECK_OFFERED_FEE);
        assertEquals(newSuccess(dispatch.creatorInfo().accountId(), payerAccount), report);
    }

    @Test
    void otherNodeDuplicateUserIsPayerError() throws PreCheckException {
        givenCreatorInfo();
        givenUserDispatch();
        givenOtherNodeDuplicate();
        givenSolvencyCheckSetup();
        givenValidPayerSig();
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        final var payerAccount = givenPayer(payer -> payer.tinybarBalance(123L));

        final var report = subject.validateFeeChargingScenario(dispatch);

        verify(solvencyPreCheck)
                .checkSolvency(
                        TXN_BODY,
                        PAYER_ACCOUNT_ID,
                        TXN_INFO.functionality(),
                        payerAccount,
                        FEES.withoutServiceComponent(),
                        NOT_INGEST,
                        CHECK_OFFERED_FEE);
        assertEquals(
                newPayerError(
                        dispatch.creatorInfo().accountId(),
                        payerAccount,
                        DUPLICATE_TRANSACTION,
                        CAN_PAY_SERVICE_FEE,
                        DUPLICATE),
                report);
    }

    @Test
    void sameNodeDuplicateUserIsCreatorError() {
        givenCreatorInfo();
        givenUserDispatch();
        givenSameNodeDuplicate();
        given(dispatch.txnInfo()).willReturn(TXN_INFO);
        givenValidPayerSig();
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        givenPayer(payer -> payer.tinybarBalance(123L));

        final var report = subject.validateFeeChargingScenario(dispatch);

        assertEquals(newCreatorError(dispatch.creatorInfo().accountId(), DUPLICATE_TRANSACTION), report);
    }

    @Test
    void userPreHandleFailureIsPayerError() throws PreCheckException {
        givenCreatorInfo();
        givenUserDispatch();
        givenNonDuplicate();
        givenSolvencyCheckSetup();
        givenValidPayerSig();
        given(dispatch.preHandleResult()).willReturn(UNSUCCESSFUL_PREHANDLE);
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        final var payerAccount = givenPayer(payer -> payer.tinybarBalance(123L));

        final var report = subject.validateFeeChargingScenario(dispatch);

        verify(solvencyPreCheck)
                .checkSolvency(
                        TXN_BODY,
                        PAYER_ACCOUNT_ID,
                        TXN_INFO.functionality(),
                        payerAccount,
                        FEES,
                        NOT_INGEST,
                        CHECK_OFFERED_FEE);
        assertEquals(
                newPayerError(
                        dispatch.creatorInfo().accountId(),
                        payerAccount,
                        UNSUCCESSFUL_PREHANDLE.responseCode(),
                        CAN_PAY_SERVICE_FEE,
                        NO_DUPLICATE),
                report);
    }

    @Test
    void precedingInsufficientServiceFeeIsPayerError() throws PreCheckException {
        givenCreatorInfo();
        givenChildDispatch();
        givenSolvencyCheckSetup();
        final var payerAccount = givenPayer(payer -> payer.tinybarBalance(1L));
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        doThrow(new InsufficientServiceFeeException(INSUFFICIENT_ACCOUNT_BALANCE, FEES.totalFee()))
                .when(solvencyPreCheck)
                .checkSolvency(
                        TXN_BODY,
                        PAYER_ACCOUNT_ID,
                        TXN_INFO.functionality(),
                        payerAccount,
                        FEES,
                        NOT_INGEST,
                        SKIP_OFFERED_FEE_CHECK);
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());

        final var report = subject.validateFeeChargingScenario(dispatch);

        assertEquals(
                newPayerError(
                        dispatch.creatorInfo().accountId(),
                        payerAccount,
                        INSUFFICIENT_ACCOUNT_BALANCE,
                        UNABLE_TO_PAY_SERVICE_FEE,
                        NO_DUPLICATE),
                report);
    }

    @Test
    void scheduledNonFeeDebitsFeeIsPayerError() throws PreCheckException {
        givenCreatorInfo();
        givenScheduledDispatch();
        givenValidPayerSig();
        givenSolvencyCheckSetup();
        final var payerAccount = givenPayer(payer -> payer.tinybarBalance(1L));
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        doThrow(new InsufficientNonFeeDebitsException(INSUFFICIENT_ACCOUNT_BALANCE, FEES.totalFee()))
                .when(solvencyPreCheck)
                .checkSolvency(
                        TXN_BODY,
                        PAYER_ACCOUNT_ID,
                        TXN_INFO.functionality(),
                        payerAccount,
                        FEES,
                        NOT_INGEST,
                        CHECK_OFFERED_FEE);
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());

        final var report = subject.validateFeeChargingScenario(dispatch);

        assertEquals(
                newPayerError(
                        dispatch.creatorInfo().accountId(),
                        payerAccount,
                        INSUFFICIENT_ACCOUNT_BALANCE,
                        CAN_PAY_SERVICE_FEE,
                        NO_DUPLICATE),
                report);
    }

    @Test
    void insufficientNetworkFeeIsCreatorError() throws PreCheckException {
        givenCreatorInfo();
        givenUserDispatch();
        givenValidPayerSig();
        givenNonDuplicate();
        givenSolvencyCheckSetup();
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        final var payerAccount = givenPayer(payer -> payer.tinybarBalance(1L));
        doThrow(new PreCheckException(INSUFFICIENT_PAYER_BALANCE))
                .when(solvencyPreCheck)
                .checkSolvency(
                        TXN_BODY,
                        PAYER_ACCOUNT_ID,
                        TXN_INFO.functionality(),
                        payerAccount,
                        FEES,
                        NOT_INGEST,
                        CHECK_OFFERED_FEE);
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());

        final var report = subject.validateFeeChargingScenario(dispatch);

        assertEquals(newCreatorError(dispatch.creatorInfo().accountId(), INSUFFICIENT_PAYER_BALANCE), report);
    }

    @Test
    void deletedUserPayerIsFailInvalid() {
        givenUserDispatch();
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        givenPayer(payer -> payer.tinybarBalance(1L).deleted(true));
        given(dispatch.txnInfo()).willReturn(TXN_INFO);
        assertThrows(IllegalStateException.class, () -> subject.validateFeeChargingScenario(dispatch));
    }

    @Test
    void missingPayerIsFailInvalidForChildDispatch() {
        givenChildDispatch();
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        givenMissingPayer();
        assertThrows(IllegalStateException.class, () -> subject.validateFeeChargingScenario(dispatch));
    }

    @Test
    void contractUserPayerIsFailInvalid() {
        givenUserDispatch();
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        givenPayer(payer -> payer.tinybarBalance(1L).smartContract(true));
        given(dispatch.txnInfo()).willReturn(TXN_INFO);
        assertThrows(IllegalStateException.class, () -> subject.validateFeeChargingScenario(dispatch));
    }

    @Test
    void missingUserPayerIsFailInvalid() {
        givenUserDispatch();
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        givenMissingPayer();
        given(dispatch.txnInfo()).willReturn(TXN_INFO);
        assertThrows(IllegalStateException.class, () -> subject.validateFeeChargingScenario(dispatch));
    }

    @Test
    void missingScheduledPayerIsFailInvalid() {
        givenScheduledDispatch();
        given(dispatch.preHandleResult()).willReturn(SUCCESSFUL_PREHANDLE);
        givenMissingPayer();
        assertThrows(IllegalStateException.class, () -> subject.validateFeeChargingScenario(dispatch));
    }

    private void givenMissingPayer() {
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.readableStoreFactory()).willReturn(storeFactory);
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
    }

    private void givenSolvencyCheckSetup() {
        given(dispatch.txnInfo()).willReturn(TXN_INFO);
        given(dispatch.fees()).willReturn(FEES);
    }

    private void givenNonDuplicate() {
        given(creatorInfo.nodeId()).willReturn(CREATOR_NODE_ID.id());
        given(recordCache.hasDuplicate(TXN_BODY.transactionIDOrThrow(), CREATOR_NODE_ID.id()))
                .willReturn(HederaRecordCache.DuplicateCheckResult.NO_DUPLICATE);
    }

    private void givenOtherNodeDuplicate() {
        given(creatorInfo.nodeId()).willReturn(CREATOR_NODE_ID.id());
        given(recordCache.hasDuplicate(TXN_BODY.transactionIDOrThrow(), CREATOR_NODE_ID.id()))
                .willReturn(HederaRecordCache.DuplicateCheckResult.OTHER_NODE);
    }

    private void givenSameNodeDuplicate() {
        given(creatorInfo.nodeId()).willReturn(CREATOR_NODE_ID.id());
        given(recordCache.hasDuplicate(TXN_BODY.transactionIDOrThrow(), CREATOR_NODE_ID.id()))
                .willReturn(HederaRecordCache.DuplicateCheckResult.SAME_NODE);
    }

    private void givenValidPayerSig() {
        given(dispatch.keyVerifier()).willReturn(keyVerifier);
        given(keyVerifier.verificationFor(Key.DEFAULT)).willReturn(PASSED_VERIFICATION);
    }

    private void givenInvalidPayerSig() {
        given(dispatch.keyVerifier()).willReturn(keyVerifier);
        given(keyVerifier.verificationFor(Key.DEFAULT)).willReturn(FAILED_VERIFICATION);
    }

    private void givenChildDispatch() {
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.CHILD);
    }

    private void givenPreceding() {
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.PRECEDING);
    }

    private void givenUserDispatch() {
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.USER);
    }

    private void givenScheduledDispatch() {
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.SCHEDULED);
    }

    private void givenCreatorInfo() {
        given(dispatch.creatorInfo()).willReturn(creatorInfo);
        given(creatorInfo.accountId()).willReturn(CREATOR_ACCOUNT_ID);
    }

    private Account givenPayer(@NonNull final Consumer<Account.Builder> spec) {
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.readableStoreFactory()).willReturn(storeFactory);
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        final var payer = Account.newBuilder().accountId(PAYER_ACCOUNT_ID).key(Key.DEFAULT);
        spec.accept(payer);
        final var payerAccount = payer.build();
        given(readableAccountStore.getAccountById(PAYER_ACCOUNT_ID)).willReturn(payerAccount);
        return payerAccount;
    }

    private static final PreHandleResult INVALID_PAYER_SIG_PREHANDLE = new PreHandleResult(
            null,
            null,
            NODE_DUE_DILIGENCE_FAILURE,
            INVALID_PAYER_SIGNATURE,
            null,
            Collections.emptySet(),
            null,
            Collections.emptySet(),
            null,
            null,
            0);
    private static final PreHandleResult SUCCESSFUL_PREHANDLE = new PreHandleResult(
            null,
            null,
            SO_FAR_SO_GOOD,
            SUCCESS,
            null,
            Collections.emptySet(),
            null,
            Collections.emptySet(),
            null,
            null,
            0);
    private static final PreHandleResult UNSUCCESSFUL_PREHANDLE = new PreHandleResult(
            null,
            null,
            PRE_HANDLE_FAILURE,
            INVALID_ACCOUNT_ID,
            null,
            Collections.emptySet(),
            null,
            Collections.emptySet(),
            null,
            null,
            0);

    private static final TransactionBody TXN_BODY = TransactionBody.newBuilder()
            .transactionID(
                    TransactionID.newBuilder().accountID(PAYER_ACCOUNT_ID).build())
            .build();
    private static final TransactionInfo TXN_INFO = new TransactionInfo(
            SignedTransaction.DEFAULT,
            TXN_BODY,
            SignatureMap.DEFAULT,
            Bytes.EMPTY,
            HederaFunctionality.CRYPTO_TRANSFER,
            null);

    private static final Fees FEES = new Fees(1L, 2L, 3L);

    private static final SignatureVerification PASSED_VERIFICATION =
            new SignatureVerificationImpl(Key.DEFAULT, Bytes.EMPTY, true);

    private static final SignatureVerification FAILED_VERIFICATION =
            new SignatureVerificationImpl(Key.DEFAULT, Bytes.EMPTY, false);
}

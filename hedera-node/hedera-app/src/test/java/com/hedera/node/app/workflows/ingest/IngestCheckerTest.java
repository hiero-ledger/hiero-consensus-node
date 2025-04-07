// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_ADD_LIVE_HASH;
import static com.hedera.hapi.node.base.HederaFunctionality.FREEZE;
import static com.hedera.hapi.node.base.HederaFunctionality.UNCHECKED_SUBMIT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BUSY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PLATFORM_NOT_ACTIVE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNAUTHORIZED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.WAITING_FOR_LEDGER_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.estimatedFee;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.WorkflowCheck.INGEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.freeze.FreezeTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoAddLiveHashTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.UncheckedSubmitBody;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.info.CurrentPlatformStatus;
import com.hedera.node.app.signature.SignatureExpander;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.signature.SignatureVerifier;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.workflows.InsufficientBalanceException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.app.state.recordcache.DeduplicationCacheImpl;
import com.hedera.node.app.throttle.SynchronizedThrottleAccumulator;
import com.hedera.node.app.version.ServicesSoftwareVersion;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.SolvencyPreCheck;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngestCheckerTest extends AppTestBase {
    private static final SignatureMap MOCK_SIGNATURE_MAP =
            SignatureMap.newBuilder().build();

    private static final Fees DEFAULT_FEES = new Fees(100L, 20L, 3L);

    private final InstantSource instantSource = InstantSource.system();
    private final int maxBytes = 6144;

    @Mock(strictness = LENIENT)
    CurrentPlatformStatus currentPlatformStatus;

    @Mock(strictness = LENIENT)
    TransactionChecker transactionChecker;

    @Mock(strictness = LENIENT)
    private SignatureExpander signatureExpander;

    @Mock(strictness = LENIENT)
    private SignatureVerifier signatureVerifier;

    @Mock(strictness = LENIENT)
    private SolvencyPreCheck solvencyPreCheck;

    @Mock(strictness = LENIENT)
    private TransactionDispatcher dispatcher;

    @Mock(strictness = LENIENT)
    private FeeManager feeManager;

    @Mock(strictness = LENIENT)
    private Authorizer authorizer;

    @Mock(strictness = LENIENT)
    private BlockStreamManager blockStreamManager;

    @Mock
    private OpWorkflowMetrics opWorkflowMetrics;

    @Mock(strictness = LENIENT)
    private SynchronizedThrottleAccumulator synchronizedThrottleAccumulator;

    private DeduplicationCache deduplicationCache;

    private TransactionInfo transactionInfo;
    private TransactionBody txBody;
    private Transaction tx;
    private Bytes serializedTx;

    private Configuration configuration;

    private IngestChecker subject;

    @BeforeEach
    void setUp() throws PreCheckException {
        setupStandardStates();
        when(currentPlatformStatus.get()).thenReturn(PlatformStatus.ACTIVE);

        configuration = new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1L);

        txBody = TransactionBody.newBuilder()
                .uncheckedSubmit(UncheckedSubmitBody.newBuilder().build())
                .transactionID(TransactionID.newBuilder()
                        .accountID(ALICE.accountID())
                        .transactionValidStart(
                                Timestamp.newBuilder().seconds(Instant.now().getEpochSecond())))
                .nodeAccountID(nodeSelfAccountId)
                .build();
        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(asBytes(TransactionBody.PROTOBUF, txBody))
                .build();
        tx = Transaction.newBuilder()
                .signedTransactionBytes(asBytes(SignedTransaction.PROTOBUF, signedTx))
                .build();
        serializedTx = Transaction.PROTOBUF.toBytes(tx);

        transactionInfo = new TransactionInfo(
                tx, txBody, MOCK_SIGNATURE_MAP, tx.signedTransactionBytes(), UNCHECKED_SUBMIT, serializedTx);
        when(transactionChecker.parseAndCheck(serializedTx, maxBytes)).thenReturn(transactionInfo);

        final var configProvider = HederaTestConfigBuilder.createConfigProvider();
        this.deduplicationCache = new DeduplicationCacheImpl(configProvider, instantSource);

        when(solvencyPreCheck.getPayerAccount(any(), eq(ALICE.accountID()))).thenReturn(ALICE.account());
        when(dispatcher.dispatchComputeFees(any())).thenReturn(DEFAULT_FEES);

        subject = new IngestChecker(
                nodeSelfAccountId,
                currentPlatformStatus,
                blockStreamManager,
                transactionChecker,
                solvencyPreCheck,
                signatureExpander,
                signatureVerifier,
                deduplicationCache,
                dispatcher,
                feeManager,
                authorizer,
                synchronizedThrottleAccumulator,
                instantSource,
                opWorkflowMetrics,
                ServicesSoftwareVersion::new,
                null);
    }

    @Nested
    @DisplayName("0. Node state pre-checks")
    class NodeTests {

        @Test
        @DisplayName("When the node is ok, no exception should be thrown")
        void testNodeStateSucceeds() {
            given(blockStreamManager.hasLedgerId()).willReturn(true);
            assertThatCode(() -> subject.verifyPlatformActive()).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @EnumSource(PlatformStatus.class)
        @DisplayName("When the platform is not ACTIVE, the transaction should be rejected")
        void testParseAndCheckWithInactivePlatformFails(final PlatformStatus status) {
            // Since the enum source is going over all states, and the ACTIVE state is
            // actually good, I need to skip that one.
            if (status != PlatformStatus.ACTIVE) {
                // Given a platform that is not ACTIVE
                when(currentPlatformStatus.get()).thenReturn(status);
                given(blockStreamManager.hasLedgerId()).willReturn(true);
                // When we try to parse and check a transaction, it should fail because the platform is not active
                assertThatThrownBy(() -> subject.verifyPlatformActive())
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(PLATFORM_NOT_ACTIVE));
                verify(opWorkflowMetrics, never()).incrementThrottled(any());
            }
        }

        @Test
        @DisplayName("Even if the platform is not ACTIVE, waits for ledger id to be available")
        void testParseAndCheckWithUnknownLedgerIdFails() {
            when(currentPlatformStatus.get()).thenReturn(PlatformStatus.ACTIVE);
            // When we try to parse and check a transaction, it should fail because the platform is not active
            assertThatThrownBy(() -> subject.verifyReadyForTransactions())
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(WAITING_FOR_LEDGER_ID));
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }
    }

    @Test
    @DisplayName("A wrong nodeId in transaction fails")
    void testWrongNodeIdFails() {
        // Given a transaction with an unknown node ID
        final var otherNodeSelfAccountId = AccountID.newBuilder()
                .accountNum(nodeSelfAccountId.accountNumOrElse(0L) + 1L)
                .build();

        subject = new IngestChecker(
                otherNodeSelfAccountId,
                currentPlatformStatus,
                blockStreamManager,
                transactionChecker,
                solvencyPreCheck,
                signatureExpander,
                signatureVerifier,
                deduplicationCache,
                dispatcher,
                feeManager,
                authorizer,
                synchronizedThrottleAccumulator,
                instantSource,
                opWorkflowMetrics,
                ServicesSoftwareVersion::new,
                null);

        // Then the checker should throw a PreCheckException
        assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_NODE_ACCOUNT));
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    @DisplayName("Run all checks successfully")
    void testRunAllChecksSuccessfully() throws Exception {
        // given
        final var expected = new TransactionInfo(
                tx, txBody, MOCK_SIGNATURE_MAP, tx.signedTransactionBytes(), UNCHECKED_SUBMIT, serializedTx);
        final var verificationResultFuture = mock(SignatureVerificationFuture.class);
        final var verificationResult = mock(SignatureVerification.class);
        when(verificationResult.failed()).thenReturn(false);
        when(verificationResultFuture.get(anyLong(), any())).thenReturn(verificationResult);
        when(signatureVerifier.verify(any(), any()))
                .thenReturn(Map.of(ALICE.account().keyOrThrow(), verificationResultFuture));

        // when
        final var actual = subject.runAllChecks(state, serializedTx, configuration);

        // then
        assertThat(actual).isEqualTo(expected);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Nested
    @DisplayName("1. Check the syntax")
    class SyntaxCheckTests {
        /**
         * It is not necessary to test all the possible failure reasons, just a few to make sure that
         * the checker is passing the failure reason to the response.
         * @return a stream of arguments with the failure reason
         */
        public static Stream<Arguments> failureReasons() {
            return Stream.of(Arguments.of(INVALID_TRANSACTION), Arguments.of(INVALID_TRANSACTION_BODY));
        }

        @ParameterizedTest(name = "TransactionChecker fails with error code {0}")
        @MethodSource("failureReasons")
        @DisplayName("If the transaction fails TransactionChecker, a failure response is returned with the right error")
        void onsetFailsWithPreCheckException(ResponseCodeEnum failureReason) throws PreCheckException {
            // Given a TransactionChecker that will throw a PreCheckException with the given failure reason
            when(transactionChecker.parseAndCheck(any(), eq(maxBytes))).thenThrow(new PreCheckException(failureReason));

            // When the transaction is checked
            assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(failureReason));
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }

        @Test
        @DisplayName("If some random exception is thrown from TransactionChecker, the exception is bubbled up")
        void randomException() throws PreCheckException {
            // Given a WorkflowOnset that will throw a RuntimeException
            when(transactionChecker.parseAndCheck(any(), eq(maxBytes)))
                    .thenThrow(new RuntimeException("check exception"));

            // When the transaction is submitted, then the exception is bubbled up
            assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("check exception");
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }
    }

    @Nested
    @DisplayName("3. Deduplication")
    class DuplicationTests {
        @Test
        @DisplayName("The second of two transactions with the same transaction ID should be rejected")
        void testThrottleFails() {
            // Given a deduplication cache, and a transaction with an ID already in the deduplication cache
            final var id = txBody.transactionIDOrThrow();
            deduplicationCache.add(id);
            // When the transaction is checked, then it throws a PreCheckException due to duplication
            assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", DUPLICATE_TRANSACTION);
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }
    }

    @Nested
    @DisplayName("4. Check throttles")
    class ThrottleTests {

        @Test
        @DisplayName("When the transaction is throttled, the transaction should be rejected")
        void testThrottleFails() {
            // Given a throttle on CONSENSUS_CREATE_TOPIC transactions (i.e. it is time to throttle)
            when(synchronizedThrottleAccumulator.shouldThrottle(transactionInfo, state))
                    .thenReturn(true);

            // When the transaction is submitted
            assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", BUSY);
            verify(opWorkflowMetrics).incrementThrottled(UNCHECKED_SUBMIT);
        }

        @Test
        @DisplayName("Unsupported transaction functionality should throw NOT_SUPPORTED")
        void unsupportedTransactionFunctionality() throws PreCheckException {
            final TransactionBody cryptoAddLiveHashTxBody = TransactionBody.newBuilder()
                    .cryptoAddLiveHash(
                            CryptoAddLiveHashTransactionBody.newBuilder().build())
                    .transactionID(TransactionID.newBuilder()
                            .accountID(ALICE.accountID())
                            .transactionValidStart(
                                    Timestamp.newBuilder().seconds(Instant.now().getEpochSecond())))
                    .nodeAccountID(nodeSelfAccountId)
                    .build();
            final var signedTx = SignedTransaction.newBuilder()
                    .bodyBytes(asBytes(TransactionBody.PROTOBUF, cryptoAddLiveHashTxBody))
                    .build();
            final var cryptoAddLiveHashTx = Transaction.newBuilder()
                    .signedTransactionBytes(asBytes(SignedTransaction.PROTOBUF, signedTx))
                    .build();
            final var serializedCryptoAddLiveHashTx = Transaction.PROTOBUF.toBytes(cryptoAddLiveHashTx);

            final var cryptoAddLiveHashTransactionInfo = new TransactionInfo(
                    cryptoAddLiveHashTx,
                    cryptoAddLiveHashTxBody,
                    MOCK_SIGNATURE_MAP,
                    cryptoAddLiveHashTx.signedTransactionBytes(),
                    CRYPTO_ADD_LIVE_HASH,
                    serializedCryptoAddLiveHashTx);
            when(transactionChecker.parseAndCheck(serializedCryptoAddLiveHashTx, maxBytes))
                    .thenReturn(cryptoAddLiveHashTransactionInfo);

            assertThatThrownBy(() -> subject.runAllChecks(state, serializedCryptoAddLiveHashTx, configuration))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", NOT_SUPPORTED);
        }

        @Test
        @DisplayName("Privileged transaction functionality should throw NOT_SUPPORTED for non-privileged accounts")
        void privilegedTransactionFunctionality() throws PreCheckException {
            final TransactionBody freezeTxBody = TransactionBody.newBuilder()
                    .freeze(FreezeTransactionBody.newBuilder().build())
                    .transactionID(TransactionID.newBuilder()
                            .accountID(ALICE.accountID()) // a non-privileged account
                            .transactionValidStart(
                                    Timestamp.newBuilder().seconds(Instant.now().getEpochSecond())))
                    .nodeAccountID(nodeSelfAccountId)
                    .build();
            final var signedTx = SignedTransaction.newBuilder()
                    .bodyBytes(asBytes(TransactionBody.PROTOBUF, freezeTxBody))
                    .build();
            final var freezeTx = Transaction.newBuilder()
                    .signedTransactionBytes(asBytes(SignedTransaction.PROTOBUF, signedTx))
                    .build();
            final var serializedFreezeTx = Transaction.PROTOBUF.toBytes(freezeTx);

            final var freezeTransactionInfo = new TransactionInfo(
                    freezeTx,
                    freezeTxBody,
                    MOCK_SIGNATURE_MAP,
                    freezeTx.signedTransactionBytes(),
                    FREEZE,
                    serializedFreezeTx);
            when(transactionChecker.parseAndCheck(serializedFreezeTx, maxBytes)).thenReturn(freezeTransactionInfo);

            assertThatThrownBy(() -> subject.runAllChecks(state, serializedFreezeTx, configuration))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", NOT_SUPPORTED);
        }

        @Test
        @DisplayName("If some random exception is thrown from HapiThrottling, the exception is bubbled up")
        void randomException() {
            // Given a HapiThrottling that will throw a RuntimeException
            when(synchronizedThrottleAccumulator.shouldThrottle(transactionInfo, state))
                    .thenThrow(new RuntimeException("shouldThrottle exception"));

            // When the transaction is submitted, then the exception is bubbled up
            assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("shouldThrottle exception");
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }
    }

    @Nested
    @DisplayName("5.a Check account status")
    class PayerAccountStatusTests {

        public static Stream<Arguments> failureReasons() {
            return Stream.of(Arguments.of(INVALID_ACCOUNT_ID), Arguments.of(ACCOUNT_DELETED));
        }

        @ParameterizedTest(name = "Check of account status fails with error code {0}")
        @MethodSource("failureReasons")
        @DisplayName("If the status of the payer account is invalid, the transaction should be rejected")
        void payerAccountStatusFails(ResponseCodeEnum failureReason) throws PreCheckException {
            doThrow(new PreCheckException(failureReason)).when(solvencyPreCheck).getPayerAccount(any(), any());

            assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(failureReason));
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }

        @Test
        @DisplayName("If some random exception is thrown from account status check, the exception is bubbled up")
        void randomException() throws PreCheckException {
            // Given an IngestChecker that will throw a RuntimeException from checkPayerSignature
            doThrow(new RuntimeException("checkPayerAccountStatus exception"))
                    .when(solvencyPreCheck)
                    .getPayerAccount(any(), any());

            // When the transaction is submitted, then the exception is bubbled up
            assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("checkPayerAccountStatus exception");
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }

        // NOTE: This should never happen in real life, but we need to code defensively for it anyway.
        @Test
        @DisplayName("No key for payer in state")
        void noKeyForPayer() throws PreCheckException {
            // The tx payer is ALICE. We remove her key from state
            final var account = ALICE.account().copyBuilder().key((Key) null).build();
            when(solvencyPreCheck.getPayerAccount(any(), eq(ALICE.accountID()))).thenReturn(account);

            // When the transaction is submitted, then the exception is thrown
            assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(UNAUTHORIZED));
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }
    }

    @Nested
    @DisplayName("5.b Check payer solvency")
    class PayerBalanceTests {

        public static Stream<Arguments> failureReasons() {
            return Stream.of(
                    Arguments.of(INSUFFICIENT_TX_FEE),
                    Arguments.of(INSUFFICIENT_PAYER_BALANCE),
                    Arguments.of(FAIL_FEE));
        }

        @ParameterizedTest(name = "Check of payer's balance fails with error code {0}")
        @MethodSource("failureReasons")
        @DisplayName("If the payer has insufficient funds, the transaction should be rejected")
        void payerAccountStatusFails(ResponseCodeEnum failureReason)
                throws PreCheckException, ExecutionException, InterruptedException, TimeoutException {
            givenValidPayerSignature();
            doThrow(new InsufficientBalanceException(failureReason, 123L))
                    .when(solvencyPreCheck)
                    .checkSolvency(any(), any(), any(), eq(INGEST));

            assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .has(responseCode(failureReason))
                    .has(estimatedFee(123L));
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }

        @Test
        @DisplayName("If some random exception is thrown from checking solvency, the exception is bubbled up")
        void randomException() throws PreCheckException, ExecutionException, InterruptedException, TimeoutException {
            // Given an IngestChecker that will throw a RuntimeException from checkPayerSignature
            givenValidPayerSignature();
            doThrow(new RuntimeException("checkSolvency exception"))
                    .when(solvencyPreCheck)
                    .checkSolvency(any(), any(), any(), eq(INGEST));

            // When the transaction is submitted, then the exception is bubbled up
            assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("checkSolvency exception");
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }

        private void givenValidPayerSignature() throws ExecutionException, InterruptedException, TimeoutException {
            final var verificationResultFuture = mock(SignatureVerificationFuture.class);
            final var verificationResult = mock(SignatureVerification.class);
            when(verificationResult.failed()).thenReturn(false);
            when(verificationResultFuture.get(anyLong(), any())).thenReturn(verificationResult);
            when(signatureVerifier.verify(any(), any()))
                    .thenReturn(Map.of(ALICE.account().keyOrThrow(), verificationResultFuture));
        }
    }

    @Nested
    @DisplayName("6. Check payer's signature")
    class PayerSignatureTests {

        @Test
        @DisplayName("Payer signature is missing")
        void noPayerSignature() {
            // If the signature verifier's returned map doesn't contain an entry for ALICE, it means she didn't have a
            // signature in the signature map to begin with.
            when(signatureVerifier.verify(any(), any())).thenReturn(Map.of());

            // When the transaction is submitted, then the exception is thrown
            assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_SIGNATURE));
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }

        @Test
        @DisplayName("Payer verification fails")
        void payerVerificationFails() throws Exception {
            final var verificationResultFuture = mock(SignatureVerificationFuture.class);
            final var verificationResult = mock(SignatureVerification.class);
            when(verificationResult.failed()).thenReturn(true);
            when(verificationResultFuture.get(anyLong(), any())).thenReturn(verificationResult);
            when(signatureVerifier.verify(any(), any()))
                    .thenReturn(Map.of(ALICE.account().keyOrThrow(), verificationResultFuture));

            assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_SIGNATURE));
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }

        @Test
        @DisplayName("Check payer with key-list successfully")
        void testKeyListVerificationSucceeds() throws Exception {
            // given
            final var accountID = AccountID.newBuilder().accountNum(42).build();
            final var key = Key.newBuilder()
                    .keyList(KeyList.newBuilder()
                            .keys(ALICE.account().key(), BOB.account().key()))
                    .build();
            final var account =
                    Account.newBuilder().accountId(accountID).key(key).build();
            final var myTxBody = txBody.copyBuilder()
                    .transactionID(txBody.transactionID()
                            .copyBuilder()
                            .accountID(accountID)
                            .build())
                    .build();
            final var myTx = tx.copyBuilder()
                    .signedTransactionBytes(asBytes(
                            SignedTransaction.PROTOBUF,
                            SignedTransaction.newBuilder()
                                    .bodyBytes(asBytes(TransactionBody.PROTOBUF, myTxBody))
                                    .build()))
                    .build();
            final var mySerializedTx = Transaction.PROTOBUF.toBytes(myTx);
            final var myTransactionInfo = new TransactionInfo(
                    myTx,
                    myTxBody,
                    MOCK_SIGNATURE_MAP,
                    myTx.signedTransactionBytes(),
                    UNCHECKED_SUBMIT,
                    mySerializedTx);
            when(transactionChecker.parseAndCheck(serializedTx, maxBytes)).thenReturn(myTransactionInfo);
            when(solvencyPreCheck.getPayerAccount(any(), eq(accountID))).thenReturn(account);
            final var verificationResultFutureAlice = mock(SignatureVerificationFuture.class);
            final var verificationResultAlice = mock(SignatureVerification.class);
            when(verificationResultAlice.failed()).thenReturn(false);
            when(verificationResultFutureAlice.get(anyLong(), any())).thenReturn(verificationResultAlice);
            final var verificationResultFutureBob = mock(SignatureVerificationFuture.class);
            final var verificationResultBob = mock(SignatureVerification.class);
            when(verificationResultBob.failed()).thenReturn(false);
            when(verificationResultFutureBob.get(anyLong(), any())).thenReturn(verificationResultBob);
            when(signatureVerifier.verify(any(), any()))
                    .thenReturn(Map.of(
                            ALICE.account().keyOrThrow(), verificationResultFutureAlice,
                            BOB.account().keyOrThrow(), verificationResultFutureBob));

            // when
            final var actual = subject.runAllChecks(state, serializedTx, configuration);

            // then
            assertThat(actual).isEqualTo(myTransactionInfo);
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }

        @Test
        @DisplayName("Check payer with key-list fails")
        void testKeyListVerificationFails() throws Exception {
            // given
            final var accountID = AccountID.newBuilder().accountNum(42).build();
            final var key = Key.newBuilder()
                    .keyList(KeyList.newBuilder()
                            .keys(ALICE.account().key(), BOB.account().key()))
                    .build();
            final var account =
                    Account.newBuilder().accountId(accountID).key(key).build();
            final var myTxBody = txBody.copyBuilder()
                    .transactionID(txBody.transactionID()
                            .copyBuilder()
                            .accountID(accountID)
                            .build())
                    .build();
            final var myTx = tx.copyBuilder()
                    .signedTransactionBytes(asBytes(
                            SignedTransaction.PROTOBUF,
                            SignedTransaction.newBuilder()
                                    .bodyBytes(asBytes(TransactionBody.PROTOBUF, myTxBody))
                                    .build()))
                    .build();
            final var mySerializedTx = Transaction.PROTOBUF.toBytes(myTx);
            final var myTransactionInfo = new TransactionInfo(
                    myTx,
                    myTxBody,
                    MOCK_SIGNATURE_MAP,
                    myTx.signedTransactionBytes(),
                    UNCHECKED_SUBMIT,
                    mySerializedTx);
            when(transactionChecker.parseAndCheck(mySerializedTx, maxBytes)).thenReturn(myTransactionInfo);
            when(solvencyPreCheck.getPayerAccount(any(), eq(accountID))).thenReturn(account);
            final var verificationResultFutureAlice = mock(SignatureVerificationFuture.class);
            final var verificationResultAlice = mock(SignatureVerification.class);
            when(verificationResultAlice.failed()).thenReturn(false);
            when(verificationResultFutureAlice.get(anyLong(), any())).thenReturn(verificationResultAlice);
            final var verificationResultFutureBob = mock(SignatureVerificationFuture.class);
            final var verificationResultBob = mock(SignatureVerification.class);
            when(verificationResultBob.failed()).thenReturn(true);
            when(verificationResultFutureBob.get(anyLong(), any())).thenReturn(verificationResultBob);
            when(signatureVerifier.verify(any(), any()))
                    .thenReturn(Map.of(
                            ALICE.account().keyOrThrow(), verificationResultFutureAlice,
                            BOB.account().keyOrThrow(), verificationResultFutureBob));

            // when
            assertThatThrownBy(() -> subject.runAllChecks(state, mySerializedTx, configuration))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_SIGNATURE));
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }

        @Test
        @DisplayName("Check payer with threshold key successfully")
        void testThresholdKeyVerificationSucceeds() throws Exception {
            // given
            final var accountID = AccountID.newBuilder().accountNum(42).build();
            final var key = Key.newBuilder()
                    .thresholdKey(ThresholdKey.newBuilder()
                            .keys(KeyList.newBuilder()
                                    .keys(ALICE.account().key(), BOB.account().key()))
                            .threshold(1))
                    .build();
            final var account =
                    Account.newBuilder().accountId(accountID).key(key).build();
            final var myTxBody = txBody.copyBuilder()
                    .transactionID(txBody.transactionID()
                            .copyBuilder()
                            .accountID(accountID)
                            .build())
                    .build();
            final var myTx = tx.copyBuilder()
                    .signedTransactionBytes(asBytes(
                            SignedTransaction.PROTOBUF,
                            SignedTransaction.newBuilder()
                                    .bodyBytes(asBytes(TransactionBody.PROTOBUF, myTxBody))
                                    .build()))
                    .build();
            final var mySerializedTx = Transaction.PROTOBUF.toBytes(myTx);
            final var myTransactionInfo = new TransactionInfo(
                    myTx,
                    myTxBody,
                    MOCK_SIGNATURE_MAP,
                    myTx.signedTransactionBytes(),
                    UNCHECKED_SUBMIT,
                    mySerializedTx);
            when(transactionChecker.parseAndCheck(mySerializedTx, maxBytes)).thenReturn(myTransactionInfo);
            when(solvencyPreCheck.getPayerAccount(any(), eq(accountID))).thenReturn(account);
            final var verificationResultFutureAlice = mock(SignatureVerificationFuture.class);
            final var verificationResultAlice = mock(SignatureVerification.class);
            when(verificationResultAlice.failed()).thenReturn(false);
            when(verificationResultFutureAlice.get(anyLong(), any())).thenReturn(verificationResultAlice);
            final var verificationResultFutureBob = mock(SignatureVerificationFuture.class);
            final var verificationResultBob = mock(SignatureVerification.class);
            when(verificationResultBob.failed()).thenReturn(true);
            when(verificationResultFutureBob.get(anyLong(), any())).thenReturn(verificationResultBob);
            when(signatureVerifier.verify(any(), any()))
                    .thenReturn(Map.of(
                            ALICE.account().keyOrThrow(), verificationResultFutureAlice,
                            BOB.account().keyOrThrow(), verificationResultFutureBob));

            // when
            final var actual = subject.runAllChecks(state, mySerializedTx, configuration);

            // then
            assertThat(actual).isEqualTo(myTransactionInfo);
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }

        @Test
        @DisplayName("Check payer with threshold key fails")
        void testThresholdKeyVerificationFails() throws Exception {
            // given
            final var accountID = AccountID.newBuilder().accountNum(42).build();
            final var key = Key.newBuilder()
                    .thresholdKey(ThresholdKey.newBuilder()
                            .keys(KeyList.newBuilder()
                                    .keys(ALICE.account().key(), BOB.account().key()))
                            .threshold(1))
                    .build();
            final var account =
                    Account.newBuilder().accountId(accountID).key(key).build();
            final var myTxBody = txBody.copyBuilder()
                    .transactionID(txBody.transactionID()
                            .copyBuilder()
                            .accountID(accountID)
                            .build())
                    .build();
            final var myTx = tx.copyBuilder()
                    .signedTransactionBytes(asBytes(
                            SignedTransaction.PROTOBUF,
                            SignedTransaction.newBuilder()
                                    .bodyBytes(asBytes(TransactionBody.PROTOBUF, myTxBody))
                                    .build()))
                    .build();
            final var mySerializedTx = Transaction.PROTOBUF.toBytes(myTx);
            final var myTransactionInfo = new TransactionInfo(
                    myTx,
                    myTxBody,
                    MOCK_SIGNATURE_MAP,
                    myTx.signedTransactionBytes(),
                    UNCHECKED_SUBMIT,
                    mySerializedTx);
            when(transactionChecker.parseAndCheck(mySerializedTx, maxBytes)).thenReturn(myTransactionInfo);
            when(solvencyPreCheck.getPayerAccount(any(), eq(accountID))).thenReturn(account);
            final var verificationResultFutureAlice = mock(SignatureVerificationFuture.class);
            final var verificationResultAlice = mock(SignatureVerification.class);
            when(verificationResultAlice.failed()).thenReturn(true);
            when(verificationResultFutureAlice.get(anyLong(), any())).thenReturn(verificationResultAlice);
            final var verificationResultFutureBob = mock(SignatureVerificationFuture.class);
            final var verificationResultBob = mock(SignatureVerification.class);
            when(verificationResultBob.failed()).thenReturn(true);
            when(verificationResultFutureBob.get(anyLong(), any())).thenReturn(verificationResultBob);
            when(signatureVerifier.verify(any(), any()))
                    .thenReturn(Map.of(
                            ALICE.account().keyOrThrow(), verificationResultFutureAlice,
                            BOB.account().keyOrThrow(), verificationResultFutureBob));

            // when
            assertThatThrownBy(() -> subject.runAllChecks(state, mySerializedTx, configuration))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_SIGNATURE));
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }

        @Test
        @DisplayName("Unexpected verification exception")
        void randomException() throws Exception {
            // Given a verification result future that throws an exception
            final var verificationResultFuture = mock(SignatureVerificationFuture.class);
            doThrow(new RuntimeException("checkPayerSignature exception"))
                    .when(verificationResultFuture)
                    .get(anyLong(), any());
            when(signatureVerifier.verify(any(), any()))
                    .thenReturn(Map.of(ALICE.account().keyOrThrow(), verificationResultFuture));

            // When the transaction is submitted, then the exception is bubbled up
            assertThatThrownBy(() -> subject.runAllChecks(state, serializedTx, configuration))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("checkPayerSignature exception");
            verify(opWorkflowMetrics, never()).incrementThrottled(any());
        }
    }
}

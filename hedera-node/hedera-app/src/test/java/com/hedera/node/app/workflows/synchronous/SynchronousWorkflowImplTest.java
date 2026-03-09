// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.synchronous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.quiescence.TxPipelineTracker;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.app.spi.workflows.InsufficientBalanceException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.throttle.ThrottleUsage;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.ingest.IngestChecker;
import com.hedera.node.app.workflows.ingest.SubmissionManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SynchronousWorkflowImplTest extends AppTestBase {

    private Bytes requestBuffer;
    private final BufferedData responseBuffer = BufferedData.allocate(1024 * 6);

    @Mock(strictness = LENIENT)
    State state;

    @Mock(strictness = LENIENT)
    Supplier<AutoCloseableWrapper<State>> stateAccessor;

    @Mock(strictness = LENIENT)
    IngestChecker ingestChecker;

    @Mock
    TxPipelineTracker txPipelineTracker;

    @Mock(strictness = LENIENT)
    SubmissionManager submissionManager;

    @Mock(strictness = LENIENT)
    ConfigProvider configProvider;

    @Mock(strictness = LENIENT)
    PendingFutureRegistry pendingFutureRegistry;

    @Mock(strictness = LENIENT)
    RecordSource recordSource;

    private VersionedConfiguration configuration;
    private SynchronousWorkflowImpl workflow;
    private TransactionID txnId;
    private TransactionBody transactionBody;

    @BeforeEach
    void setup() throws PreCheckException {
        requestBuffer = randomBytes(10);
        txnId = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder().accountNum(1001).build())
                .build();
        transactionBody = TransactionBody.newBuilder().transactionID(txnId).build();

        configuration = new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1L);
        when(configProvider.getConfiguration()).thenReturn(configuration);

        when(stateAccessor.get()).thenReturn(new AutoCloseableWrapper<>(state, () -> {}));

        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(transactionBody))
                .build();
        final var transactionInfo = new TransactionInfo(
                signedTx,
                transactionBody,
                SignatureMap.newBuilder().build(),
                randomBytes(100),
                HederaFunctionality.CONSENSUS_CREATE_TOPIC,
                SignedTransaction.PROTOBUF.toBytes(signedTx));
        doAnswer(invocationOnMock -> {
                    final var result = invocationOnMock.getArgument(3, IngestChecker.Result.class);
                    result.setThrottleUsages(List.of());
                    result.setTxnInfo(transactionInfo);
                    return null;
                })
                .when(ingestChecker)
                .runAllChecks(eq(state), eq(requestBuffer), any(), any());

        // Record source returns a non-null record by default (happy path)
        final var mockRecord =
                TransactionRecord.newBuilder().transactionID(txnId).build();
        doAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<TransactionRecord> consumer = invocation.getArgument(0);
                    consumer.accept(mockRecord);
                    return null;
                })
                .when(recordSource)
                .forEachTxnRecord(any());

        // pendingFutureRegistry.register() returns a pre-completed future (happy path)
        final CompletableFuture<RecordSource> future = CompletableFuture.completedFuture(recordSource);
        when(pendingFutureRegistry.register(any())).thenReturn(future);

        workflow = new SynchronousWorkflowImpl(
                stateAccessor,
                ingestChecker,
                submissionManager,
                txPipelineTracker,
                configProvider,
                pendingFutureRegistry);
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("Record is written to the response buffer")
        void recordWrittenToResponseBuffer() throws Exception {
            workflow.submitAndWait(requestBuffer, responseBuffer);

            assertThat(responseBuffer.position()).isGreaterThan(0);
        }

        @Test
        @DisplayName("pendingFutureRegistry.register() is called before submit()")
        void registerCalledBeforeSubmit() throws Exception {
            workflow.submitAndWait(requestBuffer, responseBuffer);

            final var inOrderVerifier = inOrder(pendingFutureRegistry, submissionManager);
            inOrderVerifier.verify(pendingFutureRegistry).register(txnId);
            inOrderVerifier.verify(submissionManager).submit(any(), any(), eq(false));
        }
    }

    @Nested
    @DisplayName("IngestChecker pre-check failures")
    class PreCheckFailures {

        @Test
        @DisplayName("verifyReadyForTransactions() failure → FAILED_PRECONDITION, register() never called")
        void verifyReadyThrows() throws PreCheckException {
            doThrow(new PreCheckException(ResponseCodeEnum.PLATFORM_NOT_ACTIVE))
                    .when(ingestChecker)
                    .verifyReadyForTransactions();

            assertThatThrownBy(() -> workflow.submitAndWait(requestBuffer, responseBuffer))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION);

            verify(pendingFutureRegistry, never()).register(any());
        }

        @Test
        @DisplayName("runAllChecks() throws PreCheckException → FAILED_PRECONDITION, register() never called")
        void runAllChecksFails() throws PreCheckException {
            doAnswer(invocationOnMock -> {
                        throw new PreCheckException(ResponseCodeEnum.INVALID_TRANSACTION);
                    })
                    .when(ingestChecker)
                    .runAllChecks(any(), any(), any(), any());

            assertThatThrownBy(() -> workflow.submitAndWait(requestBuffer, responseBuffer))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION);

            verify(pendingFutureRegistry, never()).register(any());
        }
    }

    @Nested
    @DisplayName("InsufficientBalance failure")
    class InsufficientBalance {

        @Test
        @DisplayName("InsufficientBalanceException reclaims throttle capacity and throws FAILED_PRECONDITION")
        void insufficientBalanceFails() throws PreCheckException {
            final var throttleUsage = mock(ThrottleUsage.class);
            doAnswer(invocationOnMock -> {
                        final var result = invocationOnMock.getArgument(3, IngestChecker.Result.class);
                        result.setThrottleUsages(List.of(throttleUsage));
                        throw new InsufficientBalanceException(ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE, 0L);
                    })
                    .when(ingestChecker)
                    .runAllChecks(any(), any(), any(), any());

            assertThatThrownBy(() -> workflow.submitAndWait(requestBuffer, responseBuffer))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION);

            verify(throttleUsage).reclaimCapacity();
        }
    }

    @Nested
    @DisplayName("Submission failures")
    class SubmissionFailures {

        @Test
        @DisplayName("When submit() throws, pendingFutureRegistry.fail() is called")
        void submitFails() throws PreCheckException {
            final var submitEx = new RuntimeException("submit failed");
            doThrow(submitEx).when(submissionManager).submit(any(), any(), eq(false));

            assertThatThrownBy(() -> workflow.submitAndWait(requestBuffer, responseBuffer))
                    .isInstanceOf(StatusRuntimeException.class);

            verify(pendingFutureRegistry).fail(eq(txnId), eq(submitEx));
        }
    }

    @Nested
    @DisplayName("Future completion failures")
    class FutureFailures {

        @Test
        @DisplayName("TimeoutException → DEADLINE_EXCEEDED, fail() called with TimeoutException")
        void timeoutException() throws Exception {
            @SuppressWarnings("unchecked")
            final CompletableFuture<RecordSource> mockFuture = mock(CompletableFuture.class);
            when(pendingFutureRegistry.register(any())).thenReturn(mockFuture);
            when(mockFuture.get(anyLong(), any())).thenThrow(new TimeoutException());

            assertThatThrownBy(() -> workflow.submitAndWait(requestBuffer, responseBuffer))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED);

            verify(pendingFutureRegistry).fail(eq(txnId), any(TimeoutException.class));
        }

        @Test
        @DisplayName("InterruptedException → INTERNAL, fail() called with InterruptedException")
        void interruptedException() throws Exception {
            @SuppressWarnings("unchecked")
            final CompletableFuture<RecordSource> mockFuture = mock(CompletableFuture.class);
            when(pendingFutureRegistry.register(any())).thenReturn(mockFuture);
            when(mockFuture.get(anyLong(), any())).thenThrow(new InterruptedException());

            assertThatThrownBy(() -> workflow.submitAndWait(requestBuffer, responseBuffer))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.INTERNAL);

            verify(pendingFutureRegistry).fail(eq(txnId), any(InterruptedException.class));
        }

        @Test
        @DisplayName("ExecutionException → INTERNAL with cause")
        void executionException() throws Exception {
            @SuppressWarnings("unchecked")
            final CompletableFuture<RecordSource> mockFuture = mock(CompletableFuture.class);
            when(pendingFutureRegistry.register(any())).thenReturn(mockFuture);
            final var cause = new RuntimeException("catastrophic");
            when(mockFuture.get(anyLong(), any())).thenThrow(new ExecutionException(cause));

            assertThatThrownBy(() -> workflow.submitAndWait(requestBuffer, responseBuffer))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.INTERNAL);
        }

        @Test
        @DisplayName("No record in RecordSource → INTERNAL with 'No transaction record produced'")
        void noRecordInRecordSource() {
            // Override forEachTxnRecord to NOT invoke the consumer — simulates empty record source
            doAnswer(invocation -> null).when(recordSource).forEachTxnRecord(any());

            assertThatThrownBy(() -> workflow.submitAndWait(requestBuffer, responseBuffer))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.INTERNAL);
        }
    }

    @Nested
    @DisplayName("Quiescence tracking")
    class QuiescenceTracking {

        private SynchronousWorkflowImpl quiescenceWorkflow;

        @BeforeEach
        void enableQuiescence() {
            final var quiescenceConfig = new VersionedConfigImpl(
                    HederaTestConfigBuilder.create()
                            .withValue("quiescence.enabled", true)
                            .getOrCreateConfig(),
                    1L);
            when(configProvider.getConfiguration()).thenReturn(quiescenceConfig);

            quiescenceWorkflow = new SynchronousWorkflowImpl(
                    stateAccessor,
                    ingestChecker,
                    submissionManager,
                    txPipelineTracker,
                    configProvider,
                    pendingFutureRegistry);
        }

        @Test
        @DisplayName("On happy path: incrementPreFlight(), incrementInFlight(), and decrementPreFlight() are called")
        void quiescenceTrackingOnSuccess() throws Exception {
            quiescenceWorkflow.submitAndWait(requestBuffer, responseBuffer);

            verify(txPipelineTracker).incrementPreFlight();
            verify(txPipelineTracker).incrementInFlight();
            verify(txPipelineTracker).decrementPreFlight();
        }

        @Test
        @DisplayName("On pre-check failure: incrementPreFlight() and decrementPreFlight() still called")
        void quiescenceDecrementCalledEvenOnFailure() throws PreCheckException {
            doThrow(new PreCheckException(ResponseCodeEnum.PLATFORM_NOT_ACTIVE))
                    .when(ingestChecker)
                    .verifyReadyForTransactions();

            assertThatThrownBy(() -> quiescenceWorkflow.submitAndWait(requestBuffer, responseBuffer))
                    .isInstanceOf(StatusRuntimeException.class);

            verify(txPipelineTracker).incrementPreFlight();
            verify(txPipelineTracker).decrementPreFlight();
        }
    }
}

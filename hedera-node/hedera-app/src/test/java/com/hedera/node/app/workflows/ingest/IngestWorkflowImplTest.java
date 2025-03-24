// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BUSY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PLATFORM_NOT_ACTIVE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PLATFORM_TRANSACTION_NOT_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_HAS_UNKNOWN_FIELDS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
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
class IngestWorkflowImplTest extends AppTestBase {
    /** The workflow to be tested. */
    private IngestWorkflowImpl workflow;

    /**
     * The request. For testing purposes, the bytes in this buffer are not important. The {@link TransactionChecker} is
     * stubbed to always return a valid parsed object.
     */
    private Bytes requestBuffer;

    /** The buffer to write responses into. */
    private final BufferedData responseBuffer = BufferedData.allocate(1024 * 6);

    /** The request transaction */
    private Transaction transaction;

    /** The request transaction body */
    private TransactionBody transactionBody;

    // The following fields are all mocked dependencies of the workflow.
    @Mock(strictness = LENIENT)
    State state;

    @Mock(strictness = LENIENT)
    Supplier<AutoCloseableWrapper<State>> stateAccessor;

    @Mock(strictness = LENIENT)
    IngestChecker ingestChecker;

    @Mock(strictness = LENIENT)
    SubmissionManager submissionManager;

    @Mock(strictness = LENIENT)
    private ConfigProvider configProvider;

    private VersionedConfiguration configuration;

    @BeforeEach
    void setup() throws PreCheckException {
        // The request buffer, with basically random bytes
        requestBuffer = randomBytes(10);
        transactionBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(1001).build())
                        .build())
                .build();

        configuration = new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1L);
        when(configProvider.getConfiguration()).thenReturn(configuration);

        // The account will have the following state
        // TODO Anything here??

        // The state is going to always be empty
        when(stateAccessor.get()).thenReturn(new AutoCloseableWrapper<>(state, () -> {}));
        // TODO Mock out the metrics to return objects we can inspect later

        // Mock out the onset to always return a valid parsed object
        transaction = Transaction.newBuilder().body(transactionBody).build();
        final var transactionInfo = new TransactionInfo(
                transaction,
                transactionBody,
                SignatureMap.newBuilder().build(),
                randomBytes(100), // Not used in this test, so random bytes is OK
                HederaFunctionality.CONSENSUS_CREATE_TOPIC,
                requestBuffer);
        when(ingestChecker.runAllChecks(state, requestBuffer, configuration)).thenReturn(transactionInfo);

        // Create the workflow we are going to test with
        workflow = new IngestWorkflowImpl(stateAccessor, ingestChecker, submissionManager, configProvider);
    }

    @Test
    @DisplayName("When everything goes right, the transaction should be submitted")
    void testSuccess() throws PreCheckException, ParseException {
        // When the transaction is submitted
        workflow.submitTransaction(requestBuffer, responseBuffer);

        // Then we get a response that is OK
        final TransactionResponse response = parseResponse(responseBuffer);
        assertThat(response.nodeTransactionPrecheckCode()).isEqualTo(OK);
        // The cost *MUST* be zero, it is only non-zero for insufficient balance errors
        assertThat(response.cost()).isZero();
        // And that the transaction and its bytes were actually passed to the submission manager
        verify(submissionManager).submit(transactionBody, requestBuffer);
    }

    @Nested
    @DisplayName("0. Node state pre-checks")
    class NodeTests {
        @ParameterizedTest
        @EnumSource(PlatformStatus.class)
        @DisplayName("When the platform is not ACTIVE, the transaction should be rejected (except for ACTIVE)")
        void testParseAndCheckWithInactivePlatformFails(final PlatformStatus status)
                throws ParseException, PreCheckException {
            // Since the enum source is going over all states, and the ACTIVE state is
            // actually good, I need to skip that one.
            if (status != PlatformStatus.ACTIVE) {
                // Given a platform that is not ACTIVE
                doThrow(new PreCheckException(PLATFORM_NOT_ACTIVE))
                        .when(ingestChecker)
                        .verifyReadyForTransactions();

                // When the transaction is submitted
                workflow.submitTransaction(requestBuffer, responseBuffer);

                // Then the response fails with PLATFORM_NOT_ACTIVE
                final TransactionResponse response = parseResponse(responseBuffer);
                assertThat(response.nodeTransactionPrecheckCode()).isEqualTo(PLATFORM_NOT_ACTIVE);
                // The cost *MUST* be zero, it is only non-zero for insufficient balance errors
                assertThat(response.cost()).isZero();
                // And the transaction is not submitted to the platform
                verify(submissionManager, never()).submit(any(), any());
            }
        }
    }

    @Nested
    @DisplayName("1. Parse the TransactionBody")
    class OnsetTests {
        /**
         * It is not necessary to test all the possible failure reasons, just a few to make sure that
         * the workflow is passing the failure reason to the response.
         * @return a stream of arguments with the failure reason
         */
        public static Stream<Arguments> failureReasons() {
            return Stream.of(
                    Arguments.of(INVALID_TRANSACTION),
                    Arguments.of(INVALID_TRANSACTION_BODY),
                    Arguments.of(TRANSACTION_HAS_UNKNOWN_FIELDS),
                    Arguments.of(TRANSACTION_OVERSIZE));
        }

        @ParameterizedTest(name = "WorkflowOnset fails with error code {0}")
        @MethodSource("failureReasons")
        @DisplayName("If the transaction fails WorkflowOnset, a failure response is returned with the right error")
        void onsetFailsWithPreCheckException(ResponseCodeEnum failureReason) throws PreCheckException, ParseException {
            // Given a WorkflowOnset that will throw a PreCheckException with the given failure reason
            when(ingestChecker.runAllChecks(any(), any(), any())).thenThrow(new PreCheckException(failureReason));

            // When the transaction is submitted
            workflow.submitTransaction(requestBuffer, responseBuffer);

            // Then the response fails with the given failure reason
            final TransactionResponse response = parseResponse(responseBuffer);
            assertThat(response.nodeTransactionPrecheckCode()).isEqualTo(failureReason);
            // The cost *MUST* be zero, it is only non-zero for insufficient balance errors
            assertThat(response.cost()).isZero();
            // And the transaction is not submitted to the platform
            verify(submissionManager, never()).submit(any(), any());
        }

        @Test
        @DisplayName("If some random exception is thrown from IngestChecker, the exception is bubbled up")
        void randomException() throws PreCheckException, ParseException {
            // Given a WorkflowOnset that will throw a RuntimeException
            when(ingestChecker.runAllChecks(any(), any(), any()))
                    .thenThrow(new RuntimeException("parseAndCheck exception"));

            // When the transaction is submitted
            workflow.submitTransaction(requestBuffer, responseBuffer);

            // Then the response will indicate the platform rejected the transaction
            final TransactionResponse response = parseResponse(responseBuffer);
            assertThat(response.nodeTransactionPrecheckCode()).isEqualTo(FAIL_INVALID);
            // And the cost will be zero
            assertThat(response.cost()).isZero();
            // And the transaction is not submitted to the platform
            verify(submissionManager, never()).submit(any(), any());
        }
    }

    @Nested
    @DisplayName("2.-6. Check the transaction")
    class IngestCheckerTests {
        public static Stream<Arguments> failureReasons() {
            return Stream.of(
                    Arguments.of(INVALID_TRANSACTION),
                    Arguments.of(INVALID_TRANSACTION_BODY),
                    Arguments.of(BUSY),
                    Arguments.of(INVALID_SIGNATURE));
        }

        @ParameterizedTest(name = "IngestChecker fails with error code {0}")
        @MethodSource("failureReasons")
        @DisplayName("When ingest checks fail, the transaction should be rejected")
        void testIngestChecksFail(ResponseCodeEnum failureReason) throws PreCheckException, ParseException {
            // Given a throttle on CONSENSUS_CREATE_TOPIC transactions (i.e. it is time to throttle)
            when(ingestChecker.runAllChecks(state, requestBuffer, configuration))
                    .thenThrow(new PreCheckException(failureReason));

            // When the transaction is submitted
            workflow.submitTransaction(requestBuffer, responseBuffer);

            // Then the response fails with BUSY
            final TransactionResponse response = parseResponse(responseBuffer);
            assertThat(response.nodeTransactionPrecheckCode()).isEqualTo(failureReason);
            // The cost *MUST* be zero, it is only non-zero for insufficient balance errors
            assertThat(response.cost()).isZero();
            // And the transaction is not submitted to the platform
            verify(submissionManager, never()).submit(any(), any());
        }

        @Test
        @DisplayName("If some random exception is thrown from IngestChecker, the exception is bubbled up")
        void randomException() throws PreCheckException, ParseException {
            // Given a ThrottleAccumulator that will throw a RuntimeException
            when(ingestChecker.runAllChecks(state, requestBuffer, configuration))
                    .thenThrow(new RuntimeException("runAllChecks exception"));

            // When the transaction is submitted
            workflow.submitTransaction(requestBuffer, responseBuffer);

            // Then the response will indicate the platform rejected the transaction
            final TransactionResponse response = parseResponse(responseBuffer);
            assertThat(response.nodeTransactionPrecheckCode()).isEqualTo(FAIL_INVALID);
            // And the cost will be zero
            assertThat(response.cost()).isZero();
            // And the transaction is not submitted to the platform
            verify(submissionManager, never()).submit(any(), any());
        }
    }

    @Nested
    @DisplayName("7. Submit to platform")
    class PlatformSubmissionTests {

        @Test
        @DisplayName("If the platform fails to onConsensusRound the transaction, the transaction should be rejected")
        void testSubmitFails() throws PreCheckException, ParseException {
            // Given a SubmissionManager that will fail the submit
            doThrow(new PreCheckException(PLATFORM_TRANSACTION_NOT_CREATED))
                    .when(submissionManager)
                    .submit(any(), any());

            // When we submit a transaction
            workflow.submitTransaction(requestBuffer, responseBuffer);

            // Then the response will indicate the platform rejected the transaction
            final TransactionResponse response = parseResponse(responseBuffer);
            assertThat(response.nodeTransactionPrecheckCode()).isEqualTo(PLATFORM_TRANSACTION_NOT_CREATED);
            // And the cost will be zero
            assertThat(response.cost()).isZero();
        }

        @Test
        @DisplayName("If some random exception is thrown from submitting to the platform, the exception is bubbled up")
        void randomException() throws PreCheckException, ParseException {
            // Given a SubmissionManager that will throw a RuntimeException from submit
            doThrow(new RuntimeException("submit exception"))
                    .when(submissionManager)
                    .submit(any(), any());

            // When the transaction is submitted
            workflow.submitTransaction(requestBuffer, responseBuffer);

            // Then the response will indicate the platform rejected the transaction
            final TransactionResponse response = parseResponse(responseBuffer);
            assertThat(response.nodeTransactionPrecheckCode()).isEqualTo(FAIL_INVALID);
            // And the cost will be zero
            assertThat(response.cost()).isZero();
        }
    }

    private static TransactionResponse parseResponse(@NonNull final BufferedData responseBuffer) throws ParseException {
        responseBuffer.flip();
        return TransactionResponse.PROTOBUF.parse(responseBuffer);
    }
}

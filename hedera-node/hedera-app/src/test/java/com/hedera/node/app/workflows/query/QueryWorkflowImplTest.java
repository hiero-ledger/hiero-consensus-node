// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.query;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_GET_TOPIC_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.NETWORK_GET_EXECUTION_TIME;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BUSY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PLATFORM_TRANSACTION_NOT_CREATED;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusGetTopicInfoQuery;
import com.hedera.hapi.node.consensus.ConsensusGetTopicInfoResponse;
import com.hedera.hapi.node.file.FileGetInfoQuery;
import com.hedera.hapi.node.file.FileGetInfoResponse;
import com.hedera.hapi.node.network.NetworkGetExecutionTimeQuery;
import com.hedera.hapi.node.network.NetworkGetExecutionTimeResponse;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusGetTopicInfoHandler;
import com.hedera.node.app.service.file.impl.handlers.FileGetInfoHandler;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkGetExecutionTimeHandler;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fees.SimpleFeeCalculator;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.workflows.InsufficientBalanceException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.app.throttle.SynchronizedThrottleAccumulator;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.ingest.IngestChecker;
import com.hedera.node.app.workflows.ingest.SubmissionManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.UnknownFieldException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.InstantSource;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.hiero.hapi.fees.FeeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
class QueryWorkflowImplTest extends AppTestBase {

    private static final int BUFFER_SIZE = 1024 * 6;
    private static final long DEFAULT_CONFIG_VERSION = 1L;

    @Mock(strictness = LENIENT)
    private Function<ResponseType, AutoCloseableWrapper<State>> stateAccessor;

    @Mock
    private SubmissionManager submissionManager;

    @Mock(strictness = LENIENT)
    private QueryChecker queryChecker;

    @Mock(strictness = LENIENT)
    private IngestChecker ingestChecker;

    private final InstantSource instantSource = InstantSource.system();

    @Mock(strictness = LENIENT)
    FileGetInfoHandler handler;

    @Mock(strictness = LENIENT)
    private QueryDispatcher dispatcher;

    @Mock(strictness = LENIENT)
    private Codec<Query> queryParser;

    @Mock(strictness = LENIENT)
    private ConfigProvider configProvider;

    @Mock(strictness = LENIENT)
    private RecordCache recordCache;

    @Mock
    private Authorizer authorizer;

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock(strictness = LENIENT)
    private FeeManager feeManager;

    @Mock(strictness = LENIENT)
    private SynchronizedThrottleAccumulator synchronizedThrottleAccumulator;

    @Mock
    private OpWorkflowMetrics opWorkflowMetrics;

    private VersionedConfiguration configuration;
    private Bytes serializedPayment;
    private TransactionBody txBody;
    private Bytes requestBuffer;
    private TransactionInfo transactionInfo;

    private QueryWorkflowImpl workflow;

    @BeforeEach
    void setup(@Mock FeeCalculator feeCalculator) throws ParseException, PreCheckException {
        setupStandardStates();

        when(stateAccessor.apply(any())).thenReturn(new AutoCloseableWrapper<>(state, () -> {}));
        final var transactionID =
                TransactionID.newBuilder().accountID(ALICE.accountID()).build();
        txBody = TransactionBody.newBuilder().transactionID(transactionID).build();
        final var payment = Transaction.newBuilder().body(txBody).build();
        final var signedPayment = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(txBody))
                .build();
        serializedPayment = Transaction.PROTOBUF.toBytes(payment);
        final var queryHeader = QueryHeader.newBuilder().payment(payment).build();
        final var query = Query.newBuilder()
                .fileGetInfo(FileGetInfoQuery.newBuilder().header(queryHeader))
                .build();
        requestBuffer = Query.PROTOBUF.toBytes(query);
        when(queryParser.parseStrict((ReadableSequentialData) notNull())).thenReturn(query);

        configuration = new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), DEFAULT_CONFIG_VERSION);
        when(configProvider.getConfiguration()).thenReturn(configuration);

        when(feeManager.createFeeCalculator(eq(FILE_GET_INFO), any(), any())).thenReturn(feeCalculator);

        final var signatureMap = SignatureMap.newBuilder().build();
        transactionInfo = new TransactionInfo(
                signedPayment, txBody, signatureMap, signedPayment.bodyBytes(), CRYPTO_TRANSFER, serializedPayment);
        doAnswer(invocationOnMock -> {
                    final var result = invocationOnMock.getArgument(3, IngestChecker.Result.class);
                    result.setThrottleUsages(List.of());
                    result.setTxnInfo(transactionInfo);
                    return null;
                })
                .when(ingestChecker)
                .runAllChecks(eq(state), eq(requestBuffer), eq(configuration), any());

        when(handler.extractHeader(query)).thenReturn(queryHeader);
        when(handler.createEmptyResponse(any())).thenAnswer((Answer<Response>) invocation -> {
            final var header = (ResponseHeader) invocation.getArguments()[0];
            return Response.newBuilder()
                    .fileGetInfo(FileGetInfoResponse.newBuilder().header(header).build())
                    .build();
        });

        final var responseHeader = ResponseHeader.newBuilder()
                .responseType(ANSWER_ONLY)
                .nodeTransactionPrecheckCode(OK)
                .build();
        final var fileGetInfo =
                FileGetInfoResponse.newBuilder().header(responseHeader).build();
        final var response = Response.newBuilder().fileGetInfo(fileGetInfo).build();

        when(dispatcher.getHandler(query)).thenReturn(handler);
        when(handler.findResponse(any(), eq(responseHeader))).thenReturn(response);

        workflow = new QueryWorkflowImpl(
                stateAccessor,
                submissionManager,
                queryChecker,
                ingestChecker,
                dispatcher,
                queryParser,
                configProvider,
                recordCache,
                authorizer,
                exchangeRateManager,
                feeManager,
                synchronizedThrottleAccumulator,
                instantSource,
                opWorkflowMetrics,
                true);
    }

    @AfterEach
    void tearDown() {
        state.release();
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testConstructorWithIllegalParameters() {
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        null,
                        submissionManager,
                        queryChecker,
                        ingestChecker,
                        dispatcher,
                        queryParser,
                        configProvider,
                        recordCache,
                        authorizer,
                        exchangeRateManager,
                        feeManager,
                        synchronizedThrottleAccumulator,
                        instantSource,
                        opWorkflowMetrics,
                        true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        stateAccessor,
                        null,
                        queryChecker,
                        ingestChecker,
                        dispatcher,
                        queryParser,
                        configProvider,
                        recordCache,
                        authorizer,
                        exchangeRateManager,
                        feeManager,
                        synchronizedThrottleAccumulator,
                        instantSource,
                        opWorkflowMetrics,
                        true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        stateAccessor,
                        submissionManager,
                        null,
                        ingestChecker,
                        dispatcher,
                        queryParser,
                        configProvider,
                        recordCache,
                        authorizer,
                        exchangeRateManager,
                        feeManager,
                        synchronizedThrottleAccumulator,
                        instantSource,
                        opWorkflowMetrics,
                        true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        stateAccessor,
                        submissionManager,
                        queryChecker,
                        null,
                        dispatcher,
                        queryParser,
                        configProvider,
                        recordCache,
                        authorizer,
                        exchangeRateManager,
                        feeManager,
                        synchronizedThrottleAccumulator,
                        instantSource,
                        opWorkflowMetrics,
                        true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        stateAccessor,
                        submissionManager,
                        queryChecker,
                        ingestChecker,
                        null,
                        queryParser,
                        configProvider,
                        recordCache,
                        authorizer,
                        exchangeRateManager,
                        feeManager,
                        synchronizedThrottleAccumulator,
                        instantSource,
                        opWorkflowMetrics,
                        true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        stateAccessor,
                        submissionManager,
                        queryChecker,
                        ingestChecker,
                        dispatcher,
                        null,
                        configProvider,
                        recordCache,
                        authorizer,
                        exchangeRateManager,
                        feeManager,
                        synchronizedThrottleAccumulator,
                        instantSource,
                        opWorkflowMetrics,
                        true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        stateAccessor,
                        submissionManager,
                        queryChecker,
                        ingestChecker,
                        dispatcher,
                        queryParser,
                        null,
                        recordCache,
                        authorizer,
                        exchangeRateManager,
                        feeManager,
                        synchronizedThrottleAccumulator,
                        instantSource,
                        opWorkflowMetrics,
                        true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        stateAccessor,
                        submissionManager,
                        queryChecker,
                        ingestChecker,
                        dispatcher,
                        queryParser,
                        configProvider,
                        null,
                        authorizer,
                        exchangeRateManager,
                        feeManager,
                        synchronizedThrottleAccumulator,
                        instantSource,
                        opWorkflowMetrics,
                        true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        stateAccessor,
                        submissionManager,
                        queryChecker,
                        ingestChecker,
                        dispatcher,
                        queryParser,
                        configProvider,
                        recordCache,
                        null,
                        exchangeRateManager,
                        feeManager,
                        synchronizedThrottleAccumulator,
                        instantSource,
                        opWorkflowMetrics,
                        true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        stateAccessor,
                        submissionManager,
                        queryChecker,
                        ingestChecker,
                        dispatcher,
                        queryParser,
                        configProvider,
                        recordCache,
                        authorizer,
                        null,
                        feeManager,
                        synchronizedThrottleAccumulator,
                        instantSource,
                        opWorkflowMetrics,
                        true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        stateAccessor,
                        submissionManager,
                        queryChecker,
                        ingestChecker,
                        dispatcher,
                        queryParser,
                        configProvider,
                        recordCache,
                        authorizer,
                        exchangeRateManager,
                        null,
                        synchronizedThrottleAccumulator,
                        instantSource,
                        opWorkflowMetrics,
                        true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        stateAccessor,
                        submissionManager,
                        queryChecker,
                        ingestChecker,
                        dispatcher,
                        queryParser,
                        configProvider,
                        recordCache,
                        authorizer,
                        exchangeRateManager,
                        feeManager,
                        null,
                        instantSource,
                        opWorkflowMetrics,
                        true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        stateAccessor,
                        submissionManager,
                        queryChecker,
                        ingestChecker,
                        dispatcher,
                        queryParser,
                        configProvider,
                        recordCache,
                        authorizer,
                        exchangeRateManager,
                        feeManager,
                        synchronizedThrottleAccumulator,
                        null,
                        opWorkflowMetrics,
                        true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryWorkflowImpl(
                        stateAccessor,
                        submissionManager,
                        queryChecker,
                        ingestChecker,
                        dispatcher,
                        queryParser,
                        configProvider,
                        recordCache,
                        authorizer,
                        exchangeRateManager,
                        feeManager,
                        synchronizedThrottleAccumulator,
                        instantSource,
                        null,
                        true))
                .isInstanceOf(NullPointerException.class);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testHandleQueryWithIllegalParameters() {
        // given
        final var requestBuffer = Bytes.wrap(new byte[] {1, 2, 3});
        final var responseBuffer = newEmptyBuffer();

        // then
        assertThatThrownBy(() -> workflow.handleQuery(null, responseBuffer)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> workflow.handleQuery(requestBuffer, null)).isInstanceOf(NullPointerException.class);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testSuccessIfPaymentNotRequired(boolean shouldCharge) throws ParseException {
        // given
        workflow = new QueryWorkflowImpl(
                stateAccessor,
                submissionManager,
                queryChecker,
                ingestChecker,
                dispatcher,
                queryParser,
                configProvider,
                recordCache,
                authorizer,
                exchangeRateManager,
                feeManager,
                synchronizedThrottleAccumulator,
                instantSource,
                opWorkflowMetrics,
                shouldCharge);
        final var responseBuffer = newEmptyBuffer();
        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(OK);
        assertThat(header.responseType()).isEqualTo(ANSWER_ONLY);
        assertThat(header.cost()).isZero();
        verifyMetricsSent();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testSuccessIfPaymentRequired(boolean shouldCharge) throws ParseException, PreCheckException {
        doAnswer(invocationOnMock -> {
                    final var result = invocationOnMock.getArgument(3, IngestChecker.Result.class);
                    result.setThrottleUsages(List.of());
                    result.setTxnInfo(transactionInfo);
                    return null;
                })
                .when(ingestChecker)
                .runAllChecks(any(), any(), any(), any());
        // given
        workflow = new QueryWorkflowImpl(
                stateAccessor,
                submissionManager,
                queryChecker,
                ingestChecker,
                dispatcher,
                queryParser,
                configProvider,
                recordCache,
                authorizer,
                exchangeRateManager,
                feeManager,
                synchronizedThrottleAccumulator,
                instantSource,
                opWorkflowMetrics,
                shouldCharge);
        given(handler.computeFees(any(QueryContext.class))).willReturn(new Fees(100L, 0L, 100L));
        given(handler.requiresNodePayment(any())).willReturn(true);
        when(handler.findResponse(any(), any()))
                .thenReturn(Response.newBuilder()
                        .fileGetInfo(FileGetInfoResponse.newBuilder()
                                .header(ResponseHeader.newBuilder().build())
                                .build())
                        .build());
        final var responseBuffer = newEmptyBuffer();

        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(OK);
        assertThat(header.responseType()).isEqualTo(ANSWER_ONLY);
        assertThat(header.cost()).isZero();
        verifyMetricsSent();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testSuccessIfPaymentRequiredAndNotProvided() throws ParseException, PreCheckException {
        doAnswer(invocationOnMock -> {
                    final var result = invocationOnMock.getArgument(3, IngestChecker.Result.class);
                    result.setThrottleUsages(List.of());
                    result.setTxnInfo(transactionInfo);
                    return null;
                })
                .when(ingestChecker)
                .runAllChecks(any(), any(), any(), any());
        final var queryHeader =
                QueryHeader.newBuilder().payment((Transaction) null).build();
        final var query = Query.newBuilder()
                .fileGetInfo(FileGetInfoQuery.newBuilder().header(queryHeader))
                .build();
        when(queryParser.parseStrict((ReadableSequentialData) notNull())).thenReturn(query);
        when(handler.extractHeader(query)).thenReturn(queryHeader);
        when(dispatcher.getHandler(query)).thenReturn(handler);
        given(handler.computeFees(any(QueryContext.class))).willReturn(new Fees(100L, 0L, 100L));
        given(handler.requiresNodePayment(any())).willReturn(true);
        when(handler.findResponse(any(), any()))
                .thenReturn(Response.newBuilder()
                        .fileGetInfo(FileGetInfoResponse.newBuilder()
                                .header(ResponseHeader.newBuilder().build())
                                .build())
                        .build());
        doThrow(new PreCheckException(INSUFFICIENT_TX_FEE))
                .when(queryChecker)
                .validateCryptoTransfer(any(), eq(transactionInfo), any());
        final var responseBuffer = newEmptyBuffer();

        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(INSUFFICIENT_TX_FEE);
        assertThat(header.responseType()).isEqualTo(ANSWER_ONLY);
        assertThat(header.cost()).isZero();
        verifyMetricsSent();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testSuccessIfCostOnly() throws ParseException {
        // given
        final var queryHeader =
                QueryHeader.newBuilder().responseType(COST_ANSWER).build();
        final var query = Query.newBuilder()
                .fileGetInfo(FileGetInfoQuery.newBuilder().header(queryHeader))
                .build();
        when(queryParser.parseStrict((ReadableSequentialData) notNull())).thenReturn(query);
        when(dispatcher.getHandler(query)).thenReturn(handler);
        when(handler.extractHeader(query)).thenReturn(queryHeader);
        when(handler.needsAnswerOnlyCost(COST_ANSWER)).thenReturn(true);
        final var fees = new Fees(1L, 20L, 300L);
        when(handler.computeFees(any())).thenReturn(fees);
        final var responseHeader = ResponseHeader.newBuilder()
                .responseType(COST_ANSWER)
                .nodeTransactionPrecheckCode(OK)
                .cost(321L)
                .build();
        final var expectedResponse = Response.newBuilder()
                .fileGetInfo(FileGetInfoResponse.newBuilder().header(responseHeader))
                .build();
        when(handler.createEmptyResponse(responseHeader)).thenReturn(expectedResponse);
        final var responseBuffer = newEmptyBuffer();

        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final var actualResponse = parseResponse(responseBuffer);
        final var header = actualResponse.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(OK);
        assertThat(header.responseType()).isEqualTo(COST_ANSWER);
        assertThat(header.cost()).isEqualTo(fees.totalFee());
        verifyMetricsSent();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testParsingFails() throws ParseException {
        // given
        when(queryParser.parseStrict((ReadableSequentialData) notNull()))
                .thenThrow(new ParseException(new RuntimeException("Expected failure")));
        final var responseBuffer = newEmptyBuffer();

        // then
        assertThatThrownBy(() -> workflow.handleQuery(requestBuffer, responseBuffer))
                .isInstanceOf(StatusRuntimeException.class)
                .hasFieldOrPropertyWithValue("status", Status.INVALID_ARGUMENT);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testUnrecognizableQueryTypeFails() throws ParseException {
        // given
        final var query = Query.newBuilder().build();
        when(queryParser.parseStrict((ReadableSequentialData) notNull())).thenReturn(query);
        final var responseBuffer = newEmptyBuffer();

        // then
        assertThatThrownBy(() -> workflow.handleQuery(requestBuffer, responseBuffer))
                .isInstanceOf(StatusRuntimeException.class)
                .hasFieldOrPropertyWithValue("status", Status.INVALID_ARGUMENT);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testUnknownQueryParamFails() throws ParseException {
        // given
        when(queryParser.parseStrict((ReadableSequentialData) notNull()))
                .thenThrow(new ParseException(new UnknownFieldException("bogus field")));
        final var responseBuffer = newEmptyBuffer();

        // then
        assertThrows(StatusRuntimeException.class, () -> workflow.handleQuery(requestBuffer, responseBuffer));
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testInvalidNodeFails() throws PreCheckException, ParseException {
        // given
        when(handler.requiresNodePayment(ANSWER_ONLY)).thenReturn(true);
        doThrow(new PreCheckException(INVALID_NODE_ACCOUNT)).when(ingestChecker).verifyPlatformActive();
        final var responseBuffer = newEmptyBuffer();

        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(INVALID_NODE_ACCOUNT);
        assertThat(header.responseType()).isEqualTo(ANSWER_ONLY);
        assertThat(header.cost()).isZero();
        verifyMetricsSent();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testUnsupportedResponseTypeFails() throws ParseException {
        // given
        final var localRequestBuffer = newEmptyBuffer();
        final var queryHeader =
                QueryHeader.newBuilder().responseType(ANSWER_STATE_PROOF).build();
        final var query = Query.newBuilder()
                .fileGetInfo(FileGetInfoQuery.newBuilder().header(queryHeader).build())
                .build();
        when(queryParser.parseStrict((ReadableSequentialData) notNull())).thenReturn(query);

        final var requestBytes = CommonPbjConverters.asBytes(localRequestBuffer);
        when(handler.extractHeader(query)).thenReturn(queryHeader);
        when(dispatcher.getHandler(query)).thenReturn(handler);
        final var responseBuffer = newEmptyBuffer();

        // when
        workflow.handleQuery(Bytes.wrap(requestBytes), responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(NOT_SUPPORTED);
        assertThat(header.responseType()).isEqualTo(ANSWER_STATE_PROOF);
        assertThat(header.cost()).isZero();
        verifyMetricsSent();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testThrottleFails() throws ParseException {
        // given
        when(synchronizedThrottleAccumulator.shouldThrottle(eq(HederaFunctionality.FILE_GET_INFO), any(), any(), any()))
                .thenReturn(true);
        final var responseBuffer = newEmptyBuffer();

        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(BUSY);
        assertThat(header.responseType()).isEqualTo(ANSWER_ONLY);
        assertThat(header.cost()).isZero();
        verifyMetricsSent();
        verify(opWorkflowMetrics).incrementThrottled(FILE_GET_INFO);
    }

    @Test
    void testThrottleDoesNotFailWhenWorkflowShouldNotCharge() throws ParseException {
        // given
        workflow = new QueryWorkflowImpl(
                stateAccessor,
                submissionManager,
                queryChecker,
                ingestChecker,
                dispatcher,
                queryParser,
                configProvider,
                recordCache,
                authorizer,
                exchangeRateManager,
                feeManager,
                synchronizedThrottleAccumulator,
                instantSource,
                opWorkflowMetrics,
                false);
        when(synchronizedThrottleAccumulator.shouldThrottle(eq(HederaFunctionality.FILE_GET_INFO), any(), any(), any()))
                .thenReturn(true);
        final var responseBuffer = newEmptyBuffer();

        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(OK);
        assertThat(header.responseType()).isEqualTo(ANSWER_ONLY);
        assertThat(header.cost()).isZero();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testPaidQueryWithInvalidTransactionFails() throws PreCheckException, ParseException {
        // given
        when(handler.requiresNodePayment(ANSWER_ONLY)).thenReturn(true);
        doAnswer(invocationOnMock -> {
                    throw new PreCheckException(INVALID_TRANSACTION_BODY);
                })
                .when(ingestChecker)
                .runAllChecks(any(), any(), any(), any());
        final var responseBuffer = newEmptyBuffer();

        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(INVALID_TRANSACTION_BODY);
        assertThat(header.responseType()).isEqualTo(ANSWER_ONLY);
        assertThat(header.cost()).isZero();
        verifyMetricsSent();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testPaidQueryWithInvalidCryptoTransferFails() throws PreCheckException, ParseException {
        // given
        doAnswer(invocationOnMock -> {
                    final var result = invocationOnMock.getArgument(3, IngestChecker.Result.class);
                    result.setThrottleUsages(List.of());
                    result.setTxnInfo(transactionInfo);
                    return null;
                })
                .when(ingestChecker)
                .runAllChecks(any(), any(), any(), any());
        when(handler.requiresNodePayment(ANSWER_ONLY)).thenReturn(true);
        doThrow(new PreCheckException(INSUFFICIENT_TX_FEE))
                .when(queryChecker)
                .validateCryptoTransfer(any(), eq(transactionInfo), any());
        final var responseBuffer = newEmptyBuffer();

        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(INSUFFICIENT_TX_FEE);
        assertThat(header.responseType()).isEqualTo(ANSWER_ONLY);
        assertThat(header.cost()).isZero();
        verifyMetricsSent();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testPaidQueryForSuperUserDoesNotSubmitCryptoTransfer() throws PreCheckException, ParseException {
        // given
        given(handler.computeFees(any(QueryContext.class))).willReturn(new Fees(100L, 0L, 100L));
        given(handler.requiresNodePayment(any())).willReturn(true);
        when(handler.findResponse(any(), any()))
                .thenReturn(Response.newBuilder()
                        .fileGetInfo(FileGetInfoResponse.newBuilder()
                                .header(ResponseHeader.newBuilder().build())
                                .build())
                        .build());
        final var responseBuffer = newEmptyBuffer();
        given(authorizer.isSuperUser(ALICE.accountID())).willReturn(true);
        doAnswer(invocationOnMock -> {
                    final var result = invocationOnMock.getArgument(3, IngestChecker.Result.class);
                    result.setThrottleUsages(List.of());
                    result.setTxnInfo(transactionInfo);
                    return null;
                })
                .when(ingestChecker)
                .runAllChecks(any(), any(), any(), any());

        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(OK);
        assertThat(header.responseType()).isEqualTo(ANSWER_ONLY);
        assertThat(header.cost()).isZero();

        verify(submissionManager, never()).submit(any(), any(), anyBoolean());
        verifyMetricsSent();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testPaidQueryWithInsufficientPermissionFails() throws PreCheckException, ParseException {
        // given
        when(handler.requiresNodePayment(ANSWER_ONLY)).thenReturn(true);
        doThrow(new PreCheckException(NOT_SUPPORTED))
                .when(queryChecker)
                .checkPermissions(ALICE.accountID(), HederaFunctionality.FILE_GET_INFO);
        final var responseBuffer = newEmptyBuffer();
        doAnswer(invocationOnMock -> {
                    final var result = invocationOnMock.getArgument(3, IngestChecker.Result.class);
                    result.setThrottleUsages(List.of());
                    result.setTxnInfo(transactionInfo);
                    return null;
                })
                .when(ingestChecker)
                .runAllChecks(any(), any(), any(), any());

        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(NOT_SUPPORTED);
        assertThat(header.responseType()).isEqualTo(ANSWER_ONLY);
        assertThat(header.cost()).isZero();
        verifyMetricsSent();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testPaidQueryWithInsufficientBalanceFails() throws PreCheckException, ParseException {
        // given
        doAnswer(invocationOnMock -> {
                    final var result = invocationOnMock.getArgument(3, IngestChecker.Result.class);
                    result.setThrottleUsages(List.of());
                    result.setTxnInfo(transactionInfo);
                    return null;
                })
                .when(ingestChecker)
                .runAllChecks(any(), any(), any(), any());
        given(handler.computeFees(any(QueryContext.class))).willReturn(new Fees(1L, 20L, 300L));
        when(handler.requiresNodePayment(ANSWER_ONLY)).thenReturn(true);
        when(queryChecker.estimateTxFees(
                        any(), any(), eq(transactionInfo), eq(ALICE.account().key()), eq(configuration)))
                .thenReturn(4000L);
        doThrow(new InsufficientBalanceException(INSUFFICIENT_TX_FEE, 12345L))
                .when(queryChecker)
                .validateAccountBalances(any(), eq(transactionInfo), eq(ALICE.account()), eq(321L), eq(4000L));
        final var responseBuffer = newEmptyBuffer();

        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(INSUFFICIENT_TX_FEE);
        assertThat(header.responseType()).isEqualTo(ANSWER_ONLY);
        assertThat(header.cost()).isEqualTo(12345L);
        verifyMetricsSent();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testUnpaidQueryWithRestrictedFunctionalityFails(@Mock NetworkGetExecutionTimeHandler networkHandler)
            throws ParseException {
        // given
        final var localRequestBuffer = newEmptyBuffer();
        final var localQueryHeader =
                QueryHeader.newBuilder().responseType(COST_ANSWER).build();
        final var localQuery = Query.newBuilder()
                .networkGetExecutionTime(
                        NetworkGetExecutionTimeQuery.newBuilder().header(localQueryHeader))
                .build();

        final var requestBytes = CommonPbjConverters.asBytes(localRequestBuffer);
        when(queryParser.parseStrict((ReadableSequentialData) notNull())).thenReturn(localQuery);
        when(networkHandler.extractHeader(localQuery)).thenReturn(localQueryHeader);
        when(dispatcher.getHandler(localQuery)).thenReturn(networkHandler);

        final var expectedResponse = Response.newBuilder()
                .networkGetExecutionTime(NetworkGetExecutionTimeResponse.newBuilder()
                        .header(ResponseHeader.newBuilder()
                                .responseType(COST_ANSWER)
                                .nodeTransactionPrecheckCode(NOT_SUPPORTED)
                                .build())
                        .build())
                .build();
        when(networkHandler.createEmptyResponse(notNull())).thenReturn(expectedResponse);
        final var responseBuffer = newEmptyBuffer();

        // when
        workflow.handleQuery(Bytes.wrap(requestBytes), responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.networkGetExecutionTimeOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(NOT_SUPPORTED);
        assertThat(header.responseType()).isEqualTo(COST_ANSWER);
        assertThat(header.cost()).isZero();
        verify(opWorkflowMetrics).updateDuration(eq(NETWORK_GET_EXECUTION_TIME), anyInt());
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testQuerySpecificValidationFails() throws PreCheckException, ParseException {
        final var captor = ArgumentCaptor.forClass(QueryContext.class);
        // given
        doThrow(new PreCheckException(ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN))
                .when(handler)
                .validate(captor.capture());
        final var responseBuffer = newEmptyBuffer();

        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final Response response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN);
        assertThat(header.responseType()).isEqualTo(ANSWER_ONLY);
        assertThat(header.cost()).isZero();
        final var queryContext = captor.getValue();
        assertThat(queryContext.payer()).isNull();
        verifyMetricsSent();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void testPaidQueryWithFailingSubmissionFails() throws PreCheckException, ParseException {
        // given
        when(handler.requiresNodePayment(ANSWER_ONLY)).thenReturn(true);
        doThrow(new PreCheckException(PLATFORM_TRANSACTION_NOT_CREATED))
                .when(submissionManager)
                .submit(txBody, serializedPayment, false);
        given(handler.computeFees(any(QueryContext.class))).willReturn(new Fees(100L, 0L, 100L));
        final var responseBuffer = newEmptyBuffer();
        doAnswer(invocationOnMock -> {
                    final var result = invocationOnMock.getArgument(3, IngestChecker.Result.class);
                    result.setThrottleUsages(List.of());
                    result.setTxnInfo(transactionInfo);
                    return null;
                })
                .when(ingestChecker)
                .runAllChecks(any(), any(), any(), any());

        // when
        workflow.handleQuery(requestBuffer, responseBuffer);

        // then
        final var response = parseResponse(responseBuffer);
        final var header = response.fileGetInfoOrThrow().headerOrThrow();
        assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(PLATFORM_TRANSACTION_NOT_CREATED);
        assertThat(header.responseType()).isEqualTo(ANSWER_ONLY);
        assertThat(header.cost()).isZero();
        verifyMetricsSent();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    private void verifyMetricsSent() {
        verify(opWorkflowMetrics).updateDuration(eq(FILE_GET_INFO), anyInt());
    }

    private static Response parseResponse(BufferedData responseBuffer) throws ParseException {
        final byte[] bytes = new byte[Math.toIntExact(responseBuffer.position())];
        responseBuffer.resetPosition();
        responseBuffer.readBytes(bytes);
        return Response.PROTOBUF.parseStrict(BufferedData.wrap(bytes));
    }

    private static BufferedData newEmptyBuffer() {
        return BufferedData.allocate(BUFFER_SIZE);
    }

    @Nested
    @DisplayName("Simple Fees Tests")
    class SimpleFeesTests {
        @Mock
        private ExchangeRateInfo testExchangeRateInfo;

        private ExchangeRate testExchangeRate;

        /**
         * Provides test data for query types that use simple fees.
         * <p><b>To enable a new query type:</b> Add a new Arguments entry here with:
         * <ol>
         *   <li>Descriptive name (for test display)</li>
         *   <li>TransactionBody with the transaction type set</li>
         * </ol>
         */
        static Stream<Arguments> simpleFeesEnabledTransactions() {
            return Stream.of(Arguments.of(
                    "CONSENSUS_GET_TOPIC_INFO",
                    Query.newBuilder()
                            .consensusGetTopicInfo(
                                    ConsensusGetTopicInfoQuery.newBuilder().build())
                            .build()));
        }

        @Mock(strictness = LENIENT)
        private QueryContext queryContext;

        @Mock
        private FeesConfig feesConfig;

        @Mock(strictness = LENIENT)
        private SimpleFeeCalculator simpleFeeCalculator;

        @Mock
        private Configuration configuration;

        @BeforeEach
        void setUp() {
            testExchangeRate =
                    ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(12).build();
            //            testExchangeRateInfo =  new ExchangeRateInfoImpl(ExchangeRateSet.DEFAULT);
        }

        @Test
        void testQueryUsesSimpleFees() throws PreCheckException, ParseException {
            final var transactionID =
                    TransactionID.newBuilder().accountID(ALICE.accountID()).build();
            txBody = TransactionBody.newBuilder().transactionID(transactionID).build();
            final var payment = Transaction.newBuilder().body(txBody).build();
            final var queryHeader = QueryHeader.newBuilder().payment(payment).build();
            final var query = Query.newBuilder()
                    .consensusGetTopicInfo(
                            ConsensusGetTopicInfoQuery.newBuilder().header(queryHeader))
                    .build();
            // Given: Simple fees are enabled
            given(feesConfig.simpleFeesEnabled()).willReturn(true);
            given(configuration.getConfigData(FeesConfig.class)).willReturn(feesConfig);

            when(configProvider.getConfiguration())
                    .thenReturn(new VersionedConfigImpl(configuration, DEFAULT_CONFIG_VERSION));
            given(queryContext.configuration()).willReturn(configuration);
            given(queryContext.exchangeRateInfo()).willReturn(testExchangeRateInfo);

            doAnswer(invocationOnMock -> {
                        final var result = invocationOnMock.getArgument(3, IngestChecker.Result.class);
                        result.setThrottleUsages(List.of());
                        result.setTxnInfo(transactionInfo);
                        return null;
                    })
                    .when(ingestChecker)
                    .runAllChecks(any(), any(), any(), any());

            given(feeManager.getSimpleFeeCalculator()).willReturn(simpleFeeCalculator);
            final var result = new FeeResult(0, 100000L, 0);
            given(simpleFeeCalculator.calculateQueryFee(eq(query), any())).willReturn(result);

            mockTopicGetInfoHandler(query, queryHeader, payment);

            // When
            final var responseBuffer = newEmptyBuffer();
            workflow.handleQuery(requestBuffer, responseBuffer);

            // Then: Should use simple fee calculator
            verify(simpleFeeCalculator).calculateQueryFee(eq(query), any());
        }

        @Test
        @DisplayName("Simple fees not used when feature is disabled")
        void testSimpleFeesNotUsedWhenFeatureDisabled() throws PreCheckException {
            doAnswer(invocationOnMock -> {
                        final var result = invocationOnMock.getArgument(3, IngestChecker.Result.class);
                        result.setThrottleUsages(List.of());
                        result.setTxnInfo(transactionInfo);
                        return null;
                    })
                    .when(ingestChecker)
                    .runAllChecks(any(), any(), any(), any());

            boolean shouldCharge = true;
            workflow = new QueryWorkflowImpl(
                    stateAccessor,
                    submissionManager,
                    queryChecker,
                    ingestChecker,
                    dispatcher,
                    queryParser,
                    configProvider,
                    recordCache,
                    authorizer,
                    exchangeRateManager,
                    feeManager,
                    synchronizedThrottleAccumulator,
                    instantSource,
                    opWorkflowMetrics,
                    shouldCharge);
            given(handler.requiresNodePayment(any())).willReturn(true);
            final var responseBuffer = newEmptyBuffer();
            // when
            workflow.handleQuery(requestBuffer, responseBuffer);
            verify(feeManager, never()).getSimpleFeeCalculator();
        }
    }

    private void mockTopicGetInfoHandler(Query query, QueryHeader queryHeader, Transaction payment)
            throws ParseException, PreCheckException {
        final var signedPayment = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(txBody))
                .build();
        serializedPayment = Transaction.PROTOBUF.toBytes(payment);

        requestBuffer = Query.PROTOBUF.toBytes(query);
        when(queryParser.parseStrict((ReadableSequentialData) notNull())).thenReturn(query);

        final var feeCalculatorMock = mock(FeeCalculator.class);
        when(feeManager.createFeeCalculator(eq(CONSENSUS_GET_TOPIC_INFO), any(), any()))
                .thenReturn(feeCalculatorMock);

        final var signatureMap = SignatureMap.newBuilder().build();
        transactionInfo = new TransactionInfo(
                signedPayment, txBody, signatureMap, signedPayment.bodyBytes(), CRYPTO_TRANSFER, serializedPayment);
        doAnswer(invocationOnMock -> {
                    final var result = invocationOnMock.getArgument(3, IngestChecker.Result.class);
                    result.setThrottleUsages(List.of());
                    result.setTxnInfo(transactionInfo);
                    return null;
                })
                .when(ingestChecker)
                .runAllChecks(eq(state), eq(requestBuffer), eq(configuration), any());

        final var getTopicInfoHandler =
                mock(ConsensusGetTopicInfoHandler.class, withSettings().strictness(Strictness.LENIENT));
        when(getTopicInfoHandler.requiresNodePayment(any())).thenReturn(true);
        when(getTopicInfoHandler.requiresNodePayment(any())).thenReturn(true);
        when(getTopicInfoHandler.extractHeader(query)).thenReturn(queryHeader);
        when(getTopicInfoHandler.createEmptyResponse(any())).thenAnswer((Answer<Response>) invocation -> {
            final var header = (ResponseHeader) invocation.getArguments()[0];
            return Response.newBuilder()
                    .consensusGetTopicInfo(ConsensusGetTopicInfoResponse.newBuilder()
                            .header(header)
                            .build())
                    .build();
        });

        final var responseHeader = ResponseHeader.newBuilder()
                .responseType(ANSWER_ONLY)
                .nodeTransactionPrecheckCode(OK)
                .build();
        final var queryResponse = ConsensusGetTopicInfoResponse.newBuilder()
                .header(responseHeader)
                .build();
        final var response =
                Response.newBuilder().consensusGetTopicInfo(queryResponse).build();

        when(dispatcher.getHandler(query)).thenReturn(getTopicInfoHandler);
        when(getTopicInfoHandler.findResponse(any(), eq(responseHeader))).thenReturn(response);

        workflow = new QueryWorkflowImpl(
                stateAccessor,
                submissionManager,
                queryChecker,
                ingestChecker,
                dispatcher,
                queryParser,
                configProvider,
                recordCache,
                authorizer,
                exchangeRateManager,
                feeManager,
                synchronizedThrottleAccumulator,
                instantSource,
                opWorkflowMetrics,
                true);
    }
}

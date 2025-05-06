// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.query;

import static com.hedera.hapi.node.base.HederaFunctionality.GET_ACCOUNT_DETAILS;
import static com.hedera.hapi.node.base.HederaFunctionality.NETWORK_GET_EXECUTION_TIME;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BUSY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER_STATE_PROOF;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.InsufficientBalanceException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.app.spi.workflows.QueryHandler;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.throttle.SynchronizedThrottleAccumulator;
import com.hedera.node.app.util.ProtobufUtils;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.ingest.IngestChecker;
import com.hedera.node.app.workflows.ingest.SubmissionManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.MalformedProtobufException;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.UnknownFieldException;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.time.InstantSource;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;
import javax.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Implementation of {@link QueryWorkflow} */
public final class QueryWorkflowImpl implements QueryWorkflow {

    private static final Logger logger = LogManager.getLogger(QueryWorkflowImpl.class);

    private static final EnumSet<ResponseType> UNSUPPORTED_RESPONSE_TYPES =
            EnumSet.of(ANSWER_STATE_PROOF, COST_ANSWER_STATE_PROOF);
    private static final List<HederaFunctionality> RESTRICTED_FUNCTIONALITIES =
            List.of(NETWORK_GET_EXECUTION_TIME, GET_ACCOUNT_DETAILS);

    private final Function<ResponseType, AutoCloseableWrapper<State>> stateAccessor;
    private final SubmissionManager submissionManager;
    private final QueryChecker queryChecker;
    private final IngestChecker ingestChecker;
    private final QueryDispatcher dispatcher;

    private final Codec<Query> queryParser;
    private final ConfigProvider configProvider;
    private final RecordCache recordCache;
    private final Authorizer authorizer;
    private final ExchangeRateManager exchangeRateManager;
    private final FeeManager feeManager;
    private final SynchronizedThrottleAccumulator synchronizedThrottleAccumulator;
    private final InstantSource instantSource;
    private final OpWorkflowMetrics workflowMetrics;
    private final SemanticVersion softwareVersionFactory;

    /**
     * Indicates if the QueryWorkflow should charge for handling queries.
     */
    private final boolean shouldCharge;

    /**
     * Constructor of {@code QueryWorkflowImpl}
     *
     * @param stateAccessor a {@link Function} that returns the latest immutable or latest signed state depending on the
     * {@link ResponseType}
     * @param submissionManager the {@link SubmissionManager} to submit transactions to the platform
     * @param queryChecker the {@link QueryChecker} with specific checks of an ingest-workflow
     * @param ingestChecker the {@link IngestChecker} to handle the crypto transfer
     * @param dispatcher the {@link QueryDispatcher} that will call query-specific methods
     * @param queryParser the {@link Codec} to parse a query
     * @param configProvider the {@link ConfigProvider} to get the current configuration
     * @param recordCache the {@link RecordCache}
     * @param authorizer the {@link Authorizer} to check permissions and special privileges
     * @param exchangeRateManager the {@link ExchangeRateManager} to get the {@link ExchangeRateInfo}
     * @param feeManager the {@link FeeManager} to calculate the fees
     * @param synchronizedThrottleAccumulator the {@link SynchronizedThrottleAccumulator} that checks transaction should be throttled
     * @param instantSource the {@link InstantSource} to get the current time
     * @param workflowMetrics the {@link OpWorkflowMetrics} to update the metrics
     * @param shouldCharge If the workflow should charge for handling queries.
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    @Inject
    public QueryWorkflowImpl(
            @NonNull final Function<ResponseType, AutoCloseableWrapper<State>> stateAccessor,
            @NonNull final SubmissionManager submissionManager,
            @NonNull final QueryChecker queryChecker,
            @NonNull final IngestChecker ingestChecker,
            @NonNull final QueryDispatcher dispatcher,
            @NonNull final Codec<Query> queryParser,
            @NonNull final ConfigProvider configProvider,
            @NonNull final RecordCache recordCache,
            @NonNull final Authorizer authorizer,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final FeeManager feeManager,
            @NonNull final SynchronizedThrottleAccumulator synchronizedThrottleAccumulator,
            @NonNull final InstantSource instantSource,
            @NonNull final OpWorkflowMetrics workflowMetrics,
            final boolean shouldCharge,
            @NonNull final SemanticVersion softwareVersionFactory) {
        this.stateAccessor = requireNonNull(stateAccessor, "stateAccessor must not be null");
        this.submissionManager = requireNonNull(submissionManager, "submissionManager must not be null");
        this.ingestChecker = requireNonNull(ingestChecker, "ingestChecker must not be null");
        this.queryChecker = requireNonNull(queryChecker, "queryChecker must not be null");
        this.dispatcher = requireNonNull(dispatcher, "dispatcher must not be null");
        this.queryParser = requireNonNull(queryParser, "queryParser must not be null");
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
        this.recordCache = requireNonNull(recordCache, "recordCache must not be null");
        this.exchangeRateManager = requireNonNull(exchangeRateManager, "exchangeRateManager must not be null");
        this.authorizer = requireNonNull(authorizer, "authorizer must not be null");
        this.feeManager = requireNonNull(feeManager, "feeManager must not be null");
        this.synchronizedThrottleAccumulator =
                requireNonNull(synchronizedThrottleAccumulator, "hapiThrottling must not be null");
        this.instantSource = requireNonNull(instantSource);
        this.workflowMetrics = requireNonNull(workflowMetrics);
        this.shouldCharge = shouldCharge;
        this.softwareVersionFactory = softwareVersionFactory;
    }

    @Override
    public void handleQuery(@NonNull final Bytes requestBuffer, @NonNull final BufferedData responseBuffer) {
        final long queryStart = System.nanoTime();

        requireNonNull(requestBuffer);
        requireNonNull(responseBuffer);

        // We use wall-clock time when calculating fees
        final var consensusTime = instantSource.instant();

        // 1. Parse and check header
        final Query query = parseQuery(requestBuffer);
        logger.debug("Received query: {}", query);
        final var function = functionOf(query);

        Response response;
        if (!HederaFunctionality.NONE.equals(function)) {
            final var handler = dispatcher.getHandler(query);
            var queryHeader = handler.extractHeader(query);
            if (queryHeader == null) {
                queryHeader = QueryHeader.DEFAULT;
            }
            final ResponseType responseType = queryHeader.responseType();
            logger.debug("Started answering a {} query of type {}", function, responseType);

            try (final var wrappedState = stateAccessor.apply(responseType)) {
                // 2. Do some general pre-checks
                ingestChecker.verifyPlatformActive();
                if (UNSUPPORTED_RESPONSE_TYPES.contains(responseType)) {
                    throw new PreCheckException(NOT_SUPPORTED);
                }

                final var state = wrappedState.get();
                final var storeFactory = new ReadableStoreFactory(state);
                final var paymentRequired = handler.requiresNodePayment(responseType);
                final var feeCalculator = feeManager.createFeeCalculator(function, consensusTime, storeFactory);
                final QueryContext context;
                TransactionBody txBody;
                AccountID payerID = null;
                if (shouldCharge && paymentRequired) {
                    final var configuration = configProvider.getConfiguration();
                    final var paymentBytes = ProtobufUtils.extractPaymentBytes(requestBuffer);

                    // 3.i Ingest checks
                    final var transactionInfo = ingestChecker.runAllChecks(state, paymentBytes, configuration);
                    txBody = transactionInfo.txBody();

                    // get payer
                    payerID = requireNonNull(transactionInfo.payerID());
                    context = new QueryContextImpl(
                            state,
                            storeFactory,
                            query,
                            configuration,
                            recordCache,
                            exchangeRateManager,
                            feeCalculator,
                            payerID);

                    // A super-user does not have to pay for a query and has all permissions
                    if (!authorizer.isSuperUser(payerID)) {
                        // But if payment is required, we must be able to submit a transaction
                        ingestChecker.verifyReadyForTransactions();

                        // 3.ii Validate CryptoTransfer
                        queryChecker.validateCryptoTransfer(transactionInfo);

                        // 3.iii Check permissions
                        queryChecker.checkPermissions(payerID, function);

                        // Get the payer
                        final var accountStore = storeFactory.getStore(ReadableAccountStore.class);
                        final var payer = accountStore.getAccountById(payerID);
                        if (payer == null) {
                            // This should never happen, because the account is checked in the pure checks
                            throw new PreCheckException(PAYER_ACCOUNT_NOT_FOUND);
                        }

                        // 3.iv Calculate costs
                        final var queryFees = handler.computeFees(context).totalFee();
                        final var txFees = queryChecker.estimateTxFees(
                                storeFactory, consensusTime, transactionInfo, payer.keyOrThrow(), configuration);

                        // 3.v Check account balances
                        queryChecker.validateAccountBalances(accountStore, transactionInfo, payer, queryFees, txFees);

                        // 3.vi Submit payment to platform
                        submissionManager.submit(txBody, paymentBytes);
                    }
                } else {
                    if (RESTRICTED_FUNCTIONALITIES.contains(function)) {
                        throw new PreCheckException(NOT_SUPPORTED);
                    }
                    context = new QueryContextImpl(
                            state,
                            storeFactory,
                            query,
                            configProvider.getConfiguration(),
                            recordCache,
                            exchangeRateManager,
                            feeCalculator,
                            null);
                }

                // 4. Check validity of query
                handler.validate(context);

                // 5. Check query throttles
                if (shouldCharge && synchronizedThrottleAccumulator.shouldThrottle(function, query, state, payerID)) {
                    workflowMetrics.incrementThrottled(function);
                    throw new PreCheckException(BUSY);
                }

                if (handler.needsAnswerOnlyCost(responseType)) {
                    // 6.i Estimate costs
                    final var queryFees = handler.computeFees(context).totalFee();

                    final var header = createResponseHeader(responseType, OK, queryFees);
                    response = handler.createEmptyResponse(header);
                } else {
                    // 6.ii Find response
                    final var header = createResponseHeader(responseType, OK, 0L);
                    response = handler.findResponse(context, header);
                }
            } catch (InsufficientBalanceException e) {
                response = createErrorResponse(handler, responseType, e.responseCode(), e.getEstimatedFee());
            } catch (PreCheckException e) {
                logger.debug("Query failed", e);
                response = createErrorResponse(handler, responseType, e.responseCode(), 0L);
            } catch (HandleException e) {
                logger.debug("Query failed", e);
                // Conceptually, this should never happen, because we should use PreCheckException only for queries
                // But we catch it here to play it safe
                response = createErrorResponse(handler, responseType, e.getStatus(), 0L);
            } catch (Exception e) {
                logger.error("Unexpected exception while handling a query", e);
                response = createErrorResponse(handler, responseType, FAIL_INVALID, 0L);
            }
        } else {
            throw new StatusRuntimeException(Status.INVALID_ARGUMENT);
        }

        try {
            Response.PROTOBUF.write(response, responseBuffer);
            logger.debug("Finished handling a query request in Query workflow");
        } catch (IOException e) {
            logger.warn("Unexpected IO exception while writing protobuf", e);
            throw new StatusRuntimeException(Status.INTERNAL);
        }

        workflowMetrics.updateDuration(function, (int) (System.nanoTime() - queryStart));
    }

    private Query parseQuery(Bytes requestBuffer) {
        try {
            return queryParser.parseStrict(requestBuffer.toReadableSequentialData());
        } catch (ParseException e) {
            switch (e.getCause()) {
                case MalformedProtobufException ignored:
                    break;
                case UnknownFieldException ignored:
                    break;
                default:
                    logger.warn("Unexpected ParseException while parsing protobuf", e);
            }
            throw new StatusRuntimeException(Status.INVALID_ARGUMENT);
        }
    }

    private static Response createErrorResponse(
            @NonNull final QueryHandler handler,
            @NonNull final ResponseType responseType,
            @NonNull final ResponseCodeEnum responseCode,
            final long fee) {
        final var header = createResponseHeader(responseType, responseCode, fee);
        return handler.createEmptyResponse(header);
    }

    private static ResponseHeader createResponseHeader(
            @NonNull final ResponseType type, @NonNull final ResponseCodeEnum responseCode, final long fee) {
        return ResponseHeader.newBuilder()
                .responseType(type)
                .nodeTransactionPrecheckCode(responseCode)
                .cost(fee)
                .build();
    }

    private static HederaFunctionality functionOf(@NonNull final Query query) {
        try {
            return HapiUtils.functionOf(query);
        } catch (UnknownHederaFunctionality e) {
            return HederaFunctionality.NONE;
        }
    }
}

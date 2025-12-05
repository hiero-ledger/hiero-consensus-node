// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.query;

import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.app.spi.workflows.QueryHandler;
import com.hedera.node.config.data.FeesConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.InstantSource;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.hapi.fees.FeeResult;

/**
 * A {@code QueryDispatcher} provides functionality to forward validate, and reply-query requests to
 * the appropriate handler
 */
@Singleton
public class QueryDispatcher {

    private static final String QUERY_NOT_SET = "Query not set";

    private final QueryHandlers handlers;
    private final FeeManager feeManager;
    private final InstantSource instantSource;

    /**
     * Constructor of {@code QueryDispatcher}
     *
     * @param handlers a {@link QueryHandlers} record with all available handlers
     * @param feeManager the {@link FeeManager} for fee calculations
     * @param instantSource the {@link InstantSource} to get the current time
     * @throws NullPointerException if one of the parameters is {@code null}
     */
    @Inject
    public QueryDispatcher(
            @NonNull final QueryHandlers handlers,
            @NonNull final FeeManager feeManager,
            @NonNull final InstantSource instantSource) {
        this.handlers = requireNonNull(handlers);
        this.feeManager = requireNonNull(feeManager);
        this.instantSource = requireNonNull(instantSource);
    }

    /**
     * Returns the {@link QueryHandler} for a given {@link Query}
     *
     * @param query the {@link Query} for which the {@link QueryHandler} is requested
     * @return the {@code QueryHandler} for the query
     */
    @NonNull
    public QueryHandler getHandler(@NonNull final Query query) {
        return switch (query.query().kind()) {
            case CONSENSUS_GET_TOPIC_INFO -> handlers.consensusGetTopicInfoHandler();

            case GET_BY_SOLIDITY_ID -> handlers.contractGetBySolidityIDHandler();
            case CONTRACT_CALL_LOCAL -> handlers.contractCallLocalHandler();
            case CONTRACT_GET_INFO -> handlers.contractGetInfoHandler();
            case CONTRACT_GET_BYTECODE -> handlers.contractGetBytecodeHandler();
            case CONTRACT_GET_RECORDS -> handlers.contractGetRecordsHandler();

            case CRYPTOGET_ACCOUNT_BALANCE -> handlers.cryptoGetAccountBalanceHandler();
            case CRYPTO_GET_INFO -> handlers.cryptoGetAccountInfoHandler();
            case CRYPTO_GET_ACCOUNT_RECORDS -> handlers.cryptoGetAccountRecordsHandler();
            case CRYPTO_GET_LIVE_HASH -> handlers.cryptoGetLiveHashHandler();
            case CRYPTO_GET_PROXY_STAKERS -> handlers.cryptoGetStakersHandler();

            case FILE_GET_CONTENTS -> handlers.fileGetContentsHandler();
            case FILE_GET_INFO -> handlers.fileGetInfoHandler();

            case ACCOUNT_DETAILS -> handlers.networkGetAccountDetailsHandler();
            case GET_BY_KEY -> handlers.networkGetByKeyHandler();
            case NETWORK_GET_VERSION_INFO -> handlers.networkGetVersionInfoHandler();
            case NETWORK_GET_EXECUTION_TIME -> handlers.networkGetExecutionTimeHandler();
            case TRANSACTION_GET_RECEIPT -> handlers.networkTransactionGetReceiptHandler();
            case TRANSACTION_GET_RECORD -> handlers.networkTransactionGetRecordHandler();
            case TRANSACTION_GET_FAST_RECORD -> handlers.networkTransactionGetFastRecordHandler();

            case SCHEDULE_GET_INFO -> handlers.scheduleGetInfoHandler();

            case TOKEN_GET_INFO -> handlers.tokenGetInfoHandler();
            case TOKEN_GET_ACCOUNT_NFT_INFOS -> handlers.tokenGetAccountNftInfosHandler();
            case TOKEN_GET_NFT_INFO -> handlers.tokenGetNftInfoHandler();
            case TOKEN_GET_NFT_INFOS -> handlers.tokenGetNftInfosHandler();

            case UNSET -> throw new UnsupportedOperationException(QUERY_NOT_SET);
        };
    }

    /**
     * Dispatch a compute fees request for queries. Routes to simple fee calculation
     * when enabled, otherwise falls back to legacy handler calculation.
     *
     * @param queryContext the query context containing all needed information
     * @return the calculated fees
     */
    @NonNull
    public Fees dispatchComputeFees(@NonNull final QueryContext queryContext) {
        requireNonNull(queryContext, "queryContext must not be null!");

        final var handler = getHandler(queryContext.query());

        if (shouldUseSimpleFees(queryContext)) {
            final var simpleFeeCalculator = feeManager.getSimpleFeeCalculator();
            if (simpleFeeCalculator != null) {
                final var feeResult = simpleFeeCalculator.calculateQueryFee(queryContext.query(), null);
                return feeResultToFees(
                        feeResult, queryContext.exchangeRateInfo().activeRate(instantSource.instant()));
            }
        }

        // Fallback to legacy calculation
        return handler.computeFees(queryContext);
    }

    /**
     * Determines if simple fees should be used for this query based on config and query type.
     *
     * @param queryContext the query context
     * @return true if simple fees should be used
     */
    private boolean shouldUseSimpleFees(@NonNull final QueryContext queryContext) {
        if (!queryContext.configuration().getConfigData(FeesConfig.class).simpleFeesEnabled()) {
            return false;
        }

        return switch (queryContext.query().query().kind()) {
            case CRYPTO_GET_INFO, CRYPTO_GET_ACCOUNT_RECORDS -> true;
            default -> false;
        };
    }

    /**
     * Converts tinycents to tinybars using the exchange rate.
     *
     * @param amount the amount in tinycents
     * @param rate the exchange rate
     * @return the amount in tinybars
     */
    private static long tinycentsToTinybars(final long amount, final ExchangeRate rate) {
        final var hbarEquiv = rate.hbarEquiv();
        if (productWouldOverflow(amount, hbarEquiv)) {
            return FeeBuilder.getTinybarsFromTinyCents(
                    com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj(rate), amount);
        }
        return amount * hbarEquiv / rate.centEquiv();
    }

    /**
     * Converts a FeeResult (in tinycents) to Fees (in tinybars).
     *
     * @param feeResult the fee result in tinycents
     * @param rate the exchange rate
     * @return fees in tinybars
     */
    private static Fees feeResultToFees(@NonNull final FeeResult feeResult, @NonNull final ExchangeRate rate) {
        return new Fees(
                tinycentsToTinybars(feeResult.node, rate),
                tinycentsToTinybars(feeResult.network, rate),
                tinycentsToTinybars(feeResult.service, rate));
    }
}

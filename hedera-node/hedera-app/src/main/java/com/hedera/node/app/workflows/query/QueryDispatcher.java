// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.query;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.spi.workflows.QueryHandler;
import edu.umd.cs.findbugs.annotations.NonNull;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A {@code QueryDispatcher} provides functionality to forward validate, and reply-query requests to
 * the appropriate handler
 */
@Singleton
public class QueryDispatcher {

    private static final String QUERY_NOT_SET = "Query not set";

    private final QueryHandlers handlers;

    /**
     * Constructor of {@code QueryDispatcher}
     *
     * @param handlers a {@link QueryHandlers} record with all available handlers
     * @throws NullPointerException if one of the parameters is {@code null}
     */
    @Inject
    public QueryDispatcher(@NonNull final QueryHandlers handlers) {
        this.handlers = requireNonNull(handlers);
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

            case GET_CLPR_LEDGER_CONFIGURATION, UNSET -> throw new UnsupportedOperationException(QUERY_NOT_SET);
        };
    }
}

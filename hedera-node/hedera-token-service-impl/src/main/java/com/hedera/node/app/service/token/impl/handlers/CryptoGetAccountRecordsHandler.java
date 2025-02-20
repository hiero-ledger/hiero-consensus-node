// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;
import static com.hedera.node.app.spi.fees.Fees.CONSTANT_FEE_DATA;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoGetAccountRecordsResponse;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.fee.CryptoFeeBuilder;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#CRYPTO_GET_ACCOUNT_RECORDS}.
 */
@Singleton
public class CryptoGetAccountRecordsHandler extends PaidQueryHandler {
    private final RecordCache recordCache;
    private final CryptoFeeBuilder usageEstimator = new CryptoFeeBuilder();

    /**
     * Default constructor for injection.
     * @param recordCache the record cache to use to get the records
     */
    @Inject
    public CryptoGetAccountRecordsHandler(@NonNull final RecordCache recordCache) {
        // Exists for injection
        this.recordCache = requireNonNull(recordCache);
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.cryptoGetAccountRecordsOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = CryptoGetAccountRecordsResponse.newBuilder().header(header);
        return Response.newBuilder().cryptoGetAccountRecords(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        final var accountStore = context.createStore(ReadableAccountStore.class);
        final var query = context.query();
        final var op = query.cryptoGetAccountRecords();
        validateTruePreCheck(op != null, INVALID_TRANSACTION_BODY);

        final var account = accountStore.getAccountById(op.accountIDOrElse(AccountID.DEFAULT));
        validateTruePreCheck(account != null, INVALID_ACCOUNT_ID);

        validateFalsePreCheck(account.deleted(), ACCOUNT_DELETED);

        validateFalsePreCheck(account.smartContract(), INVALID_ACCOUNT_ID);
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);

        final var op = context.query().cryptoGetAccountRecordsOrThrow();
        final var responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        final var accountId = op.accountIDOrElse(AccountID.DEFAULT);

        // Initialize the response (with the given header)
        final var response = CryptoGetAccountRecordsResponse.newBuilder().header(header);

        if (header.nodeTransactionPrecheckCode() == OK) {
            response.accountID(accountId);

            if (responseType != COST_ANSWER) {
                final var acctRecords = recordCache.getRecords(accountId);
                response.records(acctRecords);
            }
        }

        return Response.newBuilder().cryptoGetAccountRecords(response).build();
    }

    @NonNull
    @Override
    public Fees computeFees(@NonNull final QueryContext queryContext) {
        final var query = queryContext.query();
        final var accountStore = queryContext.createStore(ReadableAccountStore.class);
        final var op = query.cryptoGetAccountRecordsOrThrow();
        final var accountId = op.accountIDOrElse(AccountID.DEFAULT);
        final var account = accountStore.getAccountById(accountId);
        final var records = recordCache.getRecords(accountId);
        return queryContext.feeCalculator().legacyCalculate(sigValueObj -> usageGivenFor(account, records));
    }

    private FeeData usageGivenFor(final Account account, List<TransactionRecord> pbjRecords) {
        if (account == null) {
            return CONSTANT_FEE_DATA;
        }
        final var records =
                pbjRecords.stream().map(CommonPbjConverters::fromPbj).toList();
        return usageEstimator.getCryptoAccountRecordsQueryFeeMatrices(records, null);
    }
}

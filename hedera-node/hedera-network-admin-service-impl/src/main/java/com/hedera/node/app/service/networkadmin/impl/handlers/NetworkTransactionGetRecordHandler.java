// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_QUERY_HEADER;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_QUERY_RES_HEADER;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_TX_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_TX_RECORD_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.FEE_MATRICES_CONST;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.STATE_PROOF_SIZE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionGetRecordQuery;
import com.hedera.hapi.node.transaction.TransactionGetRecordResponse;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Comparator;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#TRANSACTION_GET_RECORD}.
 */
// use RecordCache for this
@Singleton
public class NetworkTransactionGetRecordHandler extends PaidQueryHandler {
    @Inject
    public NetworkTransactionGetRecordHandler() {
        // Exists for injection
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.transactionGetRecordOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = TransactionGetRecordResponse.newBuilder().header(header);
        return Response.newBuilder().transactionGetRecord(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.query().transactionGetRecordOrThrow();

        // The transaction ID must be specified
        if (!op.hasTransactionID()) {
            throw new PreCheckException(INVALID_TRANSACTION_ID);
        }

        // The record must exist for that transaction ID
        final var txnId = op.transactionIDOrThrow();

        // verify that the account id exist and not default
        final var accountID = txnId.accountID();
        if (accountID == null || accountID.equals(AccountID.DEFAULT)) {
            throw new PreCheckException(INVALID_ACCOUNT_ID);
        }
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var query = context.query();
        final var recordCache = context.recordCache();
        final var op = query.transactionGetRecordOrThrow();
        final var responseBuilder = TransactionGetRecordResponse.newBuilder();
        final var transactionId = op.transactionIDOrThrow();
        final var responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        responseBuilder.header(header);
        if (header.nodeTransactionPrecheckCode() == ResponseCodeEnum.OK && responseType != COST_ANSWER) {
            final var topLevelTxnId = transactionId.nonce() > 0
                    ? transactionId.copyBuilder().nonce(0).build()
                    : transactionId;
            final var history = recordCache.getHistory(topLevelTxnId);
            if (history == null || history.records().isEmpty()) {
                // Can we even ever hit this case? Doesn't the validate method make sure this doesn't happen?
                // If we do not yet have a record, then we return this. This has some asymmetry with the call to get
                // a transaction receipt, which will return a receipt with a status of UNKNOWN if the transactionID
                // is known but there is not yet any kind of record.
                responseBuilder.header(header.copyBuilder()
                        .nodeTransactionPrecheckCode(RECORD_NOT_FOUND)
                        .build());
            } else {
                // Only top-level transactions can have children and duplicates
                if (transactionId == topLevelTxnId) {
                    // There was definitely a record, so we can return it.
                    responseBuilder.transactionRecord(history.userTransactionRecord());
                    if (op.includeDuplicates()) {
                        responseBuilder.duplicateTransactionRecords(history.duplicateRecords());
                    }
                    if (op.includeChildRecords()) {
                        // Sort the transaction records based on nonce, so that the user transaction is always first,
                        // followed by any preceding transactions, followed by any child transactions.
                        final var sortedRecords = history.childRecords().stream()
                                .sorted(Comparator.comparingLong(
                                        a -> a.transactionIDOrThrow().nonce()))
                                .toList();
                        responseBuilder.childTransactionRecords(sortedRecords);
                    }
                } else {
                    final var maybeRecord = history.childRecords().stream()
                            .filter(record -> transactionId.equals(record.transactionID()))
                            .findFirst();
                    if (maybeRecord.isEmpty()) {
                        responseBuilder.header(header.copyBuilder()
                                .nodeTransactionPrecheckCode(RECORD_NOT_FOUND)
                                .build());
                    } else {
                        responseBuilder.transactionRecord(maybeRecord.get());
                    }
                }
            }
        }

        return Response.newBuilder().transactionGetRecord(responseBuilder).build();
    }

    @NonNull
    @Override
    public Fees computeFees(@NonNull final QueryContext queryContext) {
        final RecordCache recordCache = queryContext.recordCache();
        final TransactionGetRecordQuery op = queryContext.query().transactionGetRecordOrThrow();

        // fees are the same for all records for a given response type,
        // so we calculate them once and multiply by the number of records found

        // calculate per-record fees
        final ResponseType responseType = op.headerOrThrow().responseType();
        final int stateProofSize =
                responseType == ResponseType.ANSWER_STATE_PROOF || responseType == ResponseType.COST_ANSWER_STATE_PROOF
                        ? STATE_PROOF_SIZE
                        : 0;
        final FeeComponents feeMatricesForTxNode = FeeComponents.newBuilder()
                .setConstant(FEE_MATRICES_CONST)
                .setBpt(BASIC_QUERY_HEADER + BASIC_TX_ID_SIZE)
                .setBpr(BASIC_QUERY_RES_HEADER + BASIC_TX_RECORD_SIZE + stateProofSize)
                .build();
        final FeeData perRecordFeeData = FeeData.newBuilder()
                .setNetworkdata(FeeComponents.getDefaultInstance())
                .setNodedata(feeMatricesForTxNode)
                .setServicedata(FeeComponents.getDefaultInstance())
                .build();

        int recordCount = 1;
        if (op.includeDuplicates() || op.includeChildRecords()) {
            final var history = recordCache.getHistory(op.transactionIDOrThrow());
            if (history != null) {
                recordCount += op.includeDuplicates() ? history.duplicateCount() : 0;
                recordCount += op.includeChildRecords() ? history.childRecords().size() : 0;
            }
        }

        // multiply node fees to include duplicate and/or child records
        final FeeData feeData = multiplierOfUsages(perRecordFeeData, recordCount);
        return queryContext.feeCalculator().legacyCalculate(sigValueObj -> feeData);
    }

    private static FeeData multiplierOfUsages(final FeeData feeData, final int multiplier) {
        return FeeData.newBuilder()
                .setNodedata(multiplierOfScopedUsages(feeData.getNodedata(), multiplier))
                .setNetworkdata(multiplierOfScopedUsages(feeData.getNetworkdata(), multiplier))
                .setServicedata(multiplierOfScopedUsages(feeData.getServicedata(), multiplier))
                .build();
    }

    private static FeeComponents multiplierOfScopedUsages(final FeeComponents feeComponents, final int multiplier) {
        return FeeComponents.newBuilder()
                .setMin(feeComponents.getMin())
                .setMax(feeComponents.getMax())
                .setConstant(feeComponents.getConstant() * multiplier)
                .setBpt(feeComponents.getBpt() * multiplier)
                .setVpt(feeComponents.getVpt() * multiplier)
                .setRbh(feeComponents.getRbh() * multiplier)
                .setSbh(feeComponents.getSbh() * multiplier)
                .setGas(feeComponents.getGas() * multiplier)
                .setTv(feeComponents.getTv() * multiplier)
                .setBpr(feeComponents.getBpr() * multiplier)
                .setSbpr(feeComponents.getSbpr() * multiplier)
                .build();
    }
}

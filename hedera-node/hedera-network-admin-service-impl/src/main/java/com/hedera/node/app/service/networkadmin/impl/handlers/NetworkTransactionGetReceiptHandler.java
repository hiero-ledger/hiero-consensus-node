// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.RECEIPT_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionGetReceiptResponse;
import com.hedera.node.app.spi.workflows.FreeQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#TRANSACTION_GET_RECEIPT}.
 */
// use RecordCache for this
@Singleton
public class NetworkTransactionGetReceiptHandler extends FreeQueryHandler {
    @Inject
    public NetworkTransactionGetReceiptHandler() {
        // Exists for injection
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.transactionGetReceiptOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = TransactionGetReceiptResponse.newBuilder().header(header);
        return Response.newBuilder().transactionGetReceipt(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.query().transactionGetReceiptOrThrow();

        // The transaction ID must be specified
        if (!op.hasTransactionID()) {
            throw new PreCheckException(INVALID_TRANSACTION_ID);
        }
        // And must contain both a valid start time and an account ID
        final var transactionId = op.transactionIDOrThrow();
        if (!transactionId.hasTransactionValidStart() || !transactionId.hasAccountID()) {
            throw new PreCheckException(INVALID_TRANSACTION_ID);
        }
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var query = context.query();
        final var recordCache = context.recordCache();
        final var op = query.transactionGetReceiptOrThrow();
        final var responseBuilder = TransactionGetReceiptResponse.newBuilder();
        final var transactionId = op.transactionIDOrThrow();

        responseBuilder.header(header);
        final var responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        if (header.nodeTransactionPrecheckCode() == OK && responseType != COST_ANSWER) {
            final var topLevelTxnId = transactionId.nonce() > 0
                    ? transactionId.copyBuilder().nonce(0).build()
                    : transactionId;
            final var receipts = recordCache.getReceipts(topLevelTxnId);
            if (receipts == null) {
                // We only return RECEIPT_NOT_FOUND if we have never heard of this transaction.
                responseBuilder.header(header.copyBuilder()
                        .nodeTransactionPrecheckCode(RECEIPT_NOT_FOUND)
                        .build());
            } else {
                // Only top-level transactions can have children and duplicates
                if (transactionId == topLevelTxnId) {
                    responseBuilder.receipt(receipts.priorityReceipt(topLevelTxnId));
                    if (op.includeDuplicates()) {
                        responseBuilder.duplicateTransactionReceipts(receipts.duplicateReceipts(topLevelTxnId));
                    }
                    if (op.includeChildReceipts()) {
                        responseBuilder.childTransactionReceipts(receipts.childReceipts(topLevelTxnId));
                    }
                } else {
                    final var maybeReceipt = receipts.childReceipt(transactionId);
                    if (maybeReceipt != null) {
                        responseBuilder.receipt(maybeReceipt);
                    } else {
                        responseBuilder.header(header.copyBuilder()
                                .nodeTransactionPrecheckCode(RECEIPT_NOT_FOUND)
                                .build());
                    }
                }
            }
        }

        return Response.newBuilder().transactionGetReceipt(responseBuilder).build();
    }
}

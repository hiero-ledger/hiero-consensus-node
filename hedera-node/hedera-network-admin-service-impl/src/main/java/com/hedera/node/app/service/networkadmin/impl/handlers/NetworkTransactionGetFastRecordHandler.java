// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionGetFastRecordResponse;
import com.hedera.node.app.spi.workflows.FreeQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding TRANSACTION_GET_FAST_RECORD.
 * <p>
 * This network service call is not supported. Because protobufs promise backwards compatibility,
 * we cannot remove it. However, it should not be used.
 */
@Singleton
public class NetworkTransactionGetFastRecordHandler extends FreeQueryHandler {
    @Inject
    public NetworkTransactionGetFastRecordHandler() {
        // Exists for injection
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.transactionGetFastRecordOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = TransactionGetFastRecordResponse.newBuilder().header(header);
        return Response.newBuilder().transactionGetFastRecord(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        throw new PreCheckException(NOT_SUPPORTED);
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        // this code should never be executed, as validate() should fail before we get here
        requireNonNull(context);
        requireNonNull(header);
        throw new UnsupportedOperationException(NOT_SUPPORTED.toString());
    }
}

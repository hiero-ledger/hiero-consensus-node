// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.contract.ContractGetRecordsResponse;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CONTRACT_GET_RECORDS}.
 */
@Singleton
public class ContractGetRecordsHandler extends PaidQueryHandler {
    /**
     * Default constructor for injection.
     */
    @Inject
    public ContractGetRecordsHandler() {}

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.contractGetRecordsOrThrow().header();
    }

    @Override
    // Suppress the warning that we are using deprecated class(ContractGetRecordsResponse)
    @SuppressWarnings("java:S1874")
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = ContractGetRecordsResponse.newBuilder().header(header);
        return Response.newBuilder().contractGetRecordsResponse(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        throw new PreCheckException(NOT_SUPPORTED);
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        throw new UnsupportedOperationException("Not implemented");
    }

    @NonNull
    @Override
    public Fees computeFees(@NonNull final QueryContext context) {
        return context.feeCalculator().calculate();
    }
}

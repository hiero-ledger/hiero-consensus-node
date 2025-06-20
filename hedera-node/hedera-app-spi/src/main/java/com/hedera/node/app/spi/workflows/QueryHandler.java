// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.spi.fees.Fees;
import edu.umd.cs.findbugs.annotations.NonNull;

/** A {@code QueryHandler} contains all methods for the different stages of a single query. */
public interface QueryHandler {

    /**
     * Extract the {@link QueryHeader} from a given {@link Query}
     *
     * @param query the {@link Query} that contains the header
     * @return the {@link QueryHeader} that was specified in the query
     * @throws NullPointerException if {@code query} is {@code null}
     */
    QueryHeader extractHeader(@NonNull Query query);

    /**
     * Creates an empty {@link Response} with a provided header. This is typically used, if an error occurred. The
     * {@code header} contains a {@link ResponseCodeEnum} with the error code.
     *
     * @param header the {@link ResponseHeader} that needs to be included
     * @return the created {@link Response}
     * @throws NullPointerException if {@code header} is {@code null}
     */
    Response createEmptyResponse(@NonNull ResponseHeader header);

    /**
     * Returns {@code true}, if the query associated with this handler is only requesting the cost of this query alone
     * (i.e. the query won't be executed)
     *
     * @param responseType the {@link ResponseType} of a query, because payment can depend on the response type
     * @return {@code true} if payment is required, {@code false} otherwise
     * @throws NullPointerException if {@code responseType} is {@code null}
     */
    boolean requiresNodePayment(@NonNull final ResponseType responseType);

    /**
     * Returns {@code true}, if a query associated with this handler returns only the costs
     *
     * @param responseType the {@link ResponseType} of a query, because the result can depend on the response type
     * @return {@code true} if only costs need to returned, {@code false} otherwise
     * @throws NullPointerException if {@code responseType} is {@code null}
     */
    boolean needsAnswerOnlyCost(@NonNull final ResponseType responseType);

    /**
     * Computes the fees associated with this given query. This method is called during the query workflow, before
     * validation, and used to determine whether the associated payment is sufficient, and whether the payer has
     * sufficient funds.
     *
     * @param queryContext The context for the query being handled
     * @return The fees associated with the query
     */
    @NonNull
    default Fees computeFees(@NonNull QueryContext queryContext) {
        return Fees.FREE;
    }

    /**
     * This method is called during the query workflow. It validates the query, but does not determine the response
     * yet.
     *
     * @param context the {@link QueryContext} that contains all information about the query
     * @throws NullPointerException if {@code context} is {@code null}
     * @throws PreCheckException if validation fails
     */
    void validate(@NonNull QueryContext context) throws PreCheckException;

    /**
     * This method is called during the query workflow. It determines the requested value(s) and returns the appropriate
     * response.
     *
     * @param context the {@link QueryContext} that contains all information about the query
     * @param header the {@link ResponseHeader} that should be used, if the request was successful
     * @return a {@link Response} with the requested values
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    Response findResponse(@NonNull QueryContext context, @NonNull ResponseHeader header);
}

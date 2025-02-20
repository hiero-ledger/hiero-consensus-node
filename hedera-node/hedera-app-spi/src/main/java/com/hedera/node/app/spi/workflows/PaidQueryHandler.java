// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseType;
import com.hedera.node.app.spi.fees.Fees;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An abstract class for all queries that are not free. If payment is required depends on the {@link ResponseType}
 */
public abstract class PaidQueryHandler implements QueryHandler {

    @Override
    public boolean requiresNodePayment(@NonNull final ResponseType responseType) {
        requireNonNull(responseType);
        return responseType == ANSWER_ONLY || responseType == ANSWER_STATE_PROOF;
    }

    @Override
    public boolean needsAnswerOnlyCost(@NonNull final ResponseType responseType) {
        requireNonNull(responseType);
        return COST_ANSWER == responseType;
    }

    @NonNull
    @Override
    public abstract Fees computeFees(@NonNull final QueryContext queryContext);
}

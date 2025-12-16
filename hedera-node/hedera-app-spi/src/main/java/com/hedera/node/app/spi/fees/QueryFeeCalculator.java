// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;

public interface QueryFeeCalculator {
    /**
     * Accumulated service fees as a side effect into the given fee result. This will be implemented by every
     * single handler's fee calculator.
     *
     * @param query the query body
     * @param queryContext the query state
     * @param feeResult the fee result
     * @param feeSchedule the fee schedule
     */
    void accumulateNodePayment(
            @NonNull Query query,
            @Nullable QueryContext queryContext,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule);
    /**
     * Returns the query type this calculator is for.
     * @return the query type
     */
    Query.QueryOneOfType getQueryType();
}

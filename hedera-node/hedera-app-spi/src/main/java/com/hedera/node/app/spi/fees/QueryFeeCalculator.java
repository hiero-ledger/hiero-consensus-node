// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee;

import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public interface QueryFeeCalculator {
    /**
     * Accumulate query fees using only the transaction body (no state access).
     *
     * @param query        the query body
     * @param feeResult    the fee result
     * @param feeSchedule  the fee schedule
     */
    void accumulateNodePayment(@NonNull Query query, @NonNull FeeResult feeResult, @NonNull FeeSchedule feeSchedule);

    /**
     * Accumulate query fees using the transaction body and query context. State access allowed.
     *
     * @param query        the query body
     * @param feeResult    the fee result
     * @param feeSchedule  the fee schedule
     */
    default void accumulateNodePayment(
            @NonNull Query query,
            @Nullable QueryContext queryContext,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule) {
        accumulateNodePayment(query, feeResult, feeSchedule);
    }
    /**
     * Returns the query type this calculator is for.
     * @return the query type
     */
    Query.QueryOneOfType getQueryType();

    /**
     * Adds an extra fee to the result.
     *
     * @param result the fee result
     * @param serviceFeeDef the service fee definition containing operation-specific extras with includedCount
     * @param extra the extra fee
     * @param feeSchedule the fee schedule
     * @param amount the amount of the extra fee
     */
    default void addExtraFee(
            @NonNull final FeeResult result,
            @NonNull final ServiceFeeDefinition serviceFeeDef,
            @NonNull final Extra extra,
            @NonNull FeeSchedule feeSchedule,
            final long amount) {
        for (ExtraFeeReference ref : serviceFeeDef.extras()) {
            if (ref.name() == extra) {
                int included = ref.includedCount();
                long extraFee = lookupExtraFee(feeSchedule, ref.name()).fee();
                if (amount > included) {
                    final long overage = amount - included;
                    result.addServiceFee(overage, extraFee);
                }
            }
        }
    }
}

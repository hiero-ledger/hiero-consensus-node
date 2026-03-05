// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee;

import com.hedera.hapi.node.transaction.Query;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public interface QueryFeeCalculator {
    /**
     * Accumulated service fees as a side effect into the given fee result. This will be implemented by every
     * single handler's fee calculator.
     *
     * @param query the query body
     * @param simpleFeeContext the query state
     * @param feeResult the fee result
     * @param feeSchedule the fee schedule
     */
    void accumulateNodePayment(
            @NonNull Query query,
            @NonNull SimpleFeeContext simpleFeeContext,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule);
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
                result.addServiceExtraFeeTinycents(ref.name().name(), extraFee, amount, included);
            }
        }
    }
}

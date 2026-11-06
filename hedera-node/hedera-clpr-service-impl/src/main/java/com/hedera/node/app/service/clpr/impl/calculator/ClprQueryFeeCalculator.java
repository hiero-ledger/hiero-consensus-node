// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.calculator;

import static com.hedera.node.app.service.clpr.impl.calculator.ClprFeeCalculator.VERIFIER_FEE_GAS;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;

/**
 * A fee calculator for the CLPR queries, each of which builds a state proof and verifies its TSS signature. Besides
 * its base fee, each is charged {@link Extra#GAS} at {@link ClprFeeCalculator#VERIFIER_FEE_GAS} for that
 * verification, when the fee schedule lists the extra for the query.
 *
 * @param functionality the CLPR query functionality for fee schedule lookup
 * @param queryType the query discriminant this calculator handles
 */
public record ClprQueryFeeCalculator(
        @NonNull HederaFunctionality functionality, @NonNull Query.QueryOneOfType queryType)
        implements QueryFeeCalculator {
    @Override
    public void accumulateNodePayment(
            @NonNull final Query query,
            @Nullable final SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final var serviceDef = lookupServiceFee(feeSchedule, functionality);
        if (serviceDef == null) {
            return;
        }
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
        addExtraFee(feeResult, serviceDef, Extra.GAS, feeSchedule, VERIFIER_FEE_GAS);
    }

    @Override
    public Query.QueryOneOfType getQueryType() {
        return queryType;
    }
}

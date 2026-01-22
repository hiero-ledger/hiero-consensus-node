// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/**
 * Fee calculator for {@link HederaFunctionality#TRANSACTION_GET_RECEIPT} queries.
 * <p>
 * Note: This is a free query (extends FreeQueryHandler), but we still provide
 * a calculator for completeness in the simple fees system.
 */
public class TransactionGetReceiptFeeCalculator implements QueryFeeCalculator {
    @Override
    public void accumulateNodePayment(
            @NonNull Query query,
            @NonNull SimpleFeeContext simpleFeeContext,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule) {
        final ServiceFeeDefinition serviceDef =
                lookupServiceFee(feeSchedule, HederaFunctionality.TRANSACTION_GET_RECEIPT);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
    }

    @Override
    public Query.QueryOneOfType getQueryType() {
        return Query.QueryOneOfType.TRANSACTION_GET_RECEIPT;
    }
}

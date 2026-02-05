// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.RECORDS;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/**
 * Fee calculator for {@link HederaFunctionality#TRANSACTION_GET_RECORD} queries.
 */
public class TransactionGetRecordFeeCalculator implements QueryFeeCalculator {
    @Override
    public void accumulateNodePayment(
            @NonNull Query query,
            @NonNull SimpleFeeContext simpleFeeContext,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule) {
        final var queryContext = simpleFeeContext.queryContext();
        final ServiceFeeDefinition serviceDef =
                lookupServiceFee(feeSchedule, HederaFunctionality.TRANSACTION_GET_RECORD);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());

        if (queryContext != null) {
            final var recordCache = queryContext.recordCache();
            final var op = queryContext.query().transactionGetRecordOrThrow();
            int recordCount = 1;
            if (op.includeDuplicates() || op.includeChildRecords()) {
                final var history = recordCache.getHistory(op.transactionIDOrThrow());
                if (history != null) {
                    recordCount += op.includeDuplicates() ? history.duplicateCount() : 0;
                    recordCount +=
                            op.includeChildRecords() ? history.childRecords().size() : 0;
                }
            }
            addExtraFee(feeResult, serviceDef, RECORDS, feeSchedule, recordCount);
        }
    }

    @Override
    public Query.QueryOneOfType getQueryType() {
        return Query.QueryOneOfType.TRANSACTION_GET_RECORD;
    }
}

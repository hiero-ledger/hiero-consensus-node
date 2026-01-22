// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class ConsensusGetTopicInfoFeeCalculator implements QueryFeeCalculator {
    @Override
    public void accumulateNodePayment(
            @NonNull Query query,
            @Nullable SimpleFeeContext simpleFeeContext,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule) {
        final ServiceFeeDefinition serviceDef =
                lookupServiceFee(feeSchedule, HederaFunctionality.CONSENSUS_GET_TOPIC_INFO);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
    }

    @Override
    public Query.QueryOneOfType getQueryType() {
        return Query.QueryOneOfType.CONSENSUS_GET_TOPIC_INFO;
    }
}

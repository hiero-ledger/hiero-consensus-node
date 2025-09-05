// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees.apis.common;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.ExchangeRate;
import java.security.InvalidParameterException;
import java.util.Map;
import org.hiero.hapi.fees.FeeModel;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class BaseFeeModel implements FeeModel {
    private final HederaFunctionality api;
    private final String description;

    public BaseFeeModel(HederaFunctionality api, String description) {
        this.api = api;
        this.description = description;
    }

    @Override
    public HederaFunctionality getApi() {
        return this.api;
    }

    @Override
    public String getDescription() {
        return this.description;
    }

    @Override
    public FeeResult computeFee(Map<String, Object> params, ExchangeRate exchangeRate, FeeSchedule feeSchedule) {
        var result = new FeeResult();
        result.addServiceFee(
                "Base Fee for " + this.getApi(),
                1,
                lookupServiceFee(feeSchedule, this.api).baseFee());

        ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, this.api);
        for (ExtraFeeReference ref : serviceDef.extras()) {
            if (!params.containsKey(ref.name().name())) {
                throw new InvalidParameterException(
                        "input params missing " + ref.name() + " required by method " + this.api);
            }
            int included = ref.includedCount();
            long used = (long) params.get(ref.name().name());
            long extraFee = lookupExtraFee(feeSchedule, ref).fee();
            if (used > included) {
                final long overage = used - included;
                result.addServiceFee("Overage of " + ref.name().name(), overage, overage * extraFee);
            }
        }

        final var nodeFee = feeSchedule.node();
        result.addNodeFee("Node base fee", 1, nodeFee.baseFee());
        long total_node_fee = 0 + nodeFee.baseFee();
        for (ExtraFeeReference ref : nodeFee.extras()) {
            if (!params.containsKey(ref.name().name())) {
                throw new InvalidParameterException("input params missing " + ref.name() + " required by node fee ");
            }
            int included = ref.includedCount();
            long used = (long) params.get(ref.name().name());
            long extraFee = lookupExtraFee(feeSchedule, ref).fee();
            if (used > included) {
                final long overage = used - included;
                result.addNodeFee("Node Overage of " + ref.name().name(), overage, overage * extraFee);
                total_node_fee += overage * extraFee;
            }
        }

        int multiplier = feeSchedule.network().multiplier();
        result.addNetworkFee("Total Network fee", multiplier, total_node_fee * multiplier);

        return result;
    }
}

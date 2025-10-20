// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees.apis.common;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import java.security.InvalidParameterException;
import java.util.Map;
import org.hiero.hapi.fees.FeeModel;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;

public abstract class AbstractBaseFeeModel implements FeeModel {
    private final HederaFunctionality api;
    private final String description;

    public AbstractBaseFeeModel(HederaFunctionality api, String description) {
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

    protected FeeResult computeNodeAndNetworkFees(Map<Extra, Object> params, FeeSchedule feeSchedule) {
        var result = new FeeResult();
        final var nodeFee = feeSchedule.node();
        result.addNodeFee("Node base fee", 1, nodeFee.baseFee());
        for (ExtraFeeReference ref : nodeFee.extras()) {
            if (!params.containsKey(ref.name())) {
                throw new InvalidParameterException("input params missing " + ref.name() + " required by node fee ");
            }
            int included = ref.includedCount();
            long used = (long) params.get(ref.name());
            long extraFee = lookupExtraFee(feeSchedule, ref).fee();
            if (used > included) {
                final long overage = used - included;
                result.addNodeFee("Node Overage of " + ref.name().name(), overage, overage * extraFee);
            }
        }

        int multiplier = feeSchedule.network().multiplier();
        result.addNetworkFee("Total Network fee", multiplier, result.node * multiplier);
        return result;
    }
}

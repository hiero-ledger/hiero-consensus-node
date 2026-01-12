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

    protected FeeResult computeNodeAndNetworkFees(Map<Extra, Long> params, FeeSchedule feeSchedule) {
        var result = new FeeResult();
        final var nodeFee = feeSchedule.node();
        result.addNodeBase(nodeFee.baseFee());
        for (ExtraFeeReference ref : nodeFee.extras()) {
            if (!params.containsKey(ref.name())) {
                throw new InvalidParameterException("input params missing " + ref.name() + " required by node fee ");
            }
            int included = ref.includedCount();
            long used = (long) params.get(ref.name());
            long extraFee = lookupExtraFee(feeSchedule, ref.name()).fee();
            if (used > included) {
                final long overage = used - included;
                result.addNodeExtra(ref.name().name(), extraFee, used, included, overage);
            }
        }

        int multiplier = feeSchedule.network().multiplier();
        result.addNetworkFee(multiplier, result.node);
        return result;
    }
}

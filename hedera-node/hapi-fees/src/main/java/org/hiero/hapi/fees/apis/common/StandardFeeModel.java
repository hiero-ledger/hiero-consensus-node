// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees.apis.common;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import java.security.InvalidParameterException;
import java.util.Map;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class StandardFeeModel extends AbstractBaseFeeModel {

    public StandardFeeModel(String api, String description) {
        super(api, description);
    }

    @Override
    public FeeResult computeFee(Map<Extra, Long> params, FeeSchedule feeSchedule) {
        var result = this.computeNodeAndNetworkFees(params, feeSchedule);
        result.addServiceFee(1, lookupServiceFee(feeSchedule, this.getApi()).baseFee());

        ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, this.getApi());
        for (ExtraFeeReference ref : serviceDef.extras()) {
            if (!params.containsKey(ref.name())) {
                throw new InvalidParameterException(
                        "input params missing " + ref.name() + " required by method " + this.getApi());
            }
            int included = ref.includedCount();
            long used = (long) params.get(ref.name());
            long extraFee = lookupExtraFee(feeSchedule, ref.name()).fee();
            if (used > included) {
                final long overage = used - included;
                result.addServiceFee(overage, extraFee);
            }
        }
        return result;
    }
}

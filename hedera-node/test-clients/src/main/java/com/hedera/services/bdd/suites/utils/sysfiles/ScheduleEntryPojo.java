// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.TransactionFeeSchedule;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScheduleEntryPojo {
    String hederaFunctionality;
    List<ScopedResourcePricesPojo> fees;

    public static ScheduleEntryPojo from(TransactionFeeSchedule grpc) {
        var pojo = new ScheduleEntryPojo();

        pojo.setHederaFunctionality(grpc.getHederaFunctionality().toString());

        List<ScopedResourcePricesPojo> feesList = new ArrayList<>();

        for (FeeData feeData : grpc.getFeesList()) {
            var subType = feeData.getSubType();
            var nodePrices = ResourcePricesPojo.from(feeData.getNodedata());
            var servicePrices = ResourcePricesPojo.from(feeData.getServicedata());
            var networkPrices = ResourcePricesPojo.from(feeData.getNetworkdata());

            var scopedPrices = new ScopedResourcePricesPojo();
            scopedPrices.setNodedata(nodePrices);
            scopedPrices.setNetworkdata(networkPrices);
            scopedPrices.setServicedata(servicePrices);
            scopedPrices.setSubType(subType);

            feesList.add(scopedPrices);
        }

        pojo.setFees(feesList);
        return pojo;
    }

    public String getHederaFunctionality() {
        return hederaFunctionality;
    }

    public void setHederaFunctionality(String hederaFunctionality) {
        this.hederaFunctionality = hederaFunctionality;
    }

    public List<ScopedResourcePricesPojo> getFees() {
        return fees;
    }

    public void setFees(List<ScopedResourcePricesPojo> feeData) {
        this.fees = feeData;
    }
}

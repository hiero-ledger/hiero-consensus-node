// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.fees;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.FEE_MATRICES_CONST;

import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;

public class AdapterUtils {
    public static FeeData feeDataFrom(UsageAccumulator usage) {
        var usages = FeeData.newBuilder();

        var network = FeeComponents.newBuilder()
                .setConstant(FEE_MATRICES_CONST)
                .setBpt(usage.getUniversalBpt())
                .setVpt(usage.getNetworkVpt())
                .setRbh(usage.getNetworkRbh());
        var node = FeeComponents.newBuilder()
                .setConstant(FEE_MATRICES_CONST)
                .setBpt(usage.getUniversalBpt())
                .setVpt(usage.getNodeVpt())
                .setBpr(usage.getNodeBpr())
                .setSbpr(usage.getNodeSbpr());
        var service = FeeComponents.newBuilder()
                .setConstant(FEE_MATRICES_CONST)
                .setRbh(usage.getServiceRbh())
                .setTv(usage.getServiceTv())
                .setSbh(usage.getServiceSbh());
        return usages.setNetworkdata(network)
                .setNodedata(node)
                .setServicedata(service)
                .build();
    }
}

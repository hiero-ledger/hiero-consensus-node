// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.test;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.FEE_MATRICES_CONST;

import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;

public final class AdapterUtils {
    public static FeeData feeDataFrom(final UsageAccumulator usage) {
        final var usages = FeeData.newBuilder();

        final var network = FeeComponents.newBuilder()
                .setConstant(FEE_MATRICES_CONST)
                .setBpt(usage.getUniversalBpt())
                .setVpt(usage.getNetworkVpt())
                .setRbh(usage.getNetworkRbh());
        final var node = FeeComponents.newBuilder()
                .setConstant(FEE_MATRICES_CONST)
                .setBpt(usage.getUniversalBpt())
                .setVpt(usage.getNodeVpt())
                .setBpr(usage.getNodeBpr())
                .setSbpr(usage.getNodeSbpr());
        final var service = FeeComponents.newBuilder()
                .setConstant(FEE_MATRICES_CONST)
                .setRbh(usage.getServiceRbh())
                .setSbh(usage.getServiceSbh());
        return usages.setNetworkdata(network)
                .setNodedata(node)
                .setServicedata(service)
                .build();
    }
}

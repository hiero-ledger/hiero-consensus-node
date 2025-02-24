// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.test;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.FEE_MATRICES_CONST;

import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;

public final class AdapterUtils {
    public static FeeData feeDataFrom(final UsageAccumulator usage) {
        final var usages = FeeData.newBuilder();

        final var network = FeeComponents.newBuilder()
                .constant(FEE_MATRICES_CONST)
                .bpt(usage.getUniversalBpt())
                .vpt(usage.getNetworkVpt())
                .rbh(usage.getNetworkRbh());
        final var node = FeeComponents.newBuilder()
                .constant(FEE_MATRICES_CONST)
                .bpt(usage.getUniversalBpt())
                .vpt(usage.getNodeVpt())
                .bpr(usage.getNodeBpr())
                .sbpr(usage.getNodeSbpr());
        final var service = FeeComponents.newBuilder()
                .constant(FEE_MATRICES_CONST)
                .rbh(usage.getServiceRbh())
                .sbh(usage.getServiceSbh());
        return usages.networkdata(network).nodedata(node).servicedata(service).build();
    }
}

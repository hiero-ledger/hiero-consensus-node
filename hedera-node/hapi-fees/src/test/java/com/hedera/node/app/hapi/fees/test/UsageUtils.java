// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.test;

import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;

public class UsageUtils {
    public static final int NUM_PAYER_KEYS = 2;

    public static final long ONE = 1;
    public static final long BPT = 2;
    public static final long VPT = 3;
    public static final long RBH = 4;
    public static final long SBH = 5;
    public static final long GAS = 6;
    public static final long TV = 7;
    public static final long BPR = 8;
    public static final long SBPR = 9;
    public static final long NETWORK_RBH = 10;

    public static final FeeComponents A_USAGE_VECTOR = FeeComponents.newBuilder()
            .constant(ONE)
            .bpt(BPT)
            .vpt(VPT)
            .rbh(RBH)
            .sbh(SBH)
            .gas(GAS)
            .tv(TV)
            .bpr(BPR)
            .sbpr(SBPR)
            .build();

    public static final FeeData A_USAGES_MATRIX;
    public static final FeeData A_QUERY_USAGES_MATRIX;

    static {
        var usagesBuilder = FeeData.newBuilder();
        usagesBuilder.networkdata(
                FeeComponents.newBuilder().constant(ONE).bpt(BPT).vpt(VPT).rbh(NETWORK_RBH));
        usagesBuilder.nodedata(FeeComponents.newBuilder()
                .constant(ONE)
                .bpt(BPT)
                .vpt(NUM_PAYER_KEYS)
                .bpr(BPR)
                .sbpr(SBPR));
        usagesBuilder.servicedata(
                FeeComponents.newBuilder().constant(ONE).rbh(RBH).sbh(SBH).tv(TV));
        A_USAGES_MATRIX = usagesBuilder.build();

        usagesBuilder = FeeData.newBuilder();
        usagesBuilder.nodedata(
                FeeComponents.newBuilder().constant(ONE).bpt(BPT).sbpr(SBPR).bpr(BPR));
        A_QUERY_USAGES_MATRIX = usagesBuilder.build();
    }
}

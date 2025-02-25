// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.test;

import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;

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
            .setConstant(ONE)
            .setBpt(BPT)
            .setVpt(VPT)
            .setRbh(RBH)
            .setSbh(SBH)
            .setGas(GAS)
            .setTv(TV)
            .setBpr(BPR)
            .setSbpr(SBPR)
            .build();

    public static final FeeData A_USAGES_MATRIX;
    public static final FeeData A_QUERY_USAGES_MATRIX;

    static {
        var usagesBuilder = FeeData.newBuilder();
        usagesBuilder.setNetworkdata(FeeComponents.newBuilder()
                .setConstant(ONE)
                .setBpt(BPT)
                .setVpt(VPT)
                .setRbh(NETWORK_RBH));
        usagesBuilder.setNodedata(FeeComponents.newBuilder()
                .setConstant(ONE)
                .setBpt(BPT)
                .setVpt(NUM_PAYER_KEYS)
                .setBpr(BPR)
                .setSbpr(SBPR));
        usagesBuilder.setServicedata(FeeComponents.newBuilder()
                .setConstant(ONE)
                .setRbh(RBH)
                .setSbh(SBH)
                .setTv(TV));
        A_USAGES_MATRIX = usagesBuilder.build();

        usagesBuilder = FeeData.newBuilder();
        usagesBuilder.setNodedata(FeeComponents.newBuilder()
                .setConstant(ONE)
                .setBpt(BPT)
                .setSbpr(SBPR)
                .setBpr(BPR));
        A_QUERY_USAGES_MATRIX = usagesBuilder.build();
    }
}

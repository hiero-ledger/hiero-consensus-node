// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.domain.throttling;

import com.hederahashgraph.api.proto.java.HederaFunctionality;

public class HapiThrottleUtils {
    public static ThrottleBucket<HederaFunctionality> hapiBucketFromProto(
            final com.hederahashgraph.api.proto.java.ThrottleBucket bucket) {
        return new ThrottleBucket<>(
                bucket.getBurstPeriodMs(),
                bucket.getName(),
                bucket.getThrottleGroupsList().stream()
                        .map(HapiThrottleUtils::hapiGroupFromProto)
                        .toList());
    }

    public static com.hederahashgraph.api.proto.java.ThrottleBucket hapiBucketToProto(
            final ThrottleBucket<HederaFunctionality> bucket) {
        return com.hederahashgraph.api.proto.java.ThrottleBucket.newBuilder()
                .setName(bucket.getName())
                .setBurstPeriodMs(bucket.impliedBurstPeriodMs())
                .addAllThrottleGroups(bucket.getThrottleGroups().stream()
                        .map(HapiThrottleUtils::hapiGroupToProto)
                        .toList())
                .build();
    }

    public static ThrottleGroup<HederaFunctionality> hapiGroupFromProto(
            final com.hederahashgraph.api.proto.java.ThrottleGroup group) {
        return new ThrottleGroup<>(group.getMilliOpsPerSec(), group.getOperationsList());
    }

    public static com.hederahashgraph.api.proto.java.ThrottleGroup hapiGroupToProto(
            final ThrottleGroup<HederaFunctionality> group) {
        return com.hederahashgraph.api.proto.java.ThrottleGroup.newBuilder()
                .setMilliOpsPerSec(group.impliedMilliOpsPerSec())
                .addAllOperations(group.getOperations())
                .build();
    }

    private HapiThrottleUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }
}

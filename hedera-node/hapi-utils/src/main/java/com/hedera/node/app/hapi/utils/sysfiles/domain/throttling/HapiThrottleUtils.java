// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.domain.throttling;

import com.hedera.hapi.node.base.HederaFunctionality;

public class HapiThrottleUtils {
    public static ThrottleBucket<HederaFunctionality> hapiBucketFromProto(
            final com.hedera.hapi.node.transaction.ThrottleBucket bucket) {
        return new ThrottleBucket<>(
                bucket.burstPeriodMs(),
                bucket.name(),
                bucket.throttleGroups().stream()
                        .map(HapiThrottleUtils::hapiGroupFromProto)
                        .toList());
    }

    public static com.hedera.hapi.node.transaction.ThrottleBucket hapiBucketToProto(
            final ThrottleBucket<HederaFunctionality> bucket) {
        return com.hedera.hapi.node.transaction.ThrottleBucket.newBuilder()
                .name(bucket.getName())
                .burstPeriodMs(bucket.impliedBurstPeriodMs())
                .throttleGroups(bucket.getThrottleGroups().stream()
                        .map(HapiThrottleUtils::hapiGroupToProto)
                        .toList())
                .build();
    }

    public static ThrottleGroup<HederaFunctionality> hapiGroupFromProto(
            final com.hedera.hapi.node.transaction.ThrottleGroup group) {
        return new ThrottleGroup<>(group.milliOpsPerSec(), group.operations());
    }

    public static com.hedera.hapi.node.transaction.ThrottleGroup hapiGroupToProto(
            final ThrottleGroup<HederaFunctionality> group) {
        return com.hedera.hapi.node.transaction.ThrottleGroup.newBuilder()
                .milliOpsPerSec(group.impliedMilliOpsPerSec())
                .operations(group.getOperations())
                .build();
    }

    private HapiThrottleUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.domain.throttling;

import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;

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

    /**
     * Computes the least common multiple of the given two numbers.
     *
     * @param lhs the first number
     * @param rhs the second number
     * @return the least common multiple of {@code a} and {@code b}
     * @throws ArithmeticException if the result overflows a {@code long}
     */
    public static long lcm(final long lhs, final long rhs) {
        if (productWouldOverflow(lhs, rhs)) {
            throw new ArithmeticException();
        }
        return (lhs * rhs) / gcd(Math.min(lhs, rhs), Math.max(lhs, rhs));
    }

    private static long gcd(final long lhs, final long rhs) {
        return (lhs == 0) ? rhs : gcd(rhs % lhs, lhs);
    }

    private HapiThrottleUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }
}

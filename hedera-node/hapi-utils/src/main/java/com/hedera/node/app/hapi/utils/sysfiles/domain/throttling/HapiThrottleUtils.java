// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.domain.throttling;

import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;

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

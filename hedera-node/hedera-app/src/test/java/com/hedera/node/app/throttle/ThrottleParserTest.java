// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.OPERATION_REPEATED_IN_BUCKET_GROUPS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS_BUT_MISSING_EXPECTED_OPERATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.THROTTLE_GROUP_HAS_ZERO_OPS_PER_SEC;
import static com.hedera.hapi.node.base.ResponseCodeEnum.THROTTLE_GROUP_LCM_OVERFLOW;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNPARSEABLE_THROTTLE_DEFINITIONS;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.ThrottleBucket;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.hapi.node.transaction.ThrottleGroup;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThrottleParserTest {

    ThrottleGroup throttleGroup = ThrottleGroup.newBuilder()
            .operations(ThrottleParser.EXPECTED_OPS.stream().toList())
            .milliOpsPerSec(100)
            .build();

    ThrottleBucket throttleBucket = ThrottleBucket.newBuilder()
            .name("throttle1")
            .burstPeriodMs(100L)
            .throttleGroups(throttleGroup)
            .build();

    ThrottleGroup throttleGroup2 = ThrottleGroup.newBuilder()
            .operations(List.of(HederaFunctionality.CONTRACT_CREATE))
            .milliOpsPerSec(120)
            .build();

    ThrottleBucket throttleBucket2 = ThrottleBucket.newBuilder()
            .name("throttle2")
            .burstPeriodMs(120L)
            .throttleGroups(throttleGroup2)
            .build();

    ThrottleDefinitions throttleDefinitions = ThrottleDefinitions.newBuilder()
            .throttleBuckets(throttleBucket, throttleBucket2)
            .build();
    Bytes throttleDefinitionsByes = ThrottleDefinitions.PROTOBUF.toBytes(throttleDefinitions);

    // Only throttleBucket2 (CONTRACT_CREATE only) — missing most expected ops
    Bytes partialThrottleDefinitionBytes = ThrottleDefinitions.PROTOBUF.toBytes(
            ThrottleDefinitions.newBuilder().throttleBuckets(throttleBucket2).build());

    private ThrottleParser subject;

    @BeforeEach
    void setUp() {
        subject = new ThrottleParser();
    }

    @Test
    void parseWithAllExpectedOps_returnsSuccess() {
        final var result = subject.parse(throttleDefinitionsByes);

        assertEquals(SUCCESS, result.successStatus());
        assertEquals(throttleDefinitions, result.throttleDefinitions());
    }

    @Test
    void parseWithMissingExpectedOps_returnsMissingExpectedOperationStatus() {
        final var result = subject.parse(partialThrottleDefinitionBytes);

        assertEquals(SUCCESS_BUT_MISSING_EXPECTED_OPERATION, result.successStatus());
    }

    @Test
    void parseWithInvalidBytes_throwsUnparseableThrottleDefinitions() {
        assertThatThrownBy(() -> subject.parse(Bytes.wrap(new byte[] {0x06})))
                .isInstanceOf(HandleException.class)
                .has(responseCode(UNPARSEABLE_THROTTLE_DEFINITIONS));
    }

    @Test
    void parseWithZeroOpsPerSec_throwsThrottleGroupHasZeroOpsPerSec() {
        final var zeroOpsGroup = ThrottleGroup.newBuilder()
                .operations(ThrottleParser.EXPECTED_OPS.stream().toList())
                .milliOpsPerSec(0)
                .build();
        final var bucket = ThrottleBucket.newBuilder()
                .name("bucket")
                .burstPeriodMs(100L)
                .throttleGroups(zeroOpsGroup)
                .build();
        final var bytes = ThrottleDefinitions.PROTOBUF.toBytes(
                ThrottleDefinitions.newBuilder().throttleBuckets(bucket).build());

        assertThatThrownBy(() -> subject.parse(bytes))
                .isInstanceOf(HandleException.class)
                .has(responseCode(THROTTLE_GROUP_HAS_ZERO_OPS_PER_SEC));
    }

    @Test
    void parseWithRepeatedOperationsInBucket_throwsOperationRepeatedInBucketGroups() {
        final var group1 = ThrottleGroup.newBuilder()
                .operations(List.of(HederaFunctionality.CRYPTO_CREATE))
                .milliOpsPerSec(100)
                .build();
        final var group2 = ThrottleGroup.newBuilder()
                .operations(List.of(HederaFunctionality.CRYPTO_CREATE))
                .milliOpsPerSec(120)
                .build();
        final var bucket = ThrottleBucket.newBuilder()
                .name("bucket")
                .burstPeriodMs(100L)
                .throttleGroups(group1, group2)
                .build();
        final var bytes = ThrottleDefinitions.PROTOBUF.toBytes(
                ThrottleDefinitions.newBuilder().throttleBuckets(bucket).build());

        assertThatThrownBy(() -> subject.parse(bytes))
                .isInstanceOf(HandleException.class)
                .has(responseCode(OPERATION_REPEATED_IN_BUCKET_GROUPS));
    }

    @Test
    void parseWithLcmOverflow_throwsThrottleGroupLcmOverflow() {
        final var group1 = ThrottleGroup.newBuilder()
                .operations(List.of(HederaFunctionality.CRYPTO_CREATE))
                .milliOpsPerSec(Long.MAX_VALUE / 2)
                .build();
        final var group2 = ThrottleGroup.newBuilder()
                .operations(List.of(HederaFunctionality.CRYPTO_TRANSFER))
                .milliOpsPerSec(Long.MAX_VALUE / 3)
                .build();
        final var bucket = ThrottleBucket.newBuilder()
                .name("bucket")
                .burstPeriodMs(100L)
                .throttleGroups(group1, group2)
                .build();
        final var bytes = ThrottleDefinitions.PROTOBUF.toBytes(
                ThrottleDefinitions.newBuilder().throttleBuckets(bucket).build());

        assertThatThrownBy(() -> subject.parse(bytes))
                .isInstanceOf(HandleException.class)
                .has(responseCode(THROTTLE_GROUP_LCM_OVERFLOW));
    }
}

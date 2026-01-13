// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.domain.throttling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.hapi.utils.TestUtils;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ThrottleBucket;
import com.hederahashgraph.api.proto.java.ThrottleGroup;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class HapiThrottleUtilsTest {
    @Test
    void factoryWorks() throws IOException {
        final var proto = TestUtils.protoDefs("bootstrap/throttles.json");

        final var bucketA = proto.getThrottleBuckets(0);

        final var fromResult = HapiThrottleUtils.hapiBucketFromProto(bucketA);
        final var fromToResult = HapiThrottleUtils.hapiBucketToProto(fromResult);
        assertEquals(bucketA, fromToResult);
    }

    @Test
    void highVolumeFieldIsPreservedInRoundTrip() {
        final var throttleGroup = ThrottleGroup.newBuilder()
                .addOperations(HederaFunctionality.CryptoCreate)
                .setMilliOpsPerSec(1000)
                .build();

        final var highVolumeBucket = ThrottleBucket.newBuilder()
                .setName("HighVolumeBucket")
                .setBurstPeriodMs(1000)
                .addThrottleGroups(throttleGroup)
                .setHighVolume(true)
                .build();

        final var normalBucket = ThrottleBucket.newBuilder()
                .setName("NormalBucket")
                .setBurstPeriodMs(1000)
                .addThrottleGroups(throttleGroup)
                .setHighVolume(false)
                .build();

        // Test high volume bucket
        final var highVolumeFromProto = HapiThrottleUtils.hapiBucketFromProto(highVolumeBucket);
        assertTrue(highVolumeFromProto.isHighVolume());
        final var highVolumeToProto = HapiThrottleUtils.hapiBucketToProto(highVolumeFromProto);
        assertEquals(highVolumeBucket, highVolumeToProto);

        // Test normal bucket
        final var normalFromProto = HapiThrottleUtils.hapiBucketFromProto(normalBucket);
        assertFalse(normalFromProto.isHighVolume());
        final var normalToProto = HapiThrottleUtils.hapiBucketToProto(normalFromProto);
        assertEquals(normalBucket, normalToProto);
    }
}

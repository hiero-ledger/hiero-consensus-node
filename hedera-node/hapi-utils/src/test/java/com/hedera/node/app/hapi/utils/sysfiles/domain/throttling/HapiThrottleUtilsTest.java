// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.domain.throttling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.utils.TestUtils;
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
}

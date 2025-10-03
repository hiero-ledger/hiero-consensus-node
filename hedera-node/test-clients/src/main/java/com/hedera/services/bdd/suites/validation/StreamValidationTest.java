// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.validation;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateStreams;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag("STREAM_VALIDATION")
// Ordered to come after any other HapiTest that runs in a PR check
@Order(Integer.MAX_VALUE)
public class StreamValidationTest {
    @LeakyHapiTest
    final Stream<DynamicTest> streamsAreValid() {
        // Ensure we don't trigger any stake rebalancing that could interfere with later record-derived validation
        HapiSpec.setStakerIds(null);
        return hapiTest(validateStreams());
    }
}

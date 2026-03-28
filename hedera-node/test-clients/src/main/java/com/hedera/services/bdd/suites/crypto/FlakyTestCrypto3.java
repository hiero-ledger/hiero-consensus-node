// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class FlakyTestCrypto3 {

    private static final AtomicInteger attemptCount = new AtomicInteger(0);

    @HapiTest
    final Stream<DynamicTest> flakyTestCrypto3() {
        return hapiTest(withOpContext((spec, opLog) -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt < 3) {
                throw new AssertionError("Intentional failure on attempt " + attempt + " (passes on attempt 3)");
            }
        }));
    }
}

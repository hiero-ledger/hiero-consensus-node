// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;

import com.hedera.services.bdd.junit.HapiTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class FlakyTestCrypto3 {

    private static final Path ATTEMPT_FILE =
            Path.of(System.getProperty("java.io.tmpdir"), "FlakyTestCrypto3_attempt.txt");

    @HapiTest
    final Stream<DynamicTest> flakyTestCrypto3() {
        return hapiTest(withOpContext((spec, opLog) -> {
            int attempt = readAndIncrementAttempt();
            if (attempt < 3) {
                throw new AssertionError("Intentional failure on attempt " + attempt + " (passes on attempt 3)");
            }
        }));
    }

    private static int readAndIncrementAttempt() throws IOException {
        int current = 1;
        if (Files.exists(ATTEMPT_FILE)) {
            current = Integer.parseInt(Files.readString(ATTEMPT_FILE).trim()) + 1;
        }
        Files.writeString(
                ATTEMPT_FILE, String.valueOf(current), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return current;
    }
}

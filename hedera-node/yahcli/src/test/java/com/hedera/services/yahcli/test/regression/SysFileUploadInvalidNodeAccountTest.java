// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.regression;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliSysFiles;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
public class SysFileUploadInvalidNodeAccountTest {

    private static final String UPDATE_FILE_LOCATION =
            Path.of("build/resources/test/testFiles").toAbsolutePath().toString();

    /**
     * Tests that sysfile upload with an invalid node account fails gracefully.
     * <p>
     * The test uses node account "0" which typically doesn't exist in network
     * configurations (nodes usually have accounts like 3, 4, 5, etc.).
     * <p>
     * This test verifies that validation in ConfigManager.assertDefaultNodeAccountIsKnown()
     * properly rejects invalid node accounts with a clear error message instead of allowing
     * them to cause a NullPointerException later in the execution.
     */
    @HapiTest
    final Stream<DynamicTest> sysfileUploadWithInvalidNodeAccountFailsGracefully() {
        final var stderrRef = new AtomicReference<>("");
        return hapiTest(
                withOpContext((spec, opLog) -> {
                    // Attempt to upload software-zip with invalid node account 0
                    // The network typically has nodes with accounts 3, 4, 5, etc., not 0
                    // Validation should catch this and provide a clear error message
                    final var operation = yahcliSysFiles("upload", "-s", UPDATE_FILE_LOCATION, "software-zip")
                            .withNodeAccount("0")
                            .payingWith("2")
                            .expectFail() // Expect this to fail with validation error
                            .exposingStderrTo(stderrRef::set); // Capture stderr where ParameterException is printed

                    operation.execFor(spec);
                }),
                withOpContext((spec, opLog) -> {
                    final var stderr = stderrRef.get();

                    final var hasValidationError = stderr != null
                            && !stderr.isEmpty()
                            && (stderr.contains("Node account '0' does not exist in network")
                                    && stderr.contains("Available node accounts:"));

                    assertTrue(
                            hasValidationError,
                            "Expected validation error message about node account not existing in stderr.");
                }));
    }
}

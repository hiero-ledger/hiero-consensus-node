// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.validation;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.OUTPUT_DIR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateAllLogsAfter;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag("LOG_VALIDATION")
// Ordered to come after any other HapiTest that runs in a PR check
@Order(Integer.MAX_VALUE - 1)
public class LogValidationTest {
    private static final Duration VALIDATION_DELAY = Duration.ofSeconds(1);
    private static final String SWIRLDS_LOG = "swirlds.log";
    private final NodeMetadata metadata =
            new NodeMetadata(0, "node0", null, "", 0, 0, 0, 0, WorkingDirUtils.workingDirFor(0, "EMBEDDED"));

    private void validateSwirldsLog() throws IOException {
        final Path workingDir = requireNonNull(metadata.workingDir());
        final Path path = workingDir.resolve(OUTPUT_DIR).resolve(SWIRLDS_LOG);
        final String fileContent = Files.readString(path);
        final Pattern pattern = Pattern.compile(".*Exception.*", Pattern.CASE_INSENSITIVE);
        final Matcher matcher = pattern.matcher(fileContent);
        final StringBuilder matchedLines = new StringBuilder();
        while (matcher.find()) {
            matchedLines.append(matcher.group()).append(System.lineSeparator());
        }
        if (!matchedLines.isEmpty()) {
            throw new AssertionError("Unexpected problem found in logs:\n" + matchedLines);
        }
    }

    @LeakyHapiTest
    final Stream<DynamicTest> logsContainNoUnexpectedProblems() {
        return Stream.concat(
                hapiTest(validateAllLogsAfter(VALIDATION_DELAY)),
                Stream.of(DynamicTest.dynamicTest("Validate swirlds.log", this::validateSwirldsLog)));
    }
}

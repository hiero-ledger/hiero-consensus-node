// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.regression;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliSysFiles;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@code yahcli sysfiles upload} surfaces the underlying network response code
 * (e.g. {@code TRANSACTION_OVERSIZE}) when an upload fails, rather than emitting a bare
 * {@code .!. FAILED Uploading requested system files} line. Regression test for issue 25163.
 */
@Tag(REGRESSION)
public class SysFileUploadOversizeTest {

    /**
     * With governance transactions disabled (so the default test payer can no longer use the
     * 130 KB governance cap) and FileUpdate not on the jumbo allow-list, the effective per-tx
     * size limit drops to {@code transaction.maxBytes} (default 6,144 bytes). A file slightly
     * above that threshold therefore parses on the gRPC layer (whose hard cap is 130 KB) but is
     * rejected by the ingest precheck with {@code TRANSACTION_OVERSIZE}.
     */
    private static final int OVERSIZED_BYTES_PER_APPEND = 7_000;

    private static final int OVERSIZED_FILE_SIZE = 8_000;

    @LeakyHapiTest(overrides = {"governanceTransactions.isEnabled"})
    final Stream<DynamicTest> uploadFailureSurfacesPrecheckResponseCode(@TempDir final Path tempDir) {
        final var capturedOutput = new AtomicReference<>("");
        return hapiTest(
                overriding("governanceTransactions.isEnabled", "false"),
                doingContextual(spec -> {
                    final var softwareZip = tempDir.resolve("softwareUpgrade.zip");
                    final var bytes = new byte[OVERSIZED_FILE_SIZE];
                    // Deterministic non-zero filler so the resulting bytes don't accidentally
                    // compress to a tiny on-the-wire payload.
                    for (int i = 0; i < bytes.length; i++) {
                        bytes[i] = (byte) (i & 0xFF);
                    }
                    try {
                        Files.write(softwareZip, bytes);
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }

                    final var operation = yahcliSysFiles(
                                    "upload",
                                    "-s",
                                    tempDir.toAbsolutePath().toString(),
                                    "--bytes-per-append",
                                    Integer.toString(OVERSIZED_BYTES_PER_APPEND),
                                    "software-zip")
                            .expectFail()
                            .exposingOutputTo(capturedOutput::set);

                    operation.execFor(spec);
                }),
                doingContextual(spec -> {
                    final var output = capturedOutput.get();
                    assertTrue(
                            output.contains("FAILED Uploading requested system files"),
                            "Expected captured output to contain the FAILED warning, but got:\n" + output);
                    assertTrue(
                            output.contains("TRANSACTION_OVERSIZE"),
                            "Expected captured output to surface TRANSACTION_OVERSIZE precheck status, but got:\n"
                                    + output);
                }));
    }
}

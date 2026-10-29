// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

/**
 * Tests for {@link HgcaaLogValidator}.
 */
class HgcaaLogValidatorTest {
    private static final String PREFIX = "2026-10-22 12:00:00.000 <ledgerA-n0> ";

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("A streaming sync failure to an unreachable peer is expected while peers restart")
    void unavailableSyncFailureIsIgnored() throws IOException {
        final var log = logWith(
                PREFIX + "ERROR 175  ClprStreamingSynchronizer - Streaming sync failed for channel 01e8 via peer"
                        + " 127.0.0.1:44450 with status UNAVAILABLE",
                "com.hedera.node.app.workflows.clpr.ClprEndpointClient$ClprSyncException: Streaming sync write"
                        + " failed: UNAVAILABLE: io exception");

        assertDoesNotThrow(() -> new HgcaaLogValidator(log, Map.of()).validate());
    }

    @Test
    @DisplayName("Any other streaming sync failure is a problem")
    void otherSyncFailureIsAProblem() throws IOException {
        final var log = logWith(
                PREFIX + "ERROR 175  ClprStreamingSynchronizer - Streaming sync failed for channel 01e8 via peer"
                        + " 127.0.0.1:44450 with status INTERNAL",
                "com.hedera.node.app.workflows.clpr.ClprEndpointClient$ClprSyncException: Streaming sync read"
                        + " failed: INTERNAL: boom");

        assertThrows(AssertionFailedError.class, () -> new HgcaaLogValidator(log, Map.of()).validate());
    }

    private String logWith(final String... lines) throws IOException {
        final var log = Files.createTempFile(tempDir, "hgcaa", ".log");
        Files.write(log, List.of(lines));
        return log.toString();
    }
}

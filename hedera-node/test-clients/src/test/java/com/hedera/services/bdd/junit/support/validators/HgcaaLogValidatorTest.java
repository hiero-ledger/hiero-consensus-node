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
    private static final String CLPR_GRACE_EXTENSION_WARNING =
            "2026-11-10 18:55:33.158 <SHARED_NETWORK-n0> WARN  436  ClprEndpointManifestReconciler - [Clpr]"
                    + " construction #8 grace period expired with 1 of 4 target nodes published — extending"
                    + " (extensions used: 1/2)";

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

    @Test
    @DisplayName("The CI manifest grace-extension warning is expected while endpoints re-publish")
    void clprGraceExtensionWarningIsIgnored() throws IOException {
        final var log = logWith(CLPR_GRACE_EXTENSION_WARNING);

        assertDoesNotThrow(() -> new HgcaaLogValidator(log, Map.of()).validate());
    }

    @Test
    @DisplayName("A manifest grace-extension message logged as an error is still a problem")
    void clprGraceExtensionErrorIsAProblem() throws IOException {
        final var log = logWith(CLPR_GRACE_EXTENSION_WARNING.replace(" WARN ", " ERROR "));

        assertThrows(AssertionFailedError.class, () -> new HgcaaLogValidator(log, Map.of()).validate());
    }

    @Test
    @DisplayName("Other manifest-construction warnings are still problems")
    void otherClprManifestWarningIsAProblem() throws IOException {
        final var log = logWith(
                PREFIX + "WARN 436 ClprEndpointManifestReconciler - [Clpr] construction #8 could not be finalized");

        assertThrows(AssertionFailedError.class, () -> new HgcaaLogValidator(log, Map.of()).validate());
    }

    @Test
    @DisplayName("A grace-expiry warning without a recovery extension is still a problem")
    void clprGraceExpiryWithoutExtensionIsAProblem() throws IOException {
        final var log = logWith(CLPR_GRACE_EXTENSION_WARNING.replace("published — extending", "published — closing"));

        assertThrows(AssertionFailedError.class, () -> new HgcaaLogValidator(log, Map.of()).validate());
    }

    @Test
    @DisplayName("A matching warning from another logger is still a problem")
    void otherLoggerGraceExtensionWarningIsAProblem() throws IOException {
        final var log =
                logWith(CLPR_GRACE_EXTENSION_WARNING.replace("ClprEndpointManifestReconciler", "OtherReconciler"));

        assertThrows(AssertionFailedError.class, () -> new HgcaaLogValidator(log, Map.of()).validate());
    }

    @Test
    @DisplayName("Ignoring a grace-extension warning does not hide a following error")
    void clprGraceExtensionDoesNotHideFollowingError() throws IOException {
        final var log = logWith(
                CLPR_GRACE_EXTENSION_WARNING,
                PREFIX + "ERROR 450 ClprEndpointManifestReconciler - [Clpr] manifest finalization failed");

        assertThrows(AssertionFailedError.class, () -> new HgcaaLogValidator(log, Map.of()).validate());
    }

    private String logWith(final String... lines) throws IOException {
        final var log = Files.createTempFile(tempDir, "hgcaa", ".log");
        Files.write(log, List.of(lines));
        return log.toString();
    }
}

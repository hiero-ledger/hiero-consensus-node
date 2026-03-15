// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsPattern;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.suites.freeze.WrapsProvingKeyVerificationOnDiskTest.VALID_WRAPS_PROVING_KEY;
import static com.hedera.services.bdd.suites.freeze.WrapsProvingKeyVerificationOnDiskTest.readClasspathResource;
import static com.hedera.services.bdd.suites.freeze.WrapsProvingKeyVerificationOnDiskTest.writeBytes;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Subprocess test that verifies the WRAPS proving key hash recovery and error-handling behavior:
 * <ul>
 *   <li>Config hash is persisted to state even when the proving key file is missing</li>
 *   <li>Config hash can overwrite a previously persisted hash in state</li>
 *   <li>Node starts successfully even when the proving key file hash mismatches and the download fails</li>
 *   <li>Failed download is retried and, once the file becomes available, extracted to the correct path</li>
 * </ul>
 */
@Tag(RESTART)
@Tag(ONLY_SUBPROCESS)
@HapiTestLifecycle
@OrderedInIsolation
class WrapsProvingKeyVerificationRecoveryTest implements LifecycleTest {
    private static final Logger log = LogManager.getLogger(WrapsProvingKeyVerificationRecoveryTest.class);

    /** Arbitrary valid SHA-384 hex hashes (48 bytes = 96 hex chars) for testing. */
    private static final String CONFIG_HASH_A = "ab".repeat(48);

    private static final String CONFIG_HASH_B = "cd".repeat(48);
    private static final int HTTP_PORT = 8000;

    private static GenericContainer<?> httpContainer;
    private static String downloadUrl;
    private static Bytes validProvingKeyHash = Bytes.EMPTY;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("tss.hintsEnabled", "true", "tss.historyEnabled", "true", "tss.wrapsEnabled", "true"));

        // Start Python HTTP server container for the retry test (test 3);
        // the /data directory starts empty so initial downloads return 404
        httpContainer = new GenericContainer<>(DockerImageName.parse("python:3.12-alpine"))
                .withCommand("python", "-m", "http.server", String.valueOf(HTTP_PORT), "--directory", "/data")
                .withExposedPorts(HTTP_PORT)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
        httpContainer.start();
        downloadUrl = "http://" + httpContainer.getHost() + ":" + httpContainer.getMappedPort(HTTP_PORT)
                + "/proving-key.tar.gz";
        log.info("HTTP server container started, download URL: {}", downloadUrl);

        // Pre-compute the hash of the valid proving key for the retry test
        final var validBytes = readClasspathResource(VALID_WRAPS_PROVING_KEY);
        validProvingKeyHash = Bytes.wrap(sha384DigestOrThrow().digest(validBytes));
        log.info("Valid proving key hash: {}", validProvingKeyHash);
    }

    @AfterAll
    static void afterAll() {
        if (httpContainer != null) {
            httpContainer.stop();
        }
    }

    /**
     * When {@code wrapsProvingKeyHash} is set, the config hash should be persisted
     * to state even when the proving key file does not exist on disk.
     */
    @LeakyHapiTest(overrides = {"tss.wrapsProvingKeyPath", "tss.wrapsProvingKeyHash"})
    @Order(0)
    final Stream<DynamicTest> configHashPersistedToStateEvenWithoutFileOnDisk() {
        return hapiTest(
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of(
                        "tss.wrapsProvingKeyPath",
                        "data/config/nonexistent-proving-key.tar.gz",
                        "tss.wrapsProvingKeyHash",
                        CONFIG_HASH_A)),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.allNodes(), "WRAPS proving key hash from config: \\S+", Duration.ofSeconds(5)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.allNodes(),
                        "Persisted first WRAPS proving key hash \\S+ to state",
                        Duration.ofSeconds(5)));
    }

    /**
     * When a different {@code wrapsProvingKeyHash} is set, it should overwrite
     * the previously persisted hash in state.
     */
    @LeakyHapiTest(overrides = {"tss.wrapsProvingKeyPath", "tss.wrapsProvingKeyHash"})
    @Order(1)
    final Stream<DynamicTest> configHashOverwritesPreviousStateValue() {
        return hapiTest(
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of(
                        "tss.wrapsProvingKeyPath",
                        "data/config/nonexistent-proving-key.tar.gz",
                        "tss.wrapsProvingKeyHash",
                        CONFIG_HASH_B)),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.allNodes(),
                        "Overwriting previous WRAPS proving key hash \\S+ with new hash \\S+",
                        Duration.ofSeconds(5)));
    }

    /**
     * When the on-disk file hash mismatches and the download URL is unreachable, the
     * node should still start up successfully (log the error and continue).
     */
    @LeakyHapiTest(overrides = {"tss.wrapsProvingKeyPath", "tss.wrapsProvingKeyHash", "tss.wrapsProvingKeyDownloadUrl"})
    @Order(2)
    final Stream<DynamicTest> nodeStartsWhenHashMismatchesAndDownloadFails() {
        return hapiTest(
                prepareFakeUpgrade(),
                doingContextual(spec -> {
                    final var invalidBytes = "not-a-valid-proving-key-content".getBytes();
                    for (final var node : spec.getNetworkNodes()) {
                        final var configDir = node.getExternalPath(ExternalPath.DATA_CONFIG_DIR);
                        writeBytes(invalidBytes, configDir.resolve("bad-proving-key.tar.gz"));
                    }
                }),
                upgradeToNextConfigVersion(Map.of(
                        "tss.wrapsProvingKeyPath",
                        "data/config/bad-proving-key.tar.gz",
                        "tss.wrapsProvingKeyHash",
                        "aa".repeat(48),
                        "tss.wrapsProvingKeyDownloadUrl",
                        "http://localhost:1/proving-key.tar.gz")),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.allNodes(),
                        "WRAPS proving key hash mismatch at .+ \\(expected=.+, actual=.+\\), initiating download",
                        Duration.ofSeconds(5)));
    }

    /**
     * When the initial download fails (file not available on the HTTP server), the node
     * schedules a retry. Once the file becomes available, the retry downloads and extracts
     * the proving key archive, and the extracted files appear at the correct path.
     */
    @LeakyHapiTest(
            overrides = {
                "tss.wrapsProvingKeyPath",
                "tss.wrapsProvingKeyHash",
                "tss.wrapsProvingKeyDownloadUrl",
                "tss.wrapsProvingKeyRetryInterval"
            })
    @Order(3)
    final Stream<DynamicTest> retriesDownloadAfterFailureAndExtractsOnSuccess() {
        return hapiTest(
                prepareFakeUpgrade(),
                // Restart with the proving key NOT yet in the HTTP container (will 404)
                sourcing(() -> upgradeToNextConfigVersion(Map.of(
                        "tss.wrapsProvingKeyPath",
                        "data/config/retry-proving-key.tar.gz",
                        "tss.wrapsProvingKeyHash",
                        validProvingKeyHash.toHex(),
                        "tss.wrapsProvingKeyDownloadUrl",
                        downloadUrl,
                        "tss.wrapsProvingKeyRetryInterval",
                        "2s"))),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                // Now copy the valid proving key to the HTTP container so the retry succeeds
                doingContextual(spec -> {
                    httpContainer.copyFileToContainer(
                            MountableFile.forClasspathResource("/" + VALID_WRAPS_PROVING_KEY),
                            "/data/proving-key.tar.gz");
                    log.info("Copied proving key to HTTP container for retry");
                }),
                // Wait for the retry to download and verify the file
                assertHgcaaLogContainsPattern(
                        NodeSelector.allNodes(),
                        "Successfully downloaded and verified WRAPS proving key on retry \\(hash=\\S+\\)",
                        Duration.ofSeconds(30)),
                // Verify the extracted file from the tar.gz exists at the correct path
                doingContextual(spec -> {
                    for (final var node : spec.getNetworkNodes()) {
                        final var configDir = node.getExternalPath(ExternalPath.DATA_CONFIG_DIR);
                        final var extractedFile = configDir.resolve("MrBleaney.txt");
                        assertTrue(
                                Files.exists(extractedFile),
                                "Extracted file MrBleaney.txt should exist in " + configDir);
                        log.info("Verified extracted file exists at {}", extractedFile);
                    }
                }));
    }
}

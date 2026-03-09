// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsPattern;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verify;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.suites.freeze.WrapsProvingKeyVerificationOnDiskTest.VALID_WRAPS_PROVING_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
 * Subprocess test that exercises the full HTTP download path for the WRAPS proving key.
 * Starts a Python HTTP server container to serve the proving key file, then restarts
 * the network with a config that points to a missing local file, thereby forcing
 * the node to download the key via HTTP.
 */
@Tag(RESTART)
@Tag(ONLY_SUBPROCESS)
@HapiTestLifecycle
@OrderedInIsolation
class WrapsProvingKeyVerificationHttpDownloadTest implements LifecycleTest {
    private static final Logger log = LogManager.getLogger(WrapsProvingKeyVerificationHttpDownloadTest.class);

    private static final String INVALID_WRAPS_PROVING_KEY = "testfiles/invalid-wraps-proving-key.tar.gz";
    private static final int HTTP_PORT = 8000;

    private static GenericContainer<?> httpContainer;
    private static Bytes validProvingKeyHash = Bytes.EMPTY;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        // Start the Python HTTP server container with an empty /data directory;
        // the proving key file will be copied in lazily once the working directory is set
        httpContainer = new GenericContainer<>(DockerImageName.parse("python:3.12-alpine"))
                .withCommand("python", "-m", "http.server", String.valueOf(HTTP_PORT), "--directory", "/data")
                .withExposedPorts(HTTP_PORT)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
        httpContainer.start();
        log.info(
                "HTTP server container started at {}:{}",
                httpContainer.getHost(),
                httpContainer.getMappedPort(HTTP_PORT));

        final String downloadUrl = "http://" + httpContainer.getHost() + ":" + httpContainer.getMappedPort(HTTP_PORT)
                + "/wraps-proving-key.tar.gz";
        log.info("Download URL: {}", downloadUrl);

        testLifecycle.overrideInClass(Map.of(
                "tss.hintsEnabled", "true",
                "tss.historyEnabled", "true",
                "tss.wrapsEnabled", "true",
                "tss.wrapsProvingKeyDownloadUrl", downloadUrl));
    }

    @AfterAll
    static void afterAll() {
        if (httpContainer != null) {
            httpContainer.stop();
        }
    }

    @LeakyHapiTest(overrides = {"tss.wrapsProvingKeyPath", "tss.wrapsProvingKeyHash"})
    @Order(0)
    final Stream<DynamicTest> downloadsProvingKeyWhenFileMissing() {
        final AtomicReference<String> downloadedHash = new AtomicReference<>();
        final AtomicReference<String> persistedHash = new AtomicReference<>();

        return hapiTest(
                // Seed the container lazily so paths resolve after the framework sets the working dir
                doingContextual(spec -> {
                    final var validWrapsProvingKeyPath = Paths.get(VALID_WRAPS_PROVING_KEY);
                    try {
                        validProvingKeyHash =
                                Bytes.wrap(sha384DigestOrThrow().digest(Files.readAllBytes(validWrapsProvingKeyPath)));
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    log.info("Valid proving key hash: {}", validProvingKeyHash);
                    httpContainer.copyFileToContainer(
                            MountableFile.forHostPath(
                                    validWrapsProvingKeyPath.toAbsolutePath().toString()),
                            "/data/wraps-proving-key.tar.gz");
                    log.info("Copied proving key to container");
                }),
                prepareFakeUpgrade(),
                // Requires lazy execution due to the container copy op
                sourcing(() -> upgradeToNextConfigVersion(Map.of(
                        "tss.wrapsProvingKeyPath",
                        "data/config/downloaded-proving-key.tar.gz",
                        "tss.wrapsProvingKeyHash",
                        validProvingKeyHash.toHex()))),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                                NodeSelector.allNodes(),
                                "Successfully downloaded and verified WRAPS proving key \\(hash=(\\S+)\\)",
                                Duration.ofSeconds(5))
                        .exposingMatchGroupTo(1, downloadedHash),
                assertHgcaaLogContainsPattern(
                                NodeSelector.allNodes(),
                                "Persisted first WRAPS proving key hash (\\S+) to state",
                                Duration.ofSeconds(5))
                        .exposingMatchGroupTo(1, persistedHash),
                verify(() -> {
                    assertEquals(
                            validProvingKeyHash.toHex(),
                            downloadedHash.get(),
                            "Downloaded proving key hash should match expected hash");
                    assertEquals(
                            validProvingKeyHash.toHex(),
                            persistedHash.get(),
                            "Persisted hash should match expected hash");
                }));
    }

    @SuppressWarnings("DuplicatedCode")
    @LeakyHapiTest(overrides = {"tss.wrapsProvingKeyPath", "tss.wrapsProvingKeyHash"})
    @Order(1)
    final Stream<DynamicTest> persistsDownloadedProvingKeyHash() {
        final AtomicReference<String> persistedHash = new AtomicReference<>();

        return hapiTest(
                prepareFakeUpgrade(),
                // Requires lazy execution due to the container copy op
                sourcing(() -> upgradeToNextConfigVersion(Map.of(
                        "tss.wrapsProvingKeyPath",
                        "data/config/downloaded-proving-key.tar.gz",
                        "tss.wrapsProvingKeyHash",
                        validProvingKeyHash.toHex()))),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                                NodeSelector.allNodes(),
                                "Pending WRAPS proving key hash (\\S+) matches proving key in state",
                                Duration.ofSeconds(5))
                        .exposingMatchGroupTo(1, persistedHash),
                verify(() -> assertEquals(
                        validProvingKeyHash.toHex(),
                        persistedHash.get(),
                        "Persisted hash should match the downloaded proving key hash")));
    }

    @LeakyHapiTest(overrides = {"tss.wrapsProvingKeyPath", "tss.wrapsProvingKeyHash"})
    @Order(2)
    final Stream<DynamicTest> redownloadsWhenOnDiskHashMismatches() {
        final AtomicReference<String> downloadedHash = new AtomicReference<>();

        return hapiTest(
                prepareFakeUpgrade(),
                doingContextual(spec -> {
                    for (final var node : spec.getNetworkNodes()) {
                        final var configDir = node.getExternalPath(ExternalPath.DATA_CONFIG_DIR);
                        WorkingDirUtils.copyUnchecked(
                                Paths.get(INVALID_WRAPS_PROVING_KEY),
                                configDir.resolve("invalid-wraps-proving-key.tar.gz"));
                    }
                }),
                sourcing(() -> upgradeToNextConfigVersion(Map.of(
                        "tss.wrapsProvingKeyPath",
                        "data/config/invalid-wraps-proving-key.tar.gz",
                        "tss.wrapsProvingKeyHash",
                        validProvingKeyHash.toHex()))),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.allNodes(),
                        "WRAPS proving key hash mismatch at .+ \\(expected=.+, actual=.+\\), initiating download",
                        Duration.ofSeconds(5)),
                assertHgcaaLogContainsPattern(
                                NodeSelector.allNodes(),
                                "Successfully downloaded and verified WRAPS proving key \\(hash=(\\S+)\\)",
                                Duration.ofSeconds(5))
                        .exposingMatchGroupTo(1, downloadedHash),
                verify(() -> assertEquals(
                        validProvingKeyHash.toHex(),
                        downloadedHash.get(),
                        "Re-downloaded proving key hash should match expected hash")));
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsPattern;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Subprocess test that exercises the full S3 download path for the WRAPS proving key.
 * Starts a MinIO container as a local S3-compatible store, seeds it with the proving key,
 * then restarts the network with a config that points to a missing local file, thereby forcing
 * the node to download the key via {@code S3WrapsProvingKeyDownloader}.
 */
@Tag(RESTART)
@Tag(ONLY_SUBPROCESS)
@HapiTestLifecycle
@OrderedInIsolation
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WrapsProvingKeyS3DownloadTest implements LifecycleTest {
    private static final Logger log = LogManager.getLogger(WrapsProvingKeyS3DownloadTest.class);

    private static final String INVALID_WRAPS_PROVING_KEY = "testfiles/invalid-wraps-proving-key.tar.gz";
    private static final int S3_API_PORT = 9000;

    private static GenericContainer<?> minioContainer;
    private static Bytes validProvingKeyHash = Bytes.EMPTY;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        // Calculate the hash of the proving key file we'll serve
        final var validWrapsProvingKeyPath = Paths.get(VALID_WRAPS_PROVING_KEY);
        try {
            validProvingKeyHash =
                    Bytes.wrap(sha384DigestOrThrow().digest(Files.readAllBytes(validWrapsProvingKeyPath)));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        log.info("Valid proving key hash: {}", validProvingKeyHash);

        // Set up the MinIO container
        minioContainer = new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-02-18T16-25-55Z"))
                .withCommand("server", "/data")
                .withExposedPorts(S3_API_PORT)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(
                                validWrapsProvingKeyPath.toAbsolutePath().toString()),
                        "/tmp/proving-key.tar.gz")
                .waitingFor(Wait.forHttp("/minio/health/live")
                        .forPort(S3_API_PORT)
                        .withStartupTimeout(Duration.ofSeconds(60)));
        minioContainer.start();
        log.info(
                "MinIO container started at {}:{}",
                minioContainer.getHost(),
                minioContainer.getMappedPort(S3_API_PORT));

        seedMinIOBucket();

        final String downloadUrl = "http://" + minioContainer.getHost() + ":"
                + minioContainer.getMappedPort(S3_API_PORT) + "/proving-keys/wraps-proving-key.tar.gz";
        log.info("Download URL: {}", downloadUrl);

        // Copy the invalid proving key file to each node's config directory
        testLifecycle.doAdhoc(doingContextual(spec -> {
            for (final var node : spec.getNetworkNodes()) {
                final var configDir = node.getExternalPath(ExternalPath.DATA_CONFIG_DIR);
                WorkingDirUtils.copyUnchecked(
                        Paths.get(INVALID_WRAPS_PROVING_KEY), configDir.resolve("invalid-wraps-proving-key.tar.gz"));
            }
        }));

        testLifecycle.overrideInClass(Map.of(
                "tss.hintsEnabled", "true",
                "tss.historyEnabled", "true",
                "tss.wrapsEnabled", "true",
                "tss.wrapsProvingKeyDownloadUrl", downloadUrl));
    }

    @AfterAll
    static void afterAll() {
        if (minioContainer != null) {
            minioContainer.stop();
        }
    }

    @LeakyHapiTest(overrides = {"tss.wrapsProvingKeyPath", "tss.wrapsProvingKeyHash"})
    @Order(0)
    final Stream<DynamicTest> downloadsProvingKeyFromS3WhenFileMissing() {
        final AtomicReference<String> downloadedHash = new AtomicReference<>();
        final AtomicReference<String> persistedHash = new AtomicReference<>();

        return hapiTest(
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of(
                        "tss.wrapsProvingKeyPath",
                        "data/config/s3-downloaded-proving-key.tar.gz",
                        "tss.wrapsProvingKeyHash",
                        validProvingKeyHash.toHex())),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                                NodeSelector.allNodes(),
                                "Successfully downloaded and verified WRAPS proving key \\(hash=(\\S+)\\)",
                                Duration.ofSeconds(30))
                        .exposingMatchGroupTo(1, downloadedHash),
                assertHgcaaLogContainsPattern(
                                NodeSelector.allNodes(),
                                "Persisted first WRAPS proving key hash (\\S+) to state",
                                Duration.ofSeconds(10))
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
                upgradeToNextConfigVersion(Map.of(
                        "tss.wrapsProvingKeyPath",
                        "data/config/s3-downloaded-proving-key.tar.gz",
                        "tss.wrapsProvingKeyHash",
                        validProvingKeyHash.toHex())),
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
                upgradeToNextConfigVersion(Map.of(
                        "tss.wrapsProvingKeyPath",
                        "data/config/invalid-wraps-proving-key.tar.gz",
                        "tss.wrapsProvingKeyHash",
                        validProvingKeyHash.toHex())),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.allNodes(),
                        "WRAPS proving key hash mismatch at .+ \\(expected=.+, actual=.+\\), initiating download",
                        Duration.ofSeconds(5)),
                assertHgcaaLogContainsPattern(
                                NodeSelector.allNodes(),
                                "Successfully downloaded and verified WRAPS proving key \\(hash=(\\S+)\\)",
                                Duration.ofSeconds(30))
                        .exposingMatchGroupTo(1, downloadedHash),
                verify(() -> assertEquals(
                        validProvingKeyHash.toHex(),
                        downloadedHash.get(),
                        "Re-downloaded proving key hash should match expected hash")));
    }

    /**
     * Uses the {@code mc} binary inside the MinIO container to create a public bucket
     * and upload the proving key file into it.
     */
    private static void seedMinIOBucket() {
        try {
            execInMinIO("mc", "alias", "set", "local", "http://localhost:9000", "minioadmin", "minioadmin");
            execInMinIO("mc", "mb", "local/proving-keys");
            execInMinIO("mc", "cp", "/tmp/proving-key.tar.gz", "local/proving-keys/wraps-proving-key.tar.gz");
            execInMinIO("mc", "anonymous", "set", "download", "local/proving-keys");
        } catch (final Exception e) {
            throw new RuntimeException("Failed to seed MinIO bucket", e);
        }
    }

    private static void execInMinIO(final String... command) throws IOException, InterruptedException {
        final var result = minioContainer.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new RuntimeException("MinIO command failed: " + String.join(" ", command)
                    + "\nstdout: " + result.getStdout()
                    + "\nstderr: " + result.getStderr());
        }
        log.info("mc: {}", result.getStdout().trim());
    }
}

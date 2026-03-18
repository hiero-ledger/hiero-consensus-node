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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Subprocess test that verifies the WRAPS proving key without requiring a key download. Instead, the
 * test copies proving key files to the config directory of each node before the tests run.
 */
@Tag(RESTART)
@Tag(ONLY_SUBPROCESS)
@HapiTestLifecycle
@OrderedInIsolation
class WrapsProvingKeyVerificationOnDiskTest implements LifecycleTest {
    private static final Logger log = LogManager.getLogger(WrapsProvingKeyVerificationOnDiskTest.class);

    static final String VALID_WRAPS_PROVING_KEY = "testfiles/valid-wraps-proving-key.tar.gz";
    private static final String VALID_VARIANT_WRAPS_PROVING_KEY = "testfiles/valid-wraps-proving-key-variant.tar.gz";

    private static Bytes validProvingKeyHash = Bytes.EMPTY;
    private static Bytes validVariantProvingKeyHash = Bytes.EMPTY;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "tss.hintsEnabled", "true",
                "tss.historyEnabled", "true",
                "tss.wrapsEnabled", "true",
                "tss.wrapsProvingKeyDownloadEnabled", "true"));

        // Assign hashes and assert preconditions
        final var validBytes = readClasspathResource(VALID_WRAPS_PROVING_KEY);
        final var variantBytes = readClasspathResource(VALID_VARIANT_WRAPS_PROVING_KEY);
        final var digest = sha384DigestOrThrow();
        validProvingKeyHash = Bytes.wrap(digest.digest(validBytes));
        log.info("Valid proving key hash: {}", validProvingKeyHash);

        validVariantProvingKeyHash = Bytes.wrap(digest.digest(variantBytes));
        log.info("Valid variant proving key hash: {}", validVariantProvingKeyHash);

        assertNotEquals(
                validProvingKeyHash,
                validVariantProvingKeyHash,
                "Precondition: valid and valid variant proving key hashes can't be the same");

        // Copy valid and valid variant proving key files to the config directory of each node
        testLifecycle.doAdhoc(doingContextual(spec -> {
            final var workingDirs = spec.getNetworkNodes().stream()
                    .map(n -> n.getExternalPath(ExternalPath.DATA_CONFIG_DIR))
                    .toList();
            for (final var workingDir : workingDirs) {
                writeBytes(validBytes, workingDir.resolve("valid-wraps-proving-key.tar.gz"));
                writeBytes(variantBytes, workingDir.resolve("valid-wraps-proving-key-variant.tar.gz"));
            }
        }));
    }

    @SuppressWarnings("DuplicatedCode")
    @LeakyHapiTest(overrides = {"tss.wrapsProvingKeyPath", "tss.wrapsProvingKeyHash"})
    @Order(0)
    final Stream<DynamicTest> setsFirstProvingKeyHash() {
        final AtomicReference<String> selectedProvingKeyHash = new AtomicReference<>();

        return hapiTest(
                // Immediately restart the network
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of(
                        "tss.wrapsProvingKeyPath",
                        "data/config/valid-wraps-proving-key.tar.gz",
                        "tss.wrapsProvingKeyHash",
                        validProvingKeyHash.toHex())),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                                NodeSelector.allNodes(),
                                "Persisted first WRAPS proving key hash (\\S+) to state",
                                Duration.ofSeconds(30))
                        .exposingMatchGroupTo(1, selectedProvingKeyHash),
                verify(() -> assertEquals(
                        validProvingKeyHash.toHex(),
                        selectedProvingKeyHash.get(),
                        "Node should log the valid proving key hash")));
    }

    @LeakyHapiTest(overrides = {"tss.wrapsProvingKeyPath", "tss.wrapsProvingKeyHash"})
    @Order(1)
    final Stream<DynamicTest> overwritesWithValidVariantProvingKeyHash() {
        final AtomicReference<String> prevProvingKeyHash = new AtomicReference<>();
        final AtomicReference<String> selectedProvingKeyHash = new AtomicReference<>();

        return hapiTest(
                // Immediately restart the network again
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of(
                        "tss.wrapsProvingKeyPath",
                        "data/config/valid-wraps-proving-key-variant.tar.gz",
                        "tss.wrapsProvingKeyHash",
                        validVariantProvingKeyHash.toHex())),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                                NodeSelector.allNodes(),
                                "Overwriting previous WRAPS proving key hash (\\S+) with new pending hash (\\S+)",
                                Duration.ofSeconds(30))
                        .exposingMatchGroupTo(1, prevProvingKeyHash)
                        .exposingMatchGroupTo(2, selectedProvingKeyHash),
                verify(() -> {
                    assertEquals(
                            validProvingKeyHash.toHex(),
                            prevProvingKeyHash.get(),
                            "Previous hash in log should match the first valid proving key hash");
                    assertEquals(
                            validVariantProvingKeyHash.toHex(),
                            selectedProvingKeyHash.get(),
                            "Node should log the valid variant proving key hash");
                }));
    }

    /**
     * Reads all bytes of a classpath resource.
     */
    static byte[] readClasspathResource(@NonNull final String name) {
        final var in =
                WrapsProvingKeyVerificationOnDiskTest.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("Classpath resource not found: " + name);
        }
        try (in) {
            return in.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes bytes to a target path, creating parent directories as needed.
     */
    static void writeBytes(@NonNull final byte[] bytes, @NonNull final Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

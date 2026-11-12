// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.updateBootstrapProperties;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp.ISS_NODE_ID;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.spec.SpecOperation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shared helpers for the ISS block-staging HAPI tests ({@link IssHandlingTest} and {@link IssGrpcBufferStagingTest}):
 * enabling failure-staging on the ISS node and collecting the staged artifacts. Each test keeps its own assertions on
 * top of {@link #stagedRegularFiles(Path)}.
 */
final class IssStagingTestSupport {
    private static final Logger log = LogManager.getLogger(IssStagingTestSupport.class);

    private IssStagingTestSupport() {}

    /**
     * Configures the ISS node (at reconnect) with the aberrant {@code ledger.transfers.maxLen} that induces the self-ISS
     * and enables failure-staging into a dir under the node's working directory, capturing that dir into
     * {@code issBlockDir} for later assertions.
     */
    static SpecOperation configureFailureStaging(final AtomicReference<Path> issBlockDir) {
        return doingContextual(spec -> {
            final var issNode = spec.getNetworkNodes().get((int) ISS_NODE_ID);
            final var props = issNode.getExternalPath(APPLICATION_PROPERTIES);
            final var configDir = issNode.getExternalPath(DATA_CONFIG_DIR);
            final Path stagingDir = configDir.toAbsolutePath().getParent().resolve("iss-blocks");
            issBlockDir.set(stagingDir);
            log.info("Configuring ISS node failure-staging + transfer limit @ {} (staging dir {})", props, stagingDir);
            updateBootstrapProperties(
                    props,
                    Map.of(
                            "ledger.transfers.maxLen", "5",
                            "failureBlockStaging.issBlockStagingEnabled", "true",
                            "failureBlockStaging.triageStagingEnabled", "true",
                            "failureBlockStaging.issBlockDir", stagingDir.toString()));
        });
    }

    /** Every regular file staged anywhere under {@code issBlockDir}, asserting the dir was created. */
    static List<String> stagedRegularFiles(final Path issBlockDir) {
        assertTrue(
                issBlockDir != null && Files.isDirectory(issBlockDir),
                "ISS staging dir was never created: " + issBlockDir);
        try (final Stream<Path> paths = Files.walk(issBlockDir)) {
            return paths.filter(Files::isRegularFile).map(Path::toString).toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.*;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Runs in subprocess mode and verifies the wrapped record hashes flag is {@code false} by default,
 * then "upgrades" to enable it and begin writing {@code wrapped-record-hashes.pb} on every node.
 */
@Tag(UPGRADE)
@Tag(ONLY_SUBPROCESS)
@HapiTestLifecycle
@OrderedInIsolation
public class WrappedRecordHashesFlagUpgradeSubprocessTest implements LifecycleTest {
    private static final String WRAPPED_RECORD_HASHES_FILE_NAME = "wrapped-record-hashes.pb";
    private static final long DISK_IO_WAIT_MS = 1_000;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", "false"));
        // Delete the wrapped record hashes file if it exists
        testLifecycle.doAdhoc(doingContextual(spec -> {
            final var workingDirs = spec.getNetworkNodes().stream()
                    .map(n -> n.getExternalPath(ExternalPath.WORKING_DIR))
                    .toList();
            for (final var workingDir : workingDirs) {
                final var file = expectedWrappedRecordHashesFileUnder(workingDir);
                try {
                    Files.deleteIfExists(file);
                } catch (final IOException e) {
                    throw new UncheckedIOException("Unable to delete " + file, e);
                }
            }
        }));
    }

    @HapiTest
    public @NonNull Stream<DynamicTest> canEnableWrappedRecordHashesAcrossUpgradeFromDefaultOff() {
        final var enableAtRestart = Map.of("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", "true");

        return hapiTest(
                // Produce a new record block and ensure nothing was written with default settings
                waitUntilNextBlock(),
                cryptoTransfer((ignore, builder) -> {}).payingWith(GENESIS),
                sleepFor(DISK_IO_WAIT_MS),
                doingContextual(spec -> assertNoWrappedHashesWritten(spec.getNetworkNodes().stream()
                        .map(n -> n.getExternalPath(ExternalPath.WORKING_DIR))
                        .toList())),
                prepareFakeUpgrade(),
                // Now restart with the feature enabled (bootstrap override)
                upgradeToNextConfigVersion(enableAtRestart),
                // Produce a new record block and ensure the file exists and is parseable on every node
                waitUntilNextBlock(),
                cryptoTransfer((ignore, builder) -> {}).payingWith(GENESIS),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                sleepFor(DISK_IO_WAIT_MS),
                doingContextual(spec -> assertWrappedHashesWrittenAndParseable(spec.getNetworkNodes().stream()
                        .map(n -> n.getExternalPath(ExternalPath.WORKING_DIR))
                        .toList())));
    }

    private static void assertNoWrappedHashesWritten(@NonNull final java.util.List<Path> workingDirs) {
        requireNonNull(workingDirs);
        for (final var workingDir : workingDirs) {
            final var file = expectedWrappedRecordHashesFileUnder(workingDir);
            if (!Files.exists(file)) {
                continue;
            }
            try {
                assertFalse(
                        Files.size(file) > 0,
                        "Expected no wrapped record hashes entries while feature is disabled, but file is non-empty: "
                                + file);
            } catch (final IOException e) {
                throw new UncheckedIOException("Unable to stat " + file, e);
            }
        }
    }

    private static void assertWrappedHashesWrittenAndParseable(@NonNull final java.util.List<Path> workingDirs) {
        requireNonNull(workingDirs);
        for (final var workingDir : workingDirs) {
            final var file = expectedWrappedRecordHashesFileUnder(workingDir);
            assertTrue(Files.exists(file), "Expected wrapped record hashes file after enabling feature: " + file);
            final byte[] bytes;
            try {
                assertTrue(Files.size(file) > 0, "Expected non-empty wrapped record hashes file: " + file);
                bytes = Files.readAllBytes(file);
            } catch (final IOException e) {
                throw new UncheckedIOException("Unable to read " + file, e);
            }
            final WrappedRecordFileBlockHashesLog parsed;
            try {
                parsed = WrappedRecordFileBlockHashesLog.PROTOBUF.parse(Bytes.wrap(bytes));
            } catch (final ParseException e) {
                throw new IllegalStateException("Unable to parse " + file, e);
            }
            assertTrue(
                    parsed.entries() != null && !parsed.entries().isEmpty(),
                    "Expected at least one wrapped record hashes entry in " + file);
        }
    }

    private static Path expectedWrappedRecordHashesFileUnder(@NonNull final Path workingDir) {
        requireNonNull(workingDir);
        return workingDir.resolve("data").resolve("wrappedRecordHashes").resolve(WRAPPED_RECORD_HASHES_FILE_NAME);
    }
}

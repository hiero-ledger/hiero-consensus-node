// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.services.bdd.junit.TestTags.ND_RECONNECT;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.WRAPPED_RECORD_HASHES_FILE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.updateBootstrapProperties;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlocks;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.configVersionOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Restarts a node with an oversized, corrupt or stale {@code wrapped-record-hashes.pb}, and checks that the node
 * truncates the file (or deletes it, when writing is disabled in its startup config), keeps appending to it, and
 * reports its size in the {@code blockStream_recordHashes_fileSizeBytes} gauge.
 */
@Tag(ND_RECONNECT)
@Tag(ONLY_SUBPROCESS)
@HapiTestLifecycle
@OrderedInIsolation
public class WrappedRecordHashesFileRecoverySubprocessTest implements LifecycleTest {
    private static final long NODE_ID = 1L;
    private static final String WRITE_TO_DISK = "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk";
    // 2^31 bytes: over every allowed limit, and Files.readAllBytes rejects it on any heap; sparse, so no disk use
    private static final long OVERSIZED_FILE_BYTES = Integer.MAX_VALUE + 1L;
    private static final long MAX_BYTES_AFTER_RECOVERY = 1_000_000L;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(WRITE_TO_DISK, "true"));
    }

    @LeakyHapiTest
    @Order(1)
    final Stream<DynamicTest> truncatesOversizedFileOnRestart() {
        return restartWithReplacedFile(
                true,
                file -> {
                    try (final var raf = new RandomAccessFile(file.toFile(), "rw")) {
                        raf.setLength(OVERSIZED_FILE_BYTES);
                    }
                },
                "byte limit. Truncating");
    }

    @LeakyHapiTest
    @Order(2)
    final Stream<DynamicTest> truncatesCorruptFileOnRestart() {
        return restartWithReplacedFile(
                true,
                file -> {
                    final var garbage = new byte[4096];
                    Arrays.fill(garbage, (byte) 0xFF);
                    Files.write(file, garbage);
                },
                "Failed to scan existing wrapped record hashes file");
    }

    @LeakyHapiTest
    @Order(3)
    final Stream<DynamicTest> deletesFileOnRestartWhenWritingIsDisabled() {
        return restartWithReplacedFile(
                false, file -> Files.writeString(file, "stale"), "Deleted stale wrapped record hashes file");
    }

    private Stream<DynamicTest> restartWithReplacedFile(
            final boolean writingOnDisk,
            @NonNull final FileReplacement replacement,
            @NonNull final String expectedLogText) {
        final AtomicReference<SemanticVersion> currentVersion = new AtomicReference<>();
        return hapiTest(
                doingContextual(spec -> Assumptions.assumeTrue(
                        spec.startupProperties().getStreamMode("blockStream.streamMode") != BLOCKS,
                        "Wrapped record hashes need the record stream; skipping in BLOCKS-only mode")),
                waitUntilNextBlocks(3).withBackgroundTraffic(true),
                getVersionInfo().exposingServicesVersionTo(currentVersion::set),
                // A same-version restart, so the node's own startup must handle the replaced file
                sourcing(() -> reconnectNode(
                        byNodeId(NODE_ID), configVersionOf(currentVersion.get()), doingContextual(spec -> {
                            setWritingOnDisk(spec, writingOnDisk);
                            replaceHashesFile(spec, replacement);
                        }))),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                assertHgcaaLogContainsText(byNodeId(NODE_ID), expectedLogText, Duration.ZERO),
                doingContextual(WrappedRecordHashesFileRecoverySubprocessTest::assertFileTruncatedAndTracked));
    }

    // The schema reads the flag from the node's startup config, before network property overrides are applied
    private static void setWritingOnDisk(@NonNull final HapiSpec spec, final boolean enabled) {
        final var node = spec.targetNetworkOrThrow().getRequiredNode(byNodeId(NODE_ID));
        updateBootstrapProperties(
                node.getExternalPath(APPLICATION_PROPERTIES), Map.of(WRITE_TO_DISK, Boolean.toString(enabled)));
    }

    private static void replaceHashesFile(@NonNull final HapiSpec spec, @NonNull final FileReplacement replacement) {
        final var file = hashesFileOf(spec);
        try {
            Files.createDirectories(file.getParent());
            replacement.writeTo(file);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertFileTruncatedAndTracked(@NonNull final HapiSpec spec) {
        final var node = spec.targetNetworkOrThrow().getRequiredNode(byNodeId(NODE_ID));
        // Read the gauge before the file; the file only grows, so the tracked size cannot exceed it
        final long trackedSize = spec.prometheusClient()
                .getWrappedRecordHashesFileSizeBytes(node.metadata().prometheusPort())
                .orElseThrow(() -> new AssertionError("No wrapped record hashes file size gauge on node" + NODE_ID));
        final var file = hashesFileOf(spec);
        final long actualSize;
        final WrappedRecordFileBlockHashesLog log;
        try {
            actualSize = Files.size(file);
            assertTrue(
                    actualSize < MAX_BYTES_AFTER_RECOVERY,
                    "Expected node" + NODE_ID + " to truncate its file, but it is " + actualSize + " bytes");
            log = WrappedRecordFileBlockHashesLog.PROTOBUF.parse(Bytes.wrap(Files.readAllBytes(file)));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final ParseException e) {
            throw new IllegalStateException("Node" + NODE_ID + "'s rewritten wrapped record hashes file is invalid", e);
        }
        assertFalse(log.entries().isEmpty(), "Expected node" + NODE_ID + " to append entries after the restart");
        assertTrue(
                trackedSize > 0 && trackedSize <= actualSize,
                "Expected the tracked size " + trackedSize + " to be in (0, " + actualSize + "]");
    }

    private static Path hashesFileOf(@NonNull final HapiSpec spec) {
        return spec.targetNetworkOrThrow()
                .getRequiredNode(byNodeId(NODE_ID))
                .getExternalPath(WRAPPED_RECORD_HASHES_FILE);
    }

    @FunctionalInterface
    private interface FileReplacement {
        void writeTo(@NonNull Path file) throws IOException;
    }
}

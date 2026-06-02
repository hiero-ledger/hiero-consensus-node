// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.verification.traceability;

import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.guaranteedExtantDir;
import static com.hedera.services.bdd.junit.support.StreamFileAccess.STREAM_FILE_ACCESS;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.triggerAndCloseAtLeastOneFileIfNotInterrupted;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.services.bdd.junit.support.StreamDataListener;
import com.hedera.services.bdd.junit.support.StreamFileAccess;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.junit.support.translators.BlockRecordTranslator;
import com.hedera.services.stream.proto.TransactionSidecarRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * A class that simultaneously,
 * <ol>
 *     <li>Listens for the actual sidecars from one source — either the legacy V6 sidecar files
 *     via {@link StreamFileAccess#STREAM_FILE_ACCESS}, or synthesised from the block stream
 *     when the four-arg constructor is given a block-stream path; and,</li>
 *     <li>Registers expected sidecars.</li>
 * </ol>
 * When a client has registered all its expectations with a {@link SidecarWatcher}
 * (necessarily after submitting the transactions triggering those sidecars,
 * since it must look up the consensus timestamp of the expected sidecars), it
 * should call the {@link SidecarWatcher#assertExpectations(HapiSpec)} method.
 *
 * <p>This method throws if any actual sidecar matched the consensus timestamp
 * of an expected sidecar, but did not match other fields; or if there are
 * expected sidecars that were never seen in the actual sidecar stream.
 */
// string literals should not be duplicated
@SuppressWarnings("java:S1192")
public class SidecarWatcher {
    private static final Logger log = LogManager.getLogger(SidecarWatcher.class);

    /**
     * The watcher is event-driven (sidecars arrive after a sidecar marker file is noticed).
     * In CI, marker creation/visibility can lag just enough that immediate unsubscribe can race
     * and leave expectations "pending" even though the sidecar file exists on disk.
     */
    private static final long EXPECTATIONS_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static final int FINAL_DRAIN_ATTEMPTS = 3;

    private final Path streamFilesPath;

    /**
     * Block-stream directory and shard/realm retained for the synchronous disk-rescan backstop
     * ({@link #drainUnseenSidecarsFromBlocks()}). Null when the watcher is in legacy record-stream mode.
     */
    @Nullable
    private final Path blockStreamPath;

    private final long shard;
    private final long realm;
    /**
     * Handle to the record-stream listener subscription. Set to a no-op when the watcher is in
     * block-stream-only mode (see the 4-arg constructor).
     */
    private final Runnable unsubscribe;

    /**
     * Handle to the block-stream listener subscription, present only when the watcher was
     * constructed in block-stream-only mode. When set, each new block is translated back into
     * V6-shape {@link TransactionSidecarRecord}s and pumped through the same drain pipeline as the
     * legacy on-disk sidecars.
     */
    @Nullable
    private final Runnable unsubscribeBlocks;

    private final Queue<ExpectedSidecar> expectedSidecars = new LinkedBlockingDeque<>();
    private final Queue<TransactionSidecarRecord> actualSidecars = new LinkedBlockingDeque<>();
    private final HashSet<TransactionSidecarRecord> seenActualSidecars = new HashSet<>();
    // LinkedHashMap lets us easily print mismatches _in the order added_. Important if the
    // records get out-of-sync at one particular test, then all the _rest_ of the tests fail
    // too: It's good to know the _first_ test which fails.
    private final LinkedHashMap<String, List<MismatchedSidecar>> failedSidecars = new LinkedHashMap<>();

    private boolean hasSeenFirstExpectedSidecar = false;

    private record ConstructionDetails(String creatingThread, String stackTrace) {}

    /**
     * Creates a {@link SidecarWatcher} appropriate for the active stream mode.
     * In BLOCKS mode, sidecars are synthesised from block items; otherwise, V6 sidecar files are used.
     */
    public static SidecarWatcher forSpec(@NonNull final HapiSpec spec) {
        final var streamsLoc = spec.recordStreamsLoc(byNodeId(0));
        final var streamMode = spec.startupProperties().getStreamMode("blockStream.streamMode");
        if (streamMode == BLOCKS) {
            final var network = spec.targetNetworkOrThrow();
            final var blockStreamLoc = network.getRequiredNode(byNodeId(0)).getExternalPath(BLOCK_STREAMS_DIR);
            return new SidecarWatcher(streamsLoc, blockStreamLoc, network.shard(), network.realm());
        }
        return new SidecarWatcher(streamsLoc);
    }

    public SidecarWatcher(@NonNull final Path path) {
        // shard/realm are unused on this overload — only consulted when blockStreamPath != null.
        this(path, null, 0L, 0L);
    }

    /**
     * Constructs a watcher that picks exactly one source for actual sidecars:
     * <ul>
     *     <li>If {@code blockStreamPath} is non-null, sidecars are synthesised from each new block
     *         under that path via {@link BlockRecordTranslator} (this is the path used
     *         under {@code streamMode=BLOCKS}, where no V6 sidecar files are written).</li>
     *     <li>Otherwise the watcher subscribes to the legacy V6 sidecar files at {@code path}
     *         (this is the path used under {@code streamMode=RECORDS} or {@code BOTH}).</li>
     * </ul>
     * Picking exactly one source avoids the double-delivery race that would otherwise be possible
     * under {@code BOTH} when a block-derived sidecar differs byte-for-byte from its on-disk twin.
     * The legacy {@code path} is always retained (so {@link #drainUnseenSidecarsFromDisk()} has a
     * directory to scan during the final cleanup pass) even when the block-stream source is used.
     */
    public SidecarWatcher(
            @NonNull final Path path, @Nullable final Path blockStreamPath, final long shard, final long realm) {
        this.streamFilesPath = guaranteedExtantDir(path);
        this.blockStreamPath = blockStreamPath;
        this.shard = shard;
        this.realm = realm;
        if (blockStreamPath != null) {
            // Block-stream-only mode: skip the record-stream listener entirely; the legacy dir is
            // empty under streamMode=BLOCKS so it would deliver nothing anyway.
            this.unsubscribe = () -> {};
            this.unsubscribeBlocks = subscribeBlocks(blockStreamPath, shard, realm);
        } else {
            // Legacy mode: V6 sidecar files are the only source.
            this.unsubscribe = STREAM_FILE_ACCESS.subscribe(streamFilesPath, new StreamDataListener() {
                @Override
                public void onNewSidecar(@NonNull final TransactionSidecarRecord sidecar) {
                    enqueueActualSidecar(sidecar);
                }
            });
            this.unsubscribeBlocks = null;
        }
    }

    private Runnable subscribeBlocks(@NonNull final Path blockStreamPath, final long shard, final long realm) {
        final var translator = new BlockRecordTranslator(shard, realm);
        return STREAM_FILE_ACCESS.subscribe(guaranteedExtantDir(blockStreamPath), new StreamDataListener() {
            // Replay so genesis sidecars (emitted before this subscription) are still picked up.
            @Override
            public boolean replayExistingFiles() {
                return true;
            }

            @Override
            public void onNewBlock(@NonNull final Block block) {
                try {
                    pumpBlockSidecars(block, translator);
                } catch (final RuntimeException e) {
                    // Don't propagate: a single bad unit shouldn't kill the watcher; the
                    // expectations-vs-actual diff at the end will surface any real miss.
                    log.warn("Failed to translate block to sidecars; continuing", e);
                }
            }

            @Override
            public String name() {
                return "SidecarWatcher#blockStreamPump";
            }
        });
    }

    /**
     * Translates a single block into V6-shape {@link TransactionSidecarRecord}s and enqueues any not
     * already seen, returning the count newly enqueued. The {@code translator} is stateful and must be
     * fed blocks in order from genesis so alias/nonce state stays consistent.
     */
    private int pumpBlockSidecars(@NonNull final Block block, @NonNull final BlockRecordTranslator translator) {
        final var added = new AtomicInteger(0);
        translator.forEachRecord(
                block, record -> BlockRecordTranslator.protoSidecarsOf(record).forEach(sidecar -> {
                    if (enqueueActualSidecar(sidecar)) {
                        added.incrementAndGet();
                    }
                }));
        return added.get();
    }

    public static String stackTrace(Throwable t) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintStream p = new PrintStream(bos, true, US_ASCII);
        t.printStackTrace(p);
        return bos.toString(US_ASCII);
    }

    /**
     * Adds a new expected sidecar to the queue.
     *
     * @param newExpectedSidecar the new expected sidecar
     */
    public void addExpectedSidecar(@NonNull final ExpectedSidecar newExpectedSidecar) {
        this.expectedSidecars.add(newExpectedSidecar);
    }

    /**
     * Ensures that the sidecar watcher is unsubscribed from both the record stream and (if
     * configured) the block stream.
     */
    public void ensureUnsubscribed() {
        unsubscribe.run();
        if (unsubscribeBlocks != null) {
            unsubscribeBlocks.run();
        }
    }

    /**
     * Asserts that there are no mismatched sidecars and no pending sidecars in the
     * context of the given spec.
     *
     * @param spec the spec to assert within
     * @throws AssertionError if there are mismatched sidecars or pending sidecars
     */
    public void assertExpectations(@NonNull final HapiSpec spec) {
        // Ensure our listener has more than fair opportunity to observe all expected sidecars
        triggerAndCloseAtLeastOneFileIfNotInterrupted(spec);
        triggerAndCloseAtLeastOneFileIfNotInterrupted(spec);
        // Drain sidecars until we either satisfy all expectations, or a bounded timeout elapses.
        // We also synchronously scan sidecar files from disk to avoid relying only on asynchronous
        // file monitor callbacks, which can lag under debugger pauses or slow CI.
        final long deadlineNanos = System.nanoTime() + EXPECTATIONS_TIMEOUT_NANOS;
        while (!expectedSidecars.isEmpty() && System.nanoTime() < deadlineNanos) {
            final var drainedAtLeastOne = drainActualSidecars() || drainUnseenSidecarsFromStorage();
            if (expectedSidecars.isEmpty()) {
                break;
            }
            // If nothing new arrived yet, actively force another record/sidecar file closure cycle.
            if (!drainedAtLeastOne) {
                triggerAndCloseAtLeastOneFileIfNotInterrupted(spec);
            }
        }

        // If still pending, perform a few final forced close+drain attempts before failing.
        for (int i = 0; i < FINAL_DRAIN_ATTEMPTS && !expectedSidecars.isEmpty(); i++) {
            triggerAndCloseAtLeastOneFileIfNotInterrupted(spec);
            drainUnseenSidecarsFromStorage();
            drainActualSidecars();
        }

        // Stop listening for any more actual sidecars, then drain anything already queued.
        unsubscribe.run();
        if (unsubscribeBlocks != null) {
            unsubscribeBlocks.run();
        }
        drainUnseenSidecarsFromStorage();
        drainActualSidecars();

        assertTrue(thereAreNoMismatchedSidecars(), getMismatchErrors());
        assertTrue(
                thereAreNoPendingSidecars(),
                "There are some sidecars that have not been yet"
                        + " externalized in the sidecar files after all"
                        + " specs: " + getPendingErrors());
    }

    private boolean drainActualSidecars() {
        boolean drainedAtLeastOne = false;
        TransactionSidecarRecord actualSidecar;
        while ((actualSidecar = actualSidecars.poll()) != null) {
            drainedAtLeastOne = true;
            System.out.println("Observed sidecar: " + actualSidecar);
            final var expectedAtHead = expectedSidecars.peek();
            final boolean matchesConsensusTimestamp = expectedAtHead != null
                    && expectedAtHead.expectedSidecarRecord().matchesConsensusTimestampOf(actualSidecar);
            if (hasSeenFirstExpectedSidecar && matchesConsensusTimestamp) {
                assertIncomingSidecar(actualSidecar);
            } else {
                // sidecar records from different suites can be present in the sidecar
                // files before our first expected sidecar so skip sidecars until we reach
                // the first expected one in the queue
                if (expectedSidecars.isEmpty()) {
                    continue;
                }
                if (matchesConsensusTimestamp) {
                    hasSeenFirstExpectedSidecar = true;
                    assertIncomingSidecar(actualSidecar);
                }
            }
        }
        return drainedAtLeastOne;
    }

    private boolean enqueueActualSidecar(@NonNull final TransactionSidecarRecord sidecar) {
        synchronized (seenActualSidecars) {
            if (seenActualSidecars.add(sidecar)) {
                actualSidecars.add(sidecar);
                return true;
            }
            return false;
        }
    }

    private boolean drainUnseenSidecarsFromDisk() {
        try {
            final var streamData = STREAM_FILE_ACCESS.readStreamDataFrom(streamFilesPath.toString(), "sidecar");
            int added = 0;
            for (final var recordWithSidecars : streamData.records()) {
                for (final var sidecarFile : recordWithSidecars.sidecarFiles()) {
                    for (final var sidecar : sidecarFile.getSidecarRecordsList()) {
                        synchronized (seenActualSidecars) {
                            if (seenActualSidecars.add(sidecar)) {
                                actualSidecars.add(sidecar);
                                added++;
                            }
                        }
                    }
                }
            }
            return added > 0;
        } catch (final UncheckedIOException | IllegalArgumentException e) {
            // Sidecar files may still be in-flight while this assertion loop is polling.
            return false;
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * Synchronous fallback for block-stream mode, mirroring {@link #drainUnseenSidecarsFromDisk()}:
     * re-reads all completed blocks from disk and re-derives their sidecars, so a missed asynchronous
     * {@code onNewBlock} callback (e.g. from CI marker-file visibility lag) cannot leave expectations
     * falsely pending. Uses a fresh translator replayed from genesis each call; {@link #enqueueActualSidecar}
     * dedups against sidecars the live listener already delivered.
     *
     * @return true if at least one previously-unseen sidecar was enqueued
     */
    private boolean drainUnseenSidecarsFromBlocks() {
        if (blockStreamPath == null) {
            return false;
        }
        try {
            // Only marker-backed (fully written) blocks, so we never translate a half-flushed block.
            final var blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(blockStreamPath);
            final var translator = new BlockRecordTranslator(shard, realm);
            int added = 0;
            for (final var block : blocks) {
                added += pumpBlockSidecars(block, translator);
            }
            return added > 0;
        } catch (final UncheckedIOException | IllegalArgumentException e) {
            // Block files may still be in-flight while this assertion loop is polling.
            return false;
        } catch (final RuntimeException e) {
            log.warn("Failed to re-scan block stream for sidecars; continuing", e);
            return false;
        }
    }

    /**
     * Drains unseen sidecars from whichever durable source backs this watcher: the block stream
     * (BLOCKS mode) or the legacy V6 sidecar files (RECORDS/BOTH mode).
     */
    private boolean drainUnseenSidecarsFromStorage() {
        return blockStreamPath != null ? drainUnseenSidecarsFromBlocks() : drainUnseenSidecarsFromDisk();
    }

    private void assertIncomingSidecar(final TransactionSidecarRecord actualSidecarRecord) {
        // there should always be an expected sidecar at this point,
        // if the queue is empty here, the specs have missed a sidecar
        // and must be updated to account for it
        if (expectedSidecars.isEmpty()) {
            Assertions.fail("No expected sidecar found for incoming sidecar: %s".formatted(actualSidecarRecord));
        }
        final var expectedSidecar = expectedSidecars.poll();
        final var expectedSidecarRecord = expectedSidecar.expectedSidecarRecord();

        if (!expectedSidecar.expectedSidecarRecord().matches(actualSidecarRecord)) {
            final var spec = expectedSidecar.spec();
            failedSidecars.computeIfAbsent(spec, k -> new ArrayList<>());
            failedSidecars.get(spec).add(new MismatchedSidecar(expectedSidecarRecord, actualSidecarRecord));
        }
    }

    private boolean thereAreNoMismatchedSidecars() {
        return failedSidecars.isEmpty();
    }

    private String getMismatchErrors() {
        return getMismatchErrors(pair -> true);
    }

    private String getMismatchErrors(Predicate<MismatchedSidecar> filter) {
        final var messageBuilder = new StringBuilder();
        messageBuilder.append("Mismatch(es) between actual/expected sidecars present: ");
        for (final var kv : failedSidecars.entrySet()) {
            final var faultySidecars = kv.getValue().stream().filter(filter).toList();
            messageBuilder
                    .append("\n\n")
                    .append(faultySidecars.size())
                    .append(" SIDECAR MISMATCH(ES) in SPEC {")
                    .append(kv.getKey())
                    .append("}:");
            int i = 1;
            for (final var pair : faultySidecars) {
                messageBuilder
                        .append("\n******FAILURE #")
                        .append(i++)
                        .append("******\n")
                        .append("***Expected sidecar***\n")
                        .append(pair.expectedSidecarRecordMatcher().toSidecarRecord())
                        .append("***Actual sidecar***\n")
                        .append(pair.actualSidecarRecord());
            }
        }
        return messageBuilder.toString();
    }

    private boolean thereAreNoPendingSidecars() {
        return expectedSidecars.isEmpty();
    }

    private String getPendingErrors() {
        final var messageBuilder = new StringBuilder();
        messageBuilder.append("Pending sidecars not yet seen: ");
        int i = 1;
        for (final var pendingSidecar : expectedSidecars) {
            messageBuilder
                    .append("\n****** PENDING #")
                    .append(i++)
                    .append("******\n")
                    .append("*** Pending sidecar***\n")
                    .append(pendingSidecar.spec())
                    .append(": ")
                    .append(pendingSidecar.expectedSidecarRecord());
        }
        return messageBuilder.toString();
    }
}

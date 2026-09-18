// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.failure;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.FailureBlockStagingConfig;
import com.hedera.node.config.types.BlockStreamWriterMode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.notification.IssNotification.IssType;

/**
 * Captures the exact ISS-round block and stages it to the node-local {@code issBlockDir} so the deployment's stream
 * uploader (a separate process that watches that bind-mounted directory) ships it to the bucket for triage. The node
 * itself does no upload and holds no bucket credentials. Capture happens from two trigger points that together cover
 * both halting and non-halting ISSes:
 *
 * <ol>
 *   <li><b>At detection</b> ({@link #captureAndStage}, from {@code FatalIssListenerImpl.notify(...)} on the platform's
 *   async ISS-notification dispatcher): locates the block from the in-memory buffer in {@code GRPC} mode (where closed
 *   blocks are never written to disk and the block would soon be pruned), or by polling local disk in {@code FILE}/
 *   {@code FILE_AND_GRPC} mode until the block becomes durable. This handles a <i>non-halting</i> ISS — which never
 *   reaches {@code CATASTROPHIC_FAILURE}. The poll is bounded by {@code captureTimeout} and runs off the consensus hot
 *   path.</li>
 *   <li><b>At {@code CATASTROPHIC_FAILURE}</b> ({@link #stageDetectedIssOnFailure}, called synchronously from
 *   {@code Hedera.newPlatformStatus} after {@code awaitFatalShutdown} has flushed the open/pending blocks to disk and
 *   <b>before</b> the block-node connections are shut down): resolves the recorded ISS round's block <i>once</i> (no
 *   polling) from the correct source for the writer mode and stages it. Running before the buffer is cleared keeps the
 *   in-memory buffer available for the <i>halting</i> case. It relies on the async detection above having recorded the
 *   ISS first; the ISS notification is soldered after the status monitor, so if it has not (a fast halt) this logs at
 *   {@code FATAL} and stages nothing. {@code awaitFatalShutdown} having run first makes that miss unlikely.</li>
 * </ol>
 *
 * <p>The two paths de-duplicate via {@link #stagedRounds}: whichever stages the round first marks it; the failure path is
 * the authoritative last-chance capture and stages into its own subdir so it never clobbers a detection capture that is
 * still writing. Each artifact is written atomically (see {@link StagingFiles}) so the uploader never sees a partial
 * file. Best-effort throughout; never throws.
 */
@Singleton
public class IssDetectionStagingCoordinator {
    private static final Logger log = LogManager.getLogger(IssDetectionStagingCoordinator.class);

    /** How often the detection path re-checks disk while waiting for the ISS-round block to become durable. */
    private static final long POLL_INTERVAL_MS = 250L;

    /**
     * Per-path staging subdirectories under a shared incident dir. The detection path and the CATASTROPHIC_FAILURE path
     * can capture the SAME round concurrently; staging each into its own subdir keeps one from clobbering a file the
     * other is mid-write.
     */
    private static final String STAGE_DETECTION = "detect";

    private static final String STAGE_FAILURE = "failure";

    private final ConfigProvider configProvider;
    private final IssBlockResolver diskResolver;
    private final IssBufferBlockReader bufferReader;
    private final SelfNodeAccountIdManager selfNodeAccountIdManager;
    private final FileSystem fileSystem;
    private final InstantSource instantSource;
    private final BlockBufferService blockBufferService;
    /** Runs the detection-time capture off the ISS-notification dispatcher (a virtual thread per ISS in production). */
    private final Executor captureExecutor;

    /**
     * The FIRST detected fatal ISS, recorded once at detection so the {@code CATASTROPHIC_FAILURE} path stages the same
     * (earliest divergent) round under the same incident folder. A diverged node re-notifies every round until it halts;
     * only the first is kept (see {@link #captureAndStage}).
     */
    private final AtomicReference<RecordedIss> firstIss = new AtomicReference<>();
    /** ISS rounds already staged, so the detection and failure paths never double-stage a round. */
    private final Set<Long> stagedRounds = ConcurrentHashMap.newKeySet();

    private record RecordedIss(
            @NonNull IssType issType, long round, @NonNull String incidentFolder) {}

    @Inject
    public IssDetectionStagingCoordinator(
            @NonNull final ConfigProvider configProvider,
            @NonNull final IssBlockResolver diskResolver,
            @NonNull final IssBufferBlockReader bufferReader,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager,
            @NonNull final FileSystem fileSystem,
            @NonNull final InstantSource instantSource,
            @NonNull final BlockBufferService blockBufferService) {
        // Detection-time capture runs on a virtual thread per ISS so the ORDERED async ISS-notification dispatcher
        // (which calls captureAndStage) is never blocked by the bounded disk poll.
        this(
                configProvider,
                diskResolver,
                bufferReader,
                selfNodeAccountIdManager,
                fileSystem,
                instantSource,
                blockBufferService,
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("iss-block-capture-", 0).factory()));
    }

    // visible for testing: a direct executor lets a test run the capture synchronously
    IssDetectionStagingCoordinator(
            @NonNull final ConfigProvider configProvider,
            @NonNull final IssBlockResolver diskResolver,
            @NonNull final IssBufferBlockReader bufferReader,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager,
            @NonNull final FileSystem fileSystem,
            @NonNull final InstantSource instantSource,
            @NonNull final BlockBufferService blockBufferService,
            @NonNull final Executor captureExecutor) {
        this.configProvider = requireNonNull(configProvider);
        this.diskResolver = requireNonNull(diskResolver);
        this.bufferReader = requireNonNull(bufferReader);
        this.selfNodeAccountIdManager = requireNonNull(selfNodeAccountIdManager);
        this.fileSystem = requireNonNull(fileSystem);
        this.instantSource = requireNonNull(instantSource);
        this.blockBufferService = requireNonNull(blockBufferService);
        this.captureExecutor = requireNonNull(captureExecutor);
    }

    /**
     * Detection-time capture (async ISS dispatcher). Records the ISS for the failure path, then locates and stages the
     * ISS-round block: from the buffer in {@code GRPC} mode, or by polling disk (bounded by {@code captureTimeout}) in
     * {@code FILE}/{@code FILE_AND_GRPC} mode. Best-effort; never throws.
     *
     * @param issType the ISS type that was detected
     * @param round the ISS round
     */
    public void captureAndStage(@NonNull final IssType issType, final long round) {
        try {
            final var config = configProvider.getConfiguration().getConfigData(FailureBlockStagingConfig.class);
            if (!config.issBlockStagingEnabled()) {
                return;
            }
            // A diverged node emits a fatal-ISS notification every round until it halts. Record only the FIRST one (the
            // earliest divergent round is the block worth triaging) and pin its incident folder, so the async capture
            // here and the CATASTROPHIC_FAILURE path both group under ONE directory and target the same round, rather
            // than a fresh timestamp folder and a later round per notification.
            final RecordedIss iss = new RecordedIss(issType, round, StagingFiles.incidentFolderNow(instantSource));
            if (!firstIss.compareAndSet(null, iss)) {
                return;
            }
            // Offload the blocking disk poll (resolveWithWait polls up to captureTimeout) off the ORDERED async
            // ISS-notification dispatcher: blocking it would stall later ISS notifications.
            captureExecutor.execute(() -> doCaptureAndStage(config, iss));
        } catch (final Throwable t) {
            log.error("ISS detection-time block capture failed for round {}", round, t);
        }
    }

    /** The blocking capture, run off the ISS dispatcher by {@link #captureAndStage}. Best-effort; never throws. */
    private void doCaptureAndStage(@NonNull final FailureBlockStagingConfig config, @NonNull final RecordedIss iss) {
        try {
            if (stagedRounds.contains(iss.round())) {
                return;
            }
            final Path incidentDir =
                    incidentDirFor(config, iss.incidentFolder()).resolve(STAGE_DETECTION);
            // Detection can run before the block is durable on disk (FILE modes), so poll until it is.
            final List<Path> files = capture(config, iss.issType(), iss.round(), incidentDir, true);
            markStaged(iss.round(), files);
        } catch (final Throwable t) {
            log.error("ISS detection-time block capture failed for round {}", iss.round(), t);
        }
    }

    /**
     * Synchronous capture on {@code CATASTROPHIC_FAILURE}, invoked from {@code Hedera.newPlatformStatus} after
     * {@code awaitFatalShutdown} has flushed the open/pending blocks to disk. Resolves the recorded ISS round's block
     * <i>once</i> (no polling) and stages it, unless the detection path already did. Best-effort, never throws — must not
     * stall the halt.
     *
     * <p><b>Must run before {@code blockNodeConnectionManager.shutdown()}</b>: in {@code GRPC} mode a closed, proven ISS
     * block is never written to disk — it lives only in the in-memory buffer, which that shutdown clears.
     */
    public void stageDetectedIssOnFailure() {
        try {
            final var config = configProvider.getConfiguration().getConfigData(FailureBlockStagingConfig.class);
            if (!config.issBlockStagingEnabled()) {
                return;
            }
            final RecordedIss iss = firstIss.get();
            if (iss == null) {
                // The async ISS notification is soldered AFTER the status monitor (see ConsensusLayerWiring), so on a
                // fast halt no ISS may have been recorded before this ran (unlikely, since awaitFatalShutdown ran
                // first). Surface it rather than silently staging nothing.
                log.fatal(
                        "Reached CATASTROPHIC_FAILURE with no ISS round recorded; the exact ISS block was not staged");
                return;
            }
            if (stagedRounds.contains(iss.round())) {
                return;
            }
            final Path incidentDir =
                    incidentDirFor(config, iss.incidentFolder()).resolve(STAGE_FAILURE);
            // On the halt path the block is already flushed to disk; resolve ONCE (no polling) so we never stall the
            // shutdown waiting for a block that will not appear.
            final List<Path> files = capture(config, iss.issType(), iss.round(), incidentDir, false);
            final boolean staged = markStaged(iss.round(), files);
            // Authoritative last-chance capture for a halting ISS: surface ONE distinct high-severity signal if nothing
            // was preserved, instead of only the routine-looking WARNs from the individual steps.
            if (!staged) {
                log.fatal(
                        "ISS block for round {} was NOT staged to {}; only a best-effort pointer (if any) is available, "
                                + "so the exact ISS block may be unavailable for triage",
                        iss.round(),
                        config.issBlockDir());
            }
        } catch (final Throwable t) {
            log.error("ISS block staging on catastrophic failure failed", t);
        }
    }

    /**
     * Resolves and stages the ISS-round block (plus any preceding context) into {@code incidentDir} for the current
     * writer mode: from disk in {@code FILE}/{@code FILE_AND_GRPC}, or from the in-memory buffer in {@code GRPC}. When
     * {@code pollForDurability} the disk resolve retries until the block is durable (the detection path, where it may
     * still be the open block); otherwise it resolves once (the halt path, which must not stall). Returns the staged
     * contents paths (ISS block last), or a single {@code .txt} pointer as a last-resort {@code GRPC} fallback, or empty
     * if nothing could be staged.
     */
    private List<Path> capture(
            @NonNull final FailureBlockStagingConfig config,
            @NonNull final IssType issType,
            final long round,
            @NonNull final Path incidentDir,
            final boolean pollForDurability) {
        final var writerMode = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .writerMode();
        List<Path> files =
                switch (writerMode) {
                    // FILE modes resolve from disk. The detection path polls until the (possibly still-open) block
                    // becomes durable; the halt path resolves once (the block was already flushed, and we must not
                    // stall the shutdown waiting for one that will not appear).
                    case FILE, FILE_AND_GRPC ->
                        materializeFromDisk(resolveFromDisk(config, round, pollForDurability), incidentDir);
                    // The ISS-round block is normally still buffered: a block node never acknowledges a diverged ISS
                    // block, and an unacknowledged block is not pruned under BLOCKS-mode back pressure. Capture it if
                    // present, else fall through to the pointer marker below.
                    case GRPC -> bufferReader.captureToDir(round, config.precedingBlocks(), incidentDir);
                };
        // GRPC last-resort fallback: if the ISS block is somehow NOT in the buffer, stage a plain-text pointer instead
        // of preserving nothing. Under normal operation this should NOT be reached (see above) — it is a regression
        // guard, logged at WARN.
        if (writerMode == BlockStreamWriterMode.GRPC && files.isEmpty()) {
            files = markerFilesFor(config, issType, round, writerMode, incidentDir);
        }
        return files;
    }

    /**
     * Marks the round staged when the exact ISS block was captured (the last entry; earlier entries are best-effort
     * preceding context). A {@code .txt} pointer alone does NOT count as staged, so the {@code CATASTROPHIC_FAILURE}
     * path can still recover the real block (normally still buffered then). Returns whether the round is now staged.
     */
    private boolean markStaged(final long round, @NonNull final List<Path> files) {
        final boolean blockStaged =
                files.stream().anyMatch(p -> !p.getFileName().toString().endsWith(StagingFiles.POINTER_EXT));
        if (blockStaged) {
            stagedRounds.add(round);
            log.warn("Staged ISS round {} for triage: {}", round, files);
            return true;
        }
        if (files.isEmpty()) {
            log.warn("No ISS block staged for round {}", round);
        }
        return stagedRounds.contains(round);
    }

    /**
     * The incident folder recorded for the first detected fatal ISS, or {@code null} if none — lets the triage staging
     * group its files under the SAME per-incident folder as the exact ISS block.
     */
    @Nullable
    public String currentIncidentFolder() {
        final RecordedIss iss = firstIss.get();
        return iss == null ? null : iss.incidentFolder();
    }

    private Path incidentDirFor(@NonNull final FailureBlockStagingConfig config, @NonNull final String incidentFolder) {
        return StagingFiles.incidentDir(
                fileSystem,
                config.issBlockDir(),
                asAccountString(selfNodeAccountIdManager.getSelfNodeAccountId()),
                incidentFolder);
    }

    /** The pointer-marker file name for an ISS round whose block is no longer in the buffer. */
    private static String markerFileName(final long round) {
        return "iss-round-" + round + StagingFiles.POINTER_EXT;
    }

    /**
     * GRPC best-effort fallback: writes a plain-text pointer marker (atomically) for an ISS round whose block is no
     * longer in the buffer into {@code stageDir}, and returns it as the single staged file. The marker records local
     * diagnostic state (the ISS round, writer mode, and buffer watermarks at capture time) so an operator has a
     * breadcrumb even though the block itself could not be preserved. Returns an empty list if the marker cannot be
     * written; best-effort, never throws.
     */
    private List<Path> markerFilesFor(
            @NonNull final FailureBlockStagingConfig config,
            @NonNull final IssType issType,
            final long round,
            @NonNull final BlockStreamWriterMode writerMode,
            @NonNull final Path stageDir) {
        log.warn(
                "ISS round {} block is not in the block buffer; staging a pointer marker (with the data to locate it on "
                        + "the block node) instead of the block",
                round);
        try {
            Files.createDirectories(stageDir);
            final Path marker = stageDir.resolve(markerFileName(round));
            final Path tmp = StagingFiles.tmpFor(marker);
            Files.writeString(tmp, buildMarkerContent(issType, round, writerMode));
            StagingFiles.atomicMoveOnto(tmp, marker);
            return List.of(marker);
        } catch (final Exception e) {
            log.warn("Could not write ISS pointer marker for round {} into {}", round, stageDir, e);
            return List.of();
        }
    }

    /**
     * Builds the plain-text {@code key=value} body of a pointer marker. Defensive: it reads only non-throwing buffer
     * snapshots, so it does not throw on the ISS/halt path. Local diagnostic state only — a diverged ISS block never
     * gathers a valid proof, so it is never acknowledged or persisted by the block node and cannot be fetched from it.
     */
    private String buildMarkerContent(
            @NonNull final IssType issType, final long round, @NonNull final BlockStreamWriterMode writerMode) {
        final StringBuilder sb = new StringBuilder(384);
        sb.append(
                "# ISS block pointer - the ISS-round block was NOT in the in-memory buffer at capture time and could\n");
        sb.append("# not be preserved locally. Diagnostic metadata only: a diverged ISS block never gathers a valid\n");
        sb.append(
                "# proof, so it is never acknowledged/persisted by the block node and is not recoverable from it.\n\n");
        sb.append("issType=").append(issType).append('\n');
        sb.append("issRound=").append(round).append('\n');
        sb.append("writerMode=").append(writerMode).append('\n');
        sb.append("selfNodeAccount=")
                .append(asAccountString(selfNodeAccountIdManager.getSelfNodeAccountId()))
                .append('\n');
        sb.append("capturedAt=").append(instantSource.instant()).append("\n\n");
        sb.append("# Local in-memory block-buffer state on this node at capture time.\n");
        sb.append("# The block containing the ISS round is older than the earliest buffered block (already pruned "
                + "locally).\n");
        sb.append("bufferEarliestBlock=")
                .append(blockBufferService.getEarliestAvailableBlockNumber())
                .append('\n');
        sb.append("bufferLastProducedBlock=")
                .append(blockBufferService.getLastBlockNumberProduced())
                .append('\n');
        sb.append("highestAckedBlock=")
                .append(blockBufferService.getHighestAckedBlockNumber())
                .append('\n');
        return sb.toString();
    }

    /**
     * Resolves the ISS block from disk: the detection path ({@code pollForDurability}) polls until it becomes durable;
     * the authoritative {@code CATASTROPHIC_FAILURE} one-shot resolves once, first dropping any negative-cache entry
     * poisoned by a transient read during the poll so it always re-reads. Kept on the disk resolver, so {@code GRPC}
     * mode never touches it.
     */
    private List<IssBlockRef> resolveFromDisk(
            @NonNull final FailureBlockStagingConfig config, final long round, final boolean pollForDurability) {
        if (pollForDurability) {
            return resolveWithWait(round, config.precedingBlocks(), config.captureTimeout());
        }
        diskResolver.forgetUnreadable();
        return diskResolver.resolve(round, config.precedingBlocks());
    }

    /**
     * Resolves the ISS-round block from disk, retrying until it is found or {@code timeout} elapses. The block may be
     * the still-open block at detection (not yet a finished file on disk); it becomes durable as rounds continue, or is
     * flushed as a {@code .open.gz} at {@code CATASTROPHIC_FAILURE}. Polling makes the capture deterministic instead of a
     * one-shot miss.
     */
    private List<IssBlockRef> resolveWithWait(
            final long round, final int precedingBlocks, @NonNull final Duration timeout) {
        // nanoTime (monotonic), not currentTimeMillis, so an NTP step or leap second cannot shorten or extend the wait.
        final long deadlineNs = System.nanoTime() + timeout.toNanos();
        while (true) {
            final List<IssBlockRef> refs = diskResolver.resolve(round, precedingBlocks);
            if (!refs.isEmpty() || System.nanoTime() - deadlineNs >= 0) {
                return refs;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return refs;
            }
        }
    }

    /**
     * Copies each resolved block's on-disk files (the contents file plus any {@code .pnd.json} proof sidecar) into
     * {@code issDir} atomically, and returns the copied <b>contents</b> paths. Per-block best-effort: a preceding context
     * block (or a sidecar) that can no longer be copied — e.g. deleted by block retention cleanup between resolve and
     * copy — is skipped rather than aborting the capture; but if the ISS block itself (the last entry) cannot be copied,
     * the whole capture is discarded so the caller never stages a context block in its place and never marks the round
     * done without the exact ISS block.
     */
    private List<Path> materializeFromDisk(@NonNull final List<IssBlockRef> refs, @NonNull final Path issDir) {
        if (refs.isEmpty()) {
            return List.of();
        }
        try {
            Files.createDirectories(issDir);
        } catch (final IOException e) {
            log.warn("Cannot create ISS staging dir {}; nothing to stage", issDir, e);
            return List.of();
        }
        final List<Path> contents = new ArrayList<>(refs.size());
        for (int i = 0; i < refs.size(); i++) {
            final IssBlockRef ref = refs.get(i);
            final List<Path> files = ref.files();
            final Path contentsSrc = files.get(0); // the contents file is first; any .pnd.json sidecar follows
            final Path contentsDest = issDir.resolve(contentsSrc.getFileName().toString());
            try {
                StagingFiles.atomicCopy(contentsSrc, contentsDest);
            } catch (final IOException e) {
                if (i == refs.size() - 1) {
                    log.warn(
                            "ISS block #{} could not be staged from {}; discarding the capture",
                            ref.blockNumber(),
                            contentsSrc,
                            e);
                    return List.of();
                }
                log.warn("Skipping preceding context block #{}: could not stage {}", ref.blockNumber(), contentsSrc, e);
                continue;
            }
            for (int j = 1; j < files.size(); j++) {
                final Path sidecar = files.get(j);
                try {
                    StagingFiles.atomicCopy(
                            sidecar, issDir.resolve(sidecar.getFileName().toString()));
                } catch (final IOException e) {
                    log.warn("Skipping sidecar {} of block #{}", sidecar, ref.blockNumber(), e);
                }
            }
            contents.add(contentsDest);
        }
        return contents;
    }
}

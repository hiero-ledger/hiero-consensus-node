// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.FailureBlockUploadConfig;
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
import java.util.Optional;
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
 * itself does no upload and holds no bucket credentials. Capture happens from two trigger points that together make it
 * deterministic for both halting and non-halting ISSes:
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
 *   polling) from the correct source for the writer mode and stages it. Running before the buffer is cleared makes the
 *   <i>halting</i> case race-free.</li>
 * </ol>
 *
 * <p>The two paths de-duplicate via {@link #stagedRounds}: whichever stages the round first marks it; the failure path is
 * the authoritative last-chance capture and stages into its own subdir so it never clobbers a detection capture that is
 * still writing. Each artifact is written atomically (see {@link StagingFiles}) so the uploader never sees a partial
 * file. Best-effort throughout; never throws.
 */
@Singleton
public class IssDetectionUploadCoordinator {
    private static final Logger log = LogManager.getLogger(IssDetectionUploadCoordinator.class);

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
    private final BlockNodeConnectionManager blockNodeConnectionManager;
    /** Runs the detection-time capture off the ISS-notification dispatcher (a virtual thread per ISS in production). */
    private final Executor captureExecutor;

    /** The latest detected fatal ISS, recorded at detection so the {@code CATASTROPHIC_FAILURE} path can stage it. */
    private final AtomicReference<RecordedIss> lastIss = new AtomicReference<>();
    /** ISS rounds already staged, so the detection and failure paths never double-stage a round. */
    private final Set<Long> stagedRounds = ConcurrentHashMap.newKeySet();

    private record RecordedIss(
            @NonNull IssType issType, long round, @NonNull String incidentFolder) {}

    @Inject
    public IssDetectionUploadCoordinator(
            @NonNull final ConfigProvider configProvider,
            @NonNull final IssBlockResolver diskResolver,
            @NonNull final IssBufferBlockReader bufferReader,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager,
            @NonNull final FileSystem fileSystem,
            @NonNull final InstantSource instantSource,
            @NonNull final BlockBufferService blockBufferService,
            @NonNull final BlockNodeConnectionManager blockNodeConnectionManager) {
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
                blockNodeConnectionManager,
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("iss-block-capture-", 0).factory()));
    }

    // visible for testing: a direct executor lets a test run the capture synchronously
    IssDetectionUploadCoordinator(
            @NonNull final ConfigProvider configProvider,
            @NonNull final IssBlockResolver diskResolver,
            @NonNull final IssBufferBlockReader bufferReader,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager,
            @NonNull final FileSystem fileSystem,
            @NonNull final InstantSource instantSource,
            @NonNull final BlockBufferService blockBufferService,
            @NonNull final BlockNodeConnectionManager blockNodeConnectionManager,
            @NonNull final Executor captureExecutor) {
        this.configProvider = requireNonNull(configProvider);
        this.diskResolver = requireNonNull(diskResolver);
        this.bufferReader = requireNonNull(bufferReader);
        this.selfNodeAccountIdManager = requireNonNull(selfNodeAccountIdManager);
        this.fileSystem = requireNonNull(fileSystem);
        this.instantSource = requireNonNull(instantSource);
        this.blockBufferService = requireNonNull(blockBufferService);
        this.blockNodeConnectionManager = requireNonNull(blockNodeConnectionManager);
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
            final var config = configProvider.getConfiguration().getConfigData(FailureBlockUploadConfig.class);
            if (!config.issBlockUploadEnabled()) {
                return;
            }
            // One folder per ISS event. Record it synchronously — before offloading — so the CATASTROPHIC_FAILURE path
            // reuses the same folder and round even while the capture below is still running.
            final String incidentFolder = StagingFiles.incidentFolderNow(instantSource);
            lastIss.set(new RecordedIss(issType, round, incidentFolder));
            if (stagedRounds.contains(round)) {
                return;
            }
            // Offload the blocking disk poll (resolveWithWait polls up to captureTimeout) off the ORDERED async
            // ISS-notification dispatcher: blocking it would stall later ISS notifications.
            captureExecutor.execute(() -> doCaptureAndStage(config, issType, round, incidentFolder));
        } catch (final Throwable t) {
            log.error("ISS detection-time block capture failed for round {}", round, t);
        }
    }

    /** The blocking capture, run off the ISS dispatcher by {@link #captureAndStage}. Best-effort; never throws. */
    private void doCaptureAndStage(
            @NonNull final FailureBlockUploadConfig config,
            @NonNull final IssType issType,
            final long round,
            @NonNull final String incidentFolder) {
        try {
            if (stagedRounds.contains(round)) {
                return;
            }
            final Path incidentDir = incidentDirFor(config, incidentFolder).resolve(STAGE_DETECTION);
            // Detection can run before the block is durable on disk (FILE modes), so poll until it is.
            final List<Path> files = capture(config, issType, round, incidentDir, true);
            markStaged(round, files);
        } catch (final Throwable t) {
            log.error("ISS detection-time block capture failed for round {}", round, t);
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
            final var config = configProvider.getConfiguration().getConfigData(FailureBlockUploadConfig.class);
            if (!config.issBlockUploadEnabled()) {
                return;
            }
            final RecordedIss iss = lastIss.get();
            if (iss == null || stagedRounds.contains(iss.round())) {
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
                        "ISS block for round {} was NOT staged to {}; the exact ISS block may be unavailable for triage",
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
            @NonNull final FailureBlockUploadConfig config,
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
                        materializeFromDisk(
                                pollForDurability
                                        ? resolveWithWait(
                                                issType, round, config.precedingBlocks(), config.captureTimeout())
                                        : diskResolver.resolve(issType, round, config.precedingBlocks()),
                                incidentDir);
                    // The ISS-round block is expected to still be buffered: a self-ISS block's divergent root hash
                    // never
                    // gathers a threshold block proof, so it is never closed and (since only closed blocks are pruned)
                    // never pruned. Capture it if present, else fall through to the pointer marker below.
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
     * preceding context). Returns whether the round is now considered staged.
     */
    private boolean markStaged(final long round, @NonNull final List<Path> files) {
        if (files.isEmpty()) {
            log.warn("No ISS block staged for round {}", round);
            return stagedRounds.contains(round);
        }
        stagedRounds.add(round);
        log.warn("Staged ISS round {} for upload: {}", round, files);
        return true;
    }

    /**
     * The incident folder recorded for the latest detected fatal ISS, or {@code null} if none — lets the triage staging
     * group its files under the SAME per-incident folder as the exact ISS block.
     */
    @Nullable
    public String currentIncidentFolder() {
        final RecordedIss iss = lastIss.get();
        return iss == null ? null : iss.incidentFolder();
    }

    private Path incidentDirFor(@NonNull final FailureBlockUploadConfig config, @NonNull final String incidentFolder) {
        return StagingFiles.incidentDir(
                fileSystem,
                config.issBlockDir(),
                asAccountString(selfNodeAccountIdManager.getSelfNodeAccountId()),
                incidentFolder);
    }

    /** The pointer-marker file name for an ISS round whose block is no longer in the buffer. */
    private static String markerFileName(final long round) {
        return "iss-round-" + round + ".txt";
    }

    /**
     * GRPC best-effort fallback: writes a plain-text pointer marker (atomically) for an ISS round whose block is no
     * longer in the buffer into {@code stageDir}, and returns it as the single staged file. The marker records where the
     * block was streamed and how far the block node acknowledged, so an operator can fetch it from the block node.
     * Returns an empty list if the marker cannot be written; best-effort, never throws.
     */
    private List<Path> markerFilesFor(
            @NonNull final FailureBlockUploadConfig config,
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
     * Builds the plain-text {@code key=value} body of a pointer marker. Defensive: it reads only non-throwing buffer and
     * connection-manager snapshots and renders a placeholder when there is no active connection, so it does not throw on
     * the ISS/halt path.
     */
    private String buildMarkerContent(
            @NonNull final IssType issType, final long round, @NonNull final BlockStreamWriterMode writerMode) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("# ISS block pointer - the ISS-round block was NOT in the in-memory buffer at capture time.\n");
        sb.append("# Written best-effort so the block can still be located on the block node.\n\n");
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
                .append("\n\n");
        sb.append("# Active block-node connection at capture time (where blocks were streamed / persisted).\n");
        sb.append("# Blocks <= lastBlockAckedByBlockNode are persisted and verified by this block node - fetch the "
                + "ISS-round block from it.\n");
        final Optional<BlockNodeConnectionManager.ActiveBlockNodeSnapshot> snapshot =
                blockNodeConnectionManager.activeConnectionSnapshot();
        if (snapshot.isPresent()) {
            final BlockNodeConnectionManager.ActiveBlockNodeSnapshot s = snapshot.get();
            sb.append("activeBlockNode=")
                    .append(s.host())
                    .append(':')
                    .append(s.port())
                    .append('\n');
            sb.append("activeBlockNodePriority=").append(s.priority()).append('\n');
            sb.append("lastBlockSentToBlockNode=").append(s.lastBlockSent()).append('\n');
            sb.append("lastBlockAckedByBlockNode=").append(s.lastBlockAcked()).append('\n');
        } else {
            sb.append("activeBlockNode=<none: no active block-node connection at capture time>\n");
        }
        return sb.toString();
    }

    /**
     * Resolves the ISS-round block from disk, retrying until it is found or {@code timeout} elapses. The block may be
     * the still-open block at detection (not yet a finished file on disk); it becomes durable as rounds continue, or is
     * flushed as a {@code .open.gz} at {@code CATASTROPHIC_FAILURE}. Polling makes the capture deterministic instead of a
     * one-shot miss.
     */
    private List<IssBlockRef> resolveWithWait(
            @NonNull final IssType issType,
            final long round,
            final int precedingBlocks,
            @NonNull final Duration timeout) {
        // nanoTime (monotonic), not currentTimeMillis, so an NTP step or leap second cannot shorten or extend the wait.
        final long deadlineNs = System.nanoTime() + timeout.toNanos();
        while (true) {
            final List<IssBlockRef> refs = diskResolver.resolve(issType, round, precedingBlocks);
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

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.FailureBlockUploadConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.notification.IssNotification.IssType;

/**
 * Captures the exact ISS-round block and uploads it to the {@code iss/} bucket folder for triage, from two trigger
 * points that together make the capture deterministic for both halting and non-halting ISSes:
 *
 * <ol>
 *   <li><b>At detection</b> ({@link #captureAndUpload}, from {@code FatalIssListenerImpl.notify(...)} on the platform's
 *   async ISS-notification dispatcher): locates the block from the in-memory buffer in {@code GRPC} mode (where closed
 *   blocks are never written to disk and the block would soon be pruned), or by polling local disk in {@code FILE}/
 *   {@code FILE_AND_GRPC} mode until the block becomes durable (it may still be open at detection). This handles a
 *   <i>non-halting</i> ISS — which never reaches {@code CATASTROPHIC_FAILURE} — and grabs the gRPC buffer before it is
 *   pruned. The poll is bounded by {@code captureTimeout} and runs off the consensus hot path.</li>
 *   <li><b>At {@code CATASTROPHIC_FAILURE}</b> ({@link #uploadDetectedIssOnFailure}, called synchronously from
 *   {@code Hedera.newPlatformStatus} after {@code awaitFatalShutdown} has flushed the open/pending blocks to disk and
 *   <b>before</b> the block-node connections are shut down): resolves the recorded ISS round's block <i>once</i> (no
 *   polling) from the correct source for the writer mode — disk in {@code FILE}/{@code FILE_AND_GRPC}, or the in-memory
 *   buffer in {@code GRPC} (where a closed block is never on disk and the connection shutdown would soon clear the
 *   buffer) — and uploads it. Running on the status thread before the node halts makes the <i>halting</i> case
 *   race-free, with no indefinite wait (bounded by {@code uploadTimeout}).</li>
 * </ol>
 *
 * <p>The two paths de-duplicate via {@link #uploadedRounds}: whichever uploads the round first marks it. On a
 * concurrent attempt for the SAME round, the detection path skips, while the failure path — the authoritative,
 * last-chance capture — awaits the in-flight outcome (bounded) and retries itself if that attempt failed. The
 * captured block is staged under a per-incident timestamp dir of the node-local {@code issBlockDir} (kept
 * for local triage, mirroring the {@code iss/{timestamp}/} cloud layout). This is distinct from
 * {@code TriageBlockUploadCoordinator}, which uploads the whole flushed open/pending set to the {@code triage/} folder.
 * Best-effort throughout; never throws.
 */
@Singleton
public class IssDetectionUploadCoordinator {
    private static final Logger log = LogManager.getLogger(IssDetectionUploadCoordinator.class);

    /** Per-incident folder name: a UTC timestamp, key-safe and lexicographically sortable. */
    private static final DateTimeFormatter INCIDENT_FOLDER_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    /** How often the detection path re-checks disk while waiting for the ISS-round block to become durable. */
    private static final long POLL_INTERVAL_MS = 250L;

    /**
     * Per-path staging subdirectories under a shared incident dir. The detection path and the CATASTROPHIC_FAILURE path
     * can capture the SAME round concurrently; staging each into its own subdir keeps the failure path from truncating
     * (via {@code REPLACE_EXISTING} / a fresh gzip) a file the detection path is mid-stream uploading, and vice versa.
     * The cloud object key is derived from the incident timestamp + block number only, so the subdir never leaks into it.
     */
    private static final String STAGE_DETECTION = "detect";

    private static final String STAGE_FAILURE = "failure";

    private final ConfigProvider configProvider;
    private final BlockUploader uploader;
    private final IssBlockResolver diskResolver;
    private final IssBufferBlockReader bufferReader;
    private final SelfNodeAccountIdManager selfNodeAccountIdManager;
    private final FileSystem fileSystem;
    private final InstantSource instantSource;
    /** Runs the detection-time capture off the ISS-notification dispatcher (a virtual thread per ISS in production). */
    private final Executor captureExecutor;

    /** The latest detected fatal ISS, recorded at detection so the {@code CATASTROPHIC_FAILURE} path can upload it. */
    private final AtomicReference<RecordedIss> lastIss = new AtomicReference<>();
    /** ISS rounds already uploaded to {@code iss/}, so the detection and failure paths never double-upload a round. */
    private final Set<Long> uploadedRounds = ConcurrentHashMap.newKeySet();
    /**
     * The in-flight upload attempt per round, claimed by the first path to reach it so the detection and failure paths
     * never upload the SAME round concurrently; the future completes with whether the exact ISS block was uploaded, so
     * the failure path can await a concurrent detection attempt's outcome instead of silently deferring to it. Keyed
     * per round (not a single slot) so two DISTINCT ISS rounds detected close together are both uploaded rather than
     * one being silently dropped.
     */
    private final Map<Long, CompletableFuture<Boolean>> inFlightUploads = new ConcurrentHashMap<>();

    private record RecordedIss(
            @NonNull IssType issType, long round, @NonNull String incidentFolder) {}

    @Inject
    public IssDetectionUploadCoordinator(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockUploader uploader,
            @NonNull final IssBlockResolver diskResolver,
            @NonNull final IssBufferBlockReader bufferReader,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager,
            @NonNull final FileSystem fileSystem,
            @NonNull final InstantSource instantSource) {
        // Detection-time capture runs on a virtual thread per ISS so the ORDERED async ISS-notification dispatcher
        // (which calls captureAndUpload) is never blocked by the bounded disk poll and upload.
        this(
                configProvider,
                uploader,
                diskResolver,
                bufferReader,
                selfNodeAccountIdManager,
                fileSystem,
                instantSource,
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("iss-block-capture-", 0).factory()));
    }

    // visible for testing: a direct executor lets a test run the capture synchronously
    IssDetectionUploadCoordinator(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockUploader uploader,
            @NonNull final IssBlockResolver diskResolver,
            @NonNull final IssBufferBlockReader bufferReader,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager,
            @NonNull final FileSystem fileSystem,
            @NonNull final InstantSource instantSource,
            @NonNull final Executor captureExecutor) {
        this.configProvider = requireNonNull(configProvider);
        this.uploader = requireNonNull(uploader);
        this.diskResolver = requireNonNull(diskResolver);
        this.bufferReader = requireNonNull(bufferReader);
        this.selfNodeAccountIdManager = requireNonNull(selfNodeAccountIdManager);
        this.fileSystem = requireNonNull(fileSystem);
        this.instantSource = requireNonNull(instantSource);
        this.captureExecutor = requireNonNull(captureExecutor);
    }

    /**
     * Detection-time capture (async ISS dispatcher). Records the ISS for the failure path, then locates and uploads the
     * ISS-round block: from the buffer in {@code GRPC} mode, or by polling disk (bounded by {@code captureTimeout}) in
     * {@code FILE}/{@code FILE_AND_GRPC} mode. Best-effort; never throws.
     *
     * @param issType the ISS type that was detected
     * @param round the ISS round
     */
    public void captureAndUpload(@NonNull final IssType issType, final long round) {
        try {
            final var config = configProvider.getConfiguration().getConfigData(FailureBlockUploadConfig.class);
            if (!config.issBlockUploadEnabled()) {
                return;
            }
            // One folder per ISS event. The captured block(s) are staged under a per-incident timestamp dir and kept
            // there for local triage (mirroring the iss/{timestamp}/ cloud layout). Record it synchronously — before
            // offloading — so the CATASTROPHIC_FAILURE path reuses the same folder and round even while the capture
            // below is still running.
            final String incidentFolder = INCIDENT_FOLDER_FORMAT.format(instantSource.instant());
            lastIss.set(new RecordedIss(issType, round, incidentFolder));
            if (uploadedRounds.contains(round)) {
                return;
            }
            // Offload the blocking work (resolveWithWait polls disk up to captureTimeout; uploadIssBlockBounded blocks
            // up to uploadTimeout) off the ORDERED async ISS-notification dispatcher: blocking it would stall later ISS
            // notifications, and could even let a subsequent ISS's block be pruned before its own capture ran.
            captureExecutor.execute(() -> doCaptureAndUpload(config, issType, round, incidentFolder));
        } catch (final Throwable t) {
            log.error("ISS detection-time block capture/upload failed for round {}", round, t);
        }
    }

    /** The blocking capture+upload, run off the ISS dispatcher by {@link #captureAndUpload}. Best-effort; never throws. */
    private void doCaptureAndUpload(
            @NonNull final FailureBlockUploadConfig config,
            @NonNull final IssType issType,
            final long round,
            @NonNull final String incidentFolder) {
        try {
            if (uploadedRounds.contains(round)) {
                return;
            }
            final var writerMode = configProvider
                    .getConfiguration()
                    .getConfigData(BlockStreamConfig.class)
                    .writerMode();
            final Path incidentDir = incidentDirFor(config, incidentFolder).resolve(STAGE_DETECTION);
            final List<Path> files =
                    switch (writerMode) {
                        // The ISS-round block may still be the open block at detection (not yet a finished file on
                        // disk); wait until it becomes durable (it closes as rounds continue, or is flushed as a
                        // .open.gz at CATASTROPHIC_FAILURE) so the capture is deterministic, not a one-shot miss.
                        case FILE, FILE_AND_GRPC ->
                            materializeFromDisk(
                                    resolveWithWait(issType, round, config.precedingBlocks(), config.captureTimeout()),
                                    incidentDir);
                        // The in-memory buffer already holds the ISS-round block (retained by
                        // minAckedBlocksToBuffer), so no wait is needed.
                        case GRPC -> bufferReader.captureToDir(round, config.precedingBlocks(), incidentDir);
                    };
            uploadAndMark(config, round, incidentFolder, files, false);
        } catch (final Throwable t) {
            log.error("ISS detection-time block capture/upload failed for round {}", round, t);
        }
    }

    /**
     * Synchronous capture on {@code CATASTROPHIC_FAILURE}, invoked from {@code Hedera.newPlatformStatus} after
     * {@code awaitFatalShutdown} has flushed the open/pending blocks to disk. Resolves the recorded ISS round's block
     * <i>once</i> (no polling) from the correct source for the writer mode and uploads it to {@code iss/}, unless the
     * detection path already uploaded it. Bounded by {@code uploadTimeout}; best-effort, never throws — must not stall
     * the halt.
     *
     * <p><b>Must run before {@code blockNodeConnectionManager.shutdown()}</b>: in {@code GRPC} mode a closed, proven
     * ISS block is never written to disk — it lives only in the in-memory buffer, which that shutdown clears. Resolving
     * it from disk (as this used to, unconditionally) would find nothing for exactly the halting ISS this is built to
     * capture.
     */
    public void uploadDetectedIssOnFailure() {
        try {
            final var config = configProvider.getConfiguration().getConfigData(FailureBlockUploadConfig.class);
            if (!config.issBlockUploadEnabled()) {
                return;
            }
            final RecordedIss iss = lastIss.get();
            if (iss == null || uploadedRounds.contains(iss.round())) {
                return;
            }
            final var writerMode = configProvider
                    .getConfiguration()
                    .getConfigData(BlockStreamConfig.class)
                    .writerMode();
            final Path incidentDir =
                    incidentDirFor(config, iss.incidentFolder()).resolve(STAGE_FAILURE);
            final List<Path> files =
                    switch (writerMode) {
                        // FILE / FILE_AND_GRPC: closed blocks are durable .blk.gz on disk and awaitFatalShutdown has
                        // already flushed the open/pending set, so resolve from disk once (no polling).
                        case FILE, FILE_AND_GRPC ->
                            materializeFromDisk(
                                    diskResolver.resolve(iss.issType(), iss.round(), config.precedingBlocks()),
                                    incidentDir);
                        // GRPC: a closed ISS block is never on disk; capture it from the in-memory buffer. This is why
                        // the call must precede blockNodeConnectionManager.shutdown() (which clears the buffer).
                        case GRPC -> bufferReader.captureToDir(iss.round(), config.precedingBlocks(), incidentDir);
                    };
            final boolean preserved = uploadAndMark(config, iss.round(), iss.incidentFolder(), files, true);
            // This is the authoritative, last-chance capture for a halting ISS. If the round is still not preserved
            // (neither this path nor an awaited in-flight detection attempt uploaded it), surface it as ONE distinct
            // high-severity signal an operator can alert on — instead of only the routine-looking WARNs emitted by
            // the individual steps.
            if (!preserved) {
                log.fatal(
                        "ISS block for round {} was NOT preserved to iss/ (writerMode={}); the exact ISS block may be "
                                + "unavailable for triage",
                        iss.round(),
                        writerMode);
            }
        } catch (final Throwable t) {
            log.error("ISS block upload on catastrophic failure failed", t);
        }
    }

    /**
     * Uploads the staged files to {@code iss/} (bounded), records the round as done, and returns whether the round is
     * now uploaded. The round is claimed atomically via {@link #inFlightUploads} so the concurrent detection and
     * failure paths never upload it twice. When the claim is already held by the other path, the detection path just
     * skips (its outcome no longer matters), but the failure path ({@code awaitInFlight}) — the authoritative,
     * last-chance capture, whose caller decides between silence and a "NOT preserved" FATAL — awaits the in-flight
     * attempt (bounded by {@code uploadTimeout}) and retries with its own staged files if that attempt failed. So a
     * lost claim race can neither fire a false FATAL nor silently drop the upload. Best-effort.
     */
    private boolean uploadAndMark(
            @NonNull final FailureBlockUploadConfig config,
            final long round,
            @NonNull final String incidentFolder,
            @NonNull final List<Path> files,
            final boolean awaitInFlight) {
        if (files.isEmpty()) {
            log.warn("No ISS block located for round {}; skipping iss/ upload", round);
            return uploadedRounds.contains(round);
        }
        while (true) {
            if (uploadedRounds.contains(round)) {
                return true;
            }
            // Claim the round; if the other path already claimed it, its future carries the outcome so the round is
            // never uploaded twice.
            final var ourAttempt = new CompletableFuture<Boolean>();
            final CompletableFuture<Boolean> inFlight = inFlightUploads.putIfAbsent(round, ourAttempt);
            if (inFlight == null) {
                boolean uploaded = false;
                try {
                    // Mark the round done ONLY when the exact ISS block is confirmed uploaded. Both the disk resolver
                    // and the buffer reader order blocks oldest→newest, so the ISS block is the last entry and any
                    // earlier entries are best-effort preceding context. If a context block uploads but the ISS block
                    // fails, the round must stay unmarked so the CATASTROPHIC_FAILURE path can still retry the exact
                    // block.
                    uploaded = uploadIssBlockBounded(config, incidentFolder, files);
                    if (uploaded) {
                        uploadedRounds.add(round);
                    }
                    return uploaded;
                } finally {
                    // Release the claim first (so an awaiting failure path that sees a failed outcome can immediately
                    // re-claim and retry), then publish the outcome. A completed round stays guarded by uploadedRounds.
                    inFlightUploads.remove(round, ourAttempt);
                    ourAttempt.complete(uploaded);
                }
            }
            if (!awaitInFlight) {
                // Detection path: the failure path is uploading this round and checks the outcome itself.
                return false;
            }
            try {
                if (Boolean.TRUE.equals(inFlight.get(config.uploadTimeout().toMillis(), TimeUnit.MILLISECONDS))) {
                    return true;
                }
                // The in-flight (detection) attempt failed; loop to claim the round and retry with our staged files.
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return uploadedRounds.contains(round);
            } catch (final Exception e) {
                // Timed out (the in-flight upload is still running) or failed exceptionally; report the current state
                // rather than stacking another upload behind one that may still be holding the connection.
                return uploadedRounds.contains(round);
            }
        }
    }

    private Path incidentDirFor(@NonNull final FailureBlockUploadConfig config, @NonNull final String incidentFolder) {
        return fileSystem
                .getPath(config.issBlockDir())
                .resolve("block-" + asAccountString(selfNodeAccountIdManager.getSelfNodeAccountId()))
                .resolve(incidentFolder);
    }

    /**
     * Resolves the ISS-round block from disk, retrying until it is found or {@code timeout} elapses. The block may be
     * the still-open block at detection (not yet a finished file on disk); it becomes durable as rounds continue, or is
     * flushed as a {@code .open.gz} at {@code CATASTROPHIC_FAILURE}. Polling makes the capture deterministic instead of
     * a one-shot miss.
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
     * {@code issDir}, and returns the copied <b>contents</b> paths. Per-block best-effort: a preceding context block
     * (or a sidecar) that can no longer be copied — e.g. deleted by block retention cleanup between resolve and copy —
     * is skipped rather than aborting the capture; but if the ISS block itself (the last entry) cannot be copied, the
     * whole capture is discarded so the caller never uploads a context block in its place and never marks the round
     * done without the exact ISS block. Sidecar discovery is intentionally left to the {@link BlockUploader}: it
     * uploads each proof sidecar by resolving the sibling in the contents file's directory — which is exactly where
     * this method places it (see {@code BuckyBlockUploader.proofSidecarOf}). Centralizing that in the uploader lets
     * the triage path — which passes flushed files it did not copy here — share the same contract.
     */
    private List<Path> materializeFromDisk(@NonNull final List<IssBlockRef> refs, @NonNull final Path issDir) {
        if (refs.isEmpty()) {
            return List.of();
        }
        try {
            Files.createDirectories(issDir);
        } catch (final IOException e) {
            log.warn("Cannot create ISS staging dir {}; nothing to upload", issDir, e);
            return List.of();
        }
        final List<Path> contents = new ArrayList<>(refs.size());
        for (int i = 0; i < refs.size(); i++) {
            final IssBlockRef ref = refs.get(i);
            final List<Path> files = ref.files();
            final Path contentsSrc = files.get(0); // the contents file is first; any .pnd.json sidecar follows
            final Path contentsDest = issDir.resolve(contentsSrc.getFileName().toString());
            try {
                Files.copy(contentsSrc, contentsDest, StandardCopyOption.REPLACE_EXISTING);
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
                    Files.copy(
                            sidecar,
                            issDir.resolve(sidecar.getFileName().toString()),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (final IOException e) {
                    log.warn("Skipping sidecar {} of block #{}", sidecar, ref.blockNumber(), e);
                }
            }
            contents.add(contentsDest);
        }
        return contents;
    }

    /**
     * Uploads the ISS-round block and any preceding context blocks to {@code iss/} on a bounded worker (one hard
     * {@code uploadTimeout} for the whole operation), and returns whether the <b>exact ISS block</b> was uploaded. The
     * ISS block (the last entry, blocks being ordered oldest→newest) is uploaded FIRST so it gets the timeout budget
     * ahead of the best-effort context, and its result is recorded before the context upload — so even if the context
     * upload is abandoned at the deadline, a successful ISS-block upload is still reported. Best-effort; never throws.
     */
    private boolean uploadIssBlockBounded(
            @NonNull final FailureBlockUploadConfig config,
            @NonNull final String incidentFolder,
            @NonNull final List<Path> files) {
        final Path issBlock = files.get(files.size() - 1);
        final List<Path> precedingContext = files.subList(0, files.size() - 1);
        final AtomicBoolean issBlockUploaded = new AtomicBoolean(false);
        // Deliberately not try-with-resources: ExecutorService.close() blocks awaiting the running task, which would
        // defeat the hard uploadTimeout. We shutdownNow() in the finally to ABANDON a slow upload so the caller (the
        // ordered notification dispatcher, or the shutting-down node) is freed.
        @SuppressWarnings("resource")
        final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "iss-block-detect-upload");
            thread.setDaemon(true);
            return thread;
        });
        try {
            final Future<?> future = executor.submit(() -> {
                // The ISS block first, so it is preserved even if the best-effort context upload later runs long.
                final List<String> issUris =
                        uploader.uploadBlockFiles(UploadCategory.ISS, incidentFolder, List.of(issBlock));
                issBlockUploaded.set(!issUris.isEmpty());
                if (issUris.isEmpty()) {
                    log.warn("ISS block {} was NOT uploaded to iss/; see prior errors", issBlock.getFileName());
                } else {
                    log.warn("ISS block upload complete: {}", issUris);
                }
                if (!precedingContext.isEmpty()) {
                    final List<String> contextUris =
                            uploader.uploadBlockFiles(UploadCategory.ISS, incidentFolder, precedingContext);
                    log.info("Uploaded {} preceding context object(s) to iss/", contextUris.size());
                }
            });
            future.get(config.uploadTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            log.error("ISS block upload exceeded {}; abandoning it so the node can continue", config.uploadTimeout());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while uploading detected ISS block", e);
        } catch (final Exception e) {
            log.error("ISS block upload failed", e);
        } finally {
            executor.shutdownNow();
        }
        return issBlockUploaded.get();
    }
}

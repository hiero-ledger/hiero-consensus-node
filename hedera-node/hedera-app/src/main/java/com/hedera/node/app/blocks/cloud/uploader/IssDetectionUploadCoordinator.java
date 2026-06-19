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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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
 *   {@code Hedera.newPlatformStatus} after {@code awaitFatalShutdown} has flushed the open/pending blocks to disk):
 *   resolves the recorded ISS round's block from the now-durable disk artifacts <i>once</i> (no polling) and uploads
 *   it. Running on the status thread before the node halts — and after the flush guarantees the block is on disk —
 *   makes the <i>halting</i> case race-free, with no indefinite wait (bounded by {@code uploadTimeout}).</li>
 * </ol>
 *
 * <p>The two paths de-duplicate via {@link #uploadedRound}: whichever uploads the round first marks it, and the other
 * skips. The captured block is staged under a per-incident timestamp dir of the node-local {@code issBlockDir} (kept
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

    private static final long NO_ROUND = Long.MIN_VALUE;

    private final ConfigProvider configProvider;
    private final BlockUploader uploader;
    private final IssBlockResolver diskResolver;
    private final IssBufferBlockReader bufferReader;
    private final SelfNodeAccountIdManager selfNodeAccountIdManager;
    private final FileSystem fileSystem;
    private final InstantSource instantSource;

    /** The latest detected fatal ISS, recorded at detection so the {@code CATASTROPHIC_FAILURE} path can upload it. */
    private final AtomicReference<RecordedIss> lastIss = new AtomicReference<>();
    /** The ISS round already uploaded to {@code iss/}, so the detection and failure paths never double-upload it. */
    private final AtomicLong uploadedRound = new AtomicLong(NO_ROUND);

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
        this.configProvider = requireNonNull(configProvider);
        this.uploader = requireNonNull(uploader);
        this.diskResolver = requireNonNull(diskResolver);
        this.bufferReader = requireNonNull(bufferReader);
        this.selfNodeAccountIdManager = requireNonNull(selfNodeAccountIdManager);
        this.fileSystem = requireNonNull(fileSystem);
        this.instantSource = requireNonNull(instantSource);
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
            final var writerMode = configProvider
                    .getConfiguration()
                    .getConfigData(BlockStreamConfig.class)
                    .writerMode();
            // One folder per ISS event. The captured block(s) are staged under a per-incident timestamp dir and kept
            // there for local triage (mirroring the iss/{timestamp}/ cloud layout); record it so the
            // CATASTROPHIC_FAILURE
            // path reuses the same folder and round.
            final String incidentFolder = INCIDENT_FOLDER_FORMAT.format(instantSource.instant());
            lastIss.set(new RecordedIss(issType, round, incidentFolder));
            if (uploadedRound.get() == round) {
                return;
            }
            final Path incidentDir = incidentDirFor(config, incidentFolder);
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
            uploadAndMark(config, round, incidentFolder, files);
        } catch (final Throwable t) {
            log.error("ISS detection-time block capture/upload failed for round {}", round, t);
        }
    }

    /**
     * Synchronous capture on {@code CATASTROPHIC_FAILURE}, invoked from {@code Hedera.newPlatformStatus} after
     * {@code awaitFatalShutdown} has flushed the open/pending blocks to disk. Resolves the recorded ISS round's block
     * from the now-durable disk artifacts <i>once</i> (no polling) and uploads it to {@code iss/}, unless the detection
     * path already uploaded it. Bounded by {@code uploadTimeout}; best-effort, never throws — must not stall the halt.
     */
    public void uploadDetectedIssOnFailure() {
        try {
            final var config = configProvider.getConfiguration().getConfigData(FailureBlockUploadConfig.class);
            if (!config.issBlockUploadEnabled()) {
                return;
            }
            final RecordedIss iss = lastIss.get();
            if (iss == null || uploadedRound.get() == iss.round()) {
                return;
            }
            // The catastrophic-failure flush has made the ISS-round block durable on disk for every writer mode, so
            // resolve it from disk once (no polling) and upload synchronously, before the node halts.
            final Path incidentDir = incidentDirFor(config, iss.incidentFolder());
            final List<Path> files = materializeFromDisk(
                    diskResolver.resolve(iss.issType(), iss.round(), config.precedingBlocks()), incidentDir);
            uploadAndMark(config, iss.round(), iss.incidentFolder(), files);
        } catch (final Throwable t) {
            log.error("ISS block upload on catastrophic failure failed", t);
        }
    }

    /** Uploads the staged files to {@code iss/} (bounded) and marks the round done, unless another path already did. */
    private void uploadAndMark(
            @NonNull final FailureBlockUploadConfig config,
            final long round,
            @NonNull final String incidentFolder,
            @NonNull final List<Path> files) {
        if (files.isEmpty()) {
            log.warn("No ISS block located for round {}; skipping iss/ upload", round);
            return;
        }
        if (uploadedRound.get() == round) {
            return;
        }
        final List<String> uploaded = uploadBounded(config, incidentFolder, files);
        if (!uploaded.isEmpty()) {
            uploadedRound.set(round);
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
        final long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (true) {
            final List<IssBlockRef> refs = diskResolver.resolve(issType, round, precedingBlocks);
            if (!refs.isEmpty() || System.currentTimeMillis() >= deadline) {
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

    /** Copies each resolved block's on-disk files into {@code issDir}; returns the copied contents paths to upload. */
    private List<Path> materializeFromDisk(@NonNull final List<IssBlockRef> refs, @NonNull final Path issDir)
            throws IOException {
        if (refs.isEmpty()) {
            return List.of();
        }
        Files.createDirectories(issDir);
        final List<Path> contents = new ArrayList<>(refs.size());
        for (final IssBlockRef ref : refs) {
            Path copiedContents = null;
            for (final Path file : ref.files()) {
                final Path dest = issDir.resolve(file.getFileName().toString());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                if (copiedContents == null) {
                    copiedContents = dest; // the contents file is first; any .pnd.json sidecar follows
                }
            }
            if (copiedContents != null) {
                contents.add(copiedContents);
            }
        }
        return contents;
    }

    private List<String> uploadBounded(
            @NonNull final FailureBlockUploadConfig config,
            @NonNull final String incidentFolder,
            @NonNull final List<Path> files) {
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
            final Future<List<String>> future =
                    executor.submit(() -> uploader.uploadBlockFiles(UploadCategory.ISS, incidentFolder, files));
            final List<String> uploaded = future.get(config.uploadTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (uploaded.isEmpty()) {
                log.warn("ISS block upload produced no objects from {} file(s); see prior errors", files.size());
            } else {
                log.warn("ISS block upload complete: {} object(s) uploaded to {}", uploaded.size(), uploaded);
            }
            return uploaded;
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
        return List.of();
    }
}

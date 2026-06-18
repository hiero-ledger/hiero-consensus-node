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
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.notification.IssNotification.IssType;

/**
 * Captures the exact ISS-round block at detection time and uploads it to the {@code iss/} bucket folder for triage.
 *
 * <p>Invoked from {@code FatalIssListenerImpl.notify(...)} on the platform's async ISS-notification dispatcher (off the
 * consensus hot path). It locates the block containing the ISS round — from local disk in {@code FILE}/{@code
 * FILE_AND_GRPC} mode, or from the in-memory buffer in {@code GRPC} mode (where closed blocks are never written to
 * disk) — persists it into a per-incident timestamp dir under the node-local {@code issBlockDir} (kept for local
 * triage, mirroring the {@code iss/{timestamp}/} cloud layout), and uploads it from there. Capturing at detection (rather
 * than at the later catastrophic-failure flush) is what makes this work in pure-gRPC mode, where the ISS block can be
 * proven, acknowledged, and pruned from the buffer before any failure flush runs; it also covers a non-halting
 * {@code SELF_ISS} that never reaches {@code CATASTROPHIC_FAILURE} at all.
 *
 * <p>This is distinct from {@code TriageBlockUploadCoordinator}, which uploads the open/pending blocks flushed at
 * {@code CATASTROPHIC_FAILURE} to the {@code triage/} folder. When both flags are on, a catastrophic ISS may produce
 * objects under both folders — intentional: {@code iss/} is the proven ISS-round block, {@code triage/} is everything
 * open/pending at the halt. The upload runs on a bounded worker capped by {@code uploadTimeout}; this method is
 * best-effort and never throws (a stuck upload must not stall the ordered notification dispatcher).
 */
@Singleton
public class IssDetectionUploadCoordinator {
    private static final Logger log = LogManager.getLogger(IssDetectionUploadCoordinator.class);

    /** Per-incident folder name: a UTC timestamp, key-safe and lexicographically sortable. */
    private static final DateTimeFormatter INCIDENT_FOLDER_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    private final ConfigProvider configProvider;
    private final BlockUploader uploader;
    private final IssBlockResolver diskResolver;
    private final IssBufferBlockReader bufferReader;
    private final SelfNodeAccountIdManager selfNodeAccountIdManager;
    private final FileSystem fileSystem;
    private final InstantSource instantSource;

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
     * Locates the block for {@code round}, persists it (and any requested preceding blocks) into the ISS block dir, and
     * uploads it to the {@code iss/} folder. Best-effort; never throws.
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
            // there: they remain available locally for triage (mirroring the iss/{timestamp}/ cloud layout) and are
            // never deleted between incidents.
            final String incidentFolder = INCIDENT_FOLDER_FORMAT.format(instantSource.instant());
            final Path incidentDir = fileSystem
                    .getPath(config.issBlockDir())
                    .resolve("block-" + asAccountString(selfNodeAccountIdManager.getSelfNodeAccountId()))
                    .resolve(incidentFolder);

            final List<Path> files =
                    switch (writerMode) {
                        case FILE, FILE_AND_GRPC ->
                            materializeFromDisk(
                                    diskResolver.resolve(issType, round, config.precedingBlocks()), incidentDir);
                        case GRPC -> bufferReader.captureToDir(round, config.precedingBlocks(), incidentDir);
                    };
            if (files.isEmpty()) {
                log.warn("No ISS block located for round {} (writerMode={}); skipping upload", round, writerMode);
                return;
            }

            uploadBounded(config, incidentFolder, files);
        } catch (final Throwable t) {
            log.error("ISS detection-time block capture/upload failed for round {}", round, t);
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

    private void uploadBounded(
            @NonNull final FailureBlockUploadConfig config,
            @NonNull final String incidentFolder,
            @NonNull final List<Path> files) {
        // Deliberately not try-with-resources: ExecutorService.close() blocks awaiting the running task, which would
        // defeat the hard uploadTimeout on the notification dispatcher. We shutdownNow() in the finally to ABANDON a
        // slow upload so the (ordered) dispatcher is freed.
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
                log.warn(
                        "ISS block detection upload produced no objects from {} file(s); see prior errors",
                        files.size());
            } else {
                log.warn("ISS block detection upload complete: {} object(s) uploaded to {}", uploaded.size(), uploaded);
            }
        } catch (final TimeoutException e) {
            log.error(
                    "ISS block detection upload exceeded {}; abandoning it so the node can continue",
                    config.uploadTimeout());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while uploading detected ISS block", e);
        } catch (final Exception e) {
            log.error("ISS block detection upload failed", e);
        } finally {
            executor.shutdownNow();
        }
    }
}

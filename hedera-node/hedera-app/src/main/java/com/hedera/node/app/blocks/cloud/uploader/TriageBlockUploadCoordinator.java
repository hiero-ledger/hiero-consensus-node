// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.FailureBlockUploadConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

/**
 * Orchestrates the catastrophic-failure triage upload: after a catastrophic failure has flushed the open/pending
 * blocks to disk, uploads those flushed files to the {@code triage/} folder of the configured bucket and logs the
 * result.
 *
 * <p>Invoked from {@code Hedera.newPlatformStatus(CATASTROPHIC_FAILURE)} immediately after
 * {@code BlockStreamManager.awaitFatalShutdown(...)} returns — at which point the flushed files are available via
 * {@link BlockStreamManager#flushedTriageBlockFiles()}. The upload runs on a bounded worker so it cannot delay the
 * node's halt beyond {@code uploadTimeout}; it is best-effort and never throws. The exact ISS-round block is uploaded
 * separately (to the {@code iss/} folder) by {@code IssDetectionUploadCoordinator} when it is detected.
 */
@Singleton
public class TriageBlockUploadCoordinator {
    private static final Logger log = LogManager.getLogger(TriageBlockUploadCoordinator.class);

    /** Per-incident folder name: a UTC timestamp, key-safe and lexicographically sortable. */
    private static final DateTimeFormatter INCIDENT_FOLDER_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    private final ConfigProvider configProvider;
    private final BlockStreamManager blockStreamManager;
    private final BlockUploader uploader;
    private final InstantSource instantSource;

    @Inject
    public TriageBlockUploadCoordinator(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockStreamManager blockStreamManager,
            @NonNull final BlockUploader uploader,
            @NonNull final InstantSource instantSource) {
        this.configProvider = requireNonNull(configProvider);
        this.blockStreamManager = requireNonNull(blockStreamManager);
        this.uploader = requireNonNull(uploader);
        this.instantSource = requireNonNull(instantSource);
    }

    public void uploadFlushedIssBlocks() {
        final var config = configProvider.getConfiguration().getConfigData(FailureBlockUploadConfig.class);
        if (!config.triageUploadEnabled()) {
            return;
        }
        final List<Path> files = blockStreamManager.flushedTriageBlockFiles();
        if (files.isEmpty()) {
            log.warn("Triage block upload is enabled but no triage block files were flushed; nothing to upload");
            return;
        }
        // One folder per catastrophic failure, so a later failure's blocks never intermix with this one's.
        final String incidentFolder = INCIDENT_FOLDER_FORMAT.format(instantSource.instant());
        // Deliberately not try-with-resources: ExecutorService.close() blocks awaiting the running task, which would
        // defeat the hard uploadTimeout on the shutdown path. We shutdownNow() in the finally to ABANDON a slow upload.
        @SuppressWarnings("resource")
        final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "iss-block-upload");
            thread.setDaemon(true);
            return thread;
        });
        try {
            final Future<List<String>> future =
                    executor.submit(() -> uploader.uploadBlockFiles(UploadCategory.TRIAGE, incidentFolder, files));
            final List<String> uploaded = future.get(config.uploadTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (uploaded.isEmpty()) {
                log.warn(
                        "Triage block upload produced no objects from {} flushed file(s); see prior errors",
                        files.size());
            } else {
                log.warn("Triage block upload complete: {} object(s) uploaded to {}", uploaded.size(), uploaded);
            }
        } catch (final TimeoutException e) {
            log.error(
                    "ISS block upload exceeded {}; abandoning it so the node can continue shutting down",
                    config.uploadTimeout());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while uploading ISS blocks", e);
        } catch (final Exception e) {
            log.error("ISS block upload failed", e);
        } finally {
            executor.shutdownNow();
        }
    }
}

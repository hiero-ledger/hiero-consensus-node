// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.FailureBlockUploadConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Stages the catastrophic-failure triage set: after a catastrophic failure has flushed the open/pending blocks to disk,
 * copies those flushed files into the node-local {@code issBlockDir} (a {@code triage/} subdir per incident) so the
 * deployment's stream uploader — a separate process watching that bind-mounted directory — ships them to the bucket. The
 * node itself does no upload and holds no bucket credentials.
 *
 * <p>Invoked from {@code Hedera.newPlatformStatus(CATASTROPHIC_FAILURE)} after {@code awaitFatalShutdown(...)} returns —
 * at which point the flushed files are available via {@link BlockStreamManager#flushedTriageBlockFiles()}. The flushed
 * files live in the block-stream directory, which is not necessarily the bind-mounted upload directory, so they are
 * copied here. Each artifact is written atomically (see {@link StagingFiles}) so the uploader never sees a partial file.
 * Best-effort; never throws. The exact ISS-round block is staged separately by {@code IssDetectionUploadCoordinator}.
 */
@Singleton
public class TriageBlockUploadCoordinator {
    private static final Logger log = LogManager.getLogger(TriageBlockUploadCoordinator.class);

    private static final String STAGE_TRIAGE = "triage";

    private final ConfigProvider configProvider;
    private final BlockStreamManager blockStreamManager;
    private final SelfNodeAccountIdManager selfNodeAccountIdManager;
    private final FileSystem fileSystem;
    private final InstantSource instantSource;

    @Inject
    public TriageBlockUploadCoordinator(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockStreamManager blockStreamManager,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager,
            @NonNull final FileSystem fileSystem,
            @NonNull final InstantSource instantSource) {
        this.configProvider = requireNonNull(configProvider);
        this.blockStreamManager = requireNonNull(blockStreamManager);
        this.selfNodeAccountIdManager = requireNonNull(selfNodeAccountIdManager);
        this.fileSystem = requireNonNull(fileSystem);
        this.instantSource = requireNonNull(instantSource);
    }

    /**
     * Stages the flushed triage set under {@code issBlockDir/block-<account>/<incidentFolder>/triage/}.
     *
     * @param incidentFolder the per-incident folder to group under — pass the ISS block's folder (from
     * {@link IssDetectionUploadCoordinator#currentIncidentFolder()}) so the triage set and the exact ISS block land in
     * ONE incident dir; {@code null} falls back to a fresh timestamp (e.g. triage enabled but ISS capture disabled)
     */
    public void stageFlushedTriageBlocks(@Nullable final String incidentFolder) {
        try {
            final var config = configProvider.getConfiguration().getConfigData(FailureBlockUploadConfig.class);
            if (!config.triageUploadEnabled()) {
                return;
            }
            final List<Path> files = blockStreamManager.flushedTriageBlockFiles();
            if (files.isEmpty()) {
                log.warn("Triage block staging is enabled but no triage block files were flushed; nothing to stage");
                return;
            }
            final String folder =
                    (incidentFolder != null) ? incidentFolder : StagingFiles.incidentFolderNow(instantSource);
            final Path stageDir = StagingFiles.incidentDir(
                            fileSystem,
                            config.issBlockDir(),
                            asAccountString(selfNodeAccountIdManager.getSelfNodeAccountId()),
                            folder)
                    .resolve(STAGE_TRIAGE);
            Files.createDirectories(stageDir);
            int staged = 0;
            for (final Path contents : files) {
                if (stageOne(contents, stageDir)) {
                    staged++;
                }
            }
            if (staged == 0) {
                log.warn(
                        "Triage block staging produced no files from {} flushed file(s); see prior errors",
                        files.size());
            } else {
                log.warn("Triage block staging complete: {} block file(s) staged under {}", staged, stageDir);
            }
        } catch (final Throwable t) {
            log.error("Triage block staging failed", t);
        }
    }

    /** Copies a flushed contents file (and, for a {@code .pnd.gz}, its {@code .pnd.json} sidecar) into {@code stageDir}. */
    private boolean stageOne(@NonNull final Path contents, @NonNull final Path stageDir) {
        final Path dest = stageDir.resolve(contents.getFileName().toString());
        try {
            StagingFiles.atomicCopy(contents, dest);
        } catch (final IOException e) {
            log.warn("Failed to stage flushed triage file {}", contents, e);
            return false;
        }
        final String name = contents.getFileName().toString();
        if (name.endsWith(StagingFiles.PENDING_EXT)) {
            final Path sidecar =
                    contents.resolveSibling(name.substring(0, name.length() - StagingFiles.PENDING_EXT.length())
                            + StagingFiles.PENDING_PROOF_EXT);
            if (Files.exists(sidecar)) {
                try {
                    StagingFiles.atomicCopy(
                            sidecar, stageDir.resolve(sidecar.getFileName().toString()));
                } catch (final IOException e) {
                    log.warn("Failed to stage triage proof sidecar {}", sidecar, e);
                }
            }
        }
        return true;
    }
}

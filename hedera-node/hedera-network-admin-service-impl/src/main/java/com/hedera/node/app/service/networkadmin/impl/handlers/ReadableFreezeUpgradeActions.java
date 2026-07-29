// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.handlers;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.runAsync;
import static org.hiero.base.file.FileUtils.getAbsolutePath;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.file.ReadableUpgradeFileStore;
import com.hedera.node.app.service.networkadmin.ReadableFreezeStore;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.platformstate.ReadablePlatformStateStore;

/**
 * Provides read-only actions that take place during network upgrade
 */
public class ReadableFreezeUpgradeActions {
    private static final Logger log = LogManager.getLogger(ReadableFreezeUpgradeActions.class);

    private final NetworkAdminConfig networkAdminConfig;
    private final ReadableFreezeStore freezeStore;
    private final ReadableUpgradeFileStore upgradeFileStore;
    private final FileID upgradeFileId;

    private final Executor executor;

    public static final String PREPARE_UPGRADE_DESC = "software";
    public static final String TELEMETRY_UPGRADE_DESC = "telemetry";
    public static final String MANUAL_REMEDIATION_ALERT = "Manual remediation may be necessary to avoid node ISS";

    public static final String NOW_FROZEN_MARKER = "now_frozen.mf";
    public static final String EXEC_IMMEDIATE_MARKER = "execute_immediate.mf";
    public static final String EXEC_TELEMETRY_MARKER = "execute_telemetry.mf";
    public static final String FREEZE_SCHEDULED_MARKER = "freeze_scheduled.mf";
    public static final String FREEZE_ABORTED_MARKER = "freeze_aborted.mf";

    public static final long UPGRADE_FILE_ID = 150L;

    public static final String MARK = "✓";

    public ReadableFreezeUpgradeActions(
            @NonNull final Configuration configuration,
            @NonNull final ReadableFreezeStore freezeStore,
            @NonNull final Executor executor,
            @NonNull final ReadableUpgradeFileStore upgradeFileStore,
            @NonNull final EntityIdFactory entityIdFactory) {
        requireNonNull(configuration, "configuration is required for freeze upgrade actions");
        requireNonNull(freezeStore, "Freeze store is required for freeze upgrade actions");
        requireNonNull(executor, "Executor is required for freeze upgrade actions");
        requireNonNull(upgradeFileStore, "Upgrade file store is required for freeze upgrade actions");

        this.networkAdminConfig = configuration.getConfigData(NetworkAdminConfig.class);
        this.freezeStore = freezeStore;
        this.executor = executor;
        this.upgradeFileStore = upgradeFileStore;
        this.upgradeFileId = entityIdFactory.newFileId(UPGRADE_FILE_ID);
    }

    /**
     * Write a NOW_FROZEN_MARKER marker file to signal that the network is frozen.
     */
    public void externalizeFreezeIfUpgradePending() {
        log.info("Externalizing freeze if upgrade pending, updateFileHash: {}", freezeStore.updateFileHash());
        if (freezeStore.updateFileHash() != null) {
            writeCheckMarker(NOW_FROZEN_MARKER);
        }
    }

    /**
     * Write a marker file.
     *
     * @param file the name of the marker file
     * @param now  the timestamp to write to the marker file
     *             if null, the marker file will contain the string "✓"
     *             if not null, the marker file will contain the string representation of the timestamp
     */
    protected void writeMarker(@NonNull final String file, @Nullable final Timestamp now) {
        requireNonNull(file);
        final Path artifactsDirPath = getAbsolutePath(networkAdminConfig.upgradeArtifactsPath());
        final var filePath = artifactsDirPath.resolve(file);
        try {
            if (!artifactsDirPath.toFile().exists()) {
                Files.createDirectories(artifactsDirPath);
            }
            final var contents = (now == null) ? MARK : (String.valueOf(now.seconds()));
            Files.writeString(filePath, contents);
            log.info("Wrote marker {}", filePath);
        } catch (final IOException e) {
            log.error("Failed to write NMT marker {}", filePath, e);
            log.error(MANUAL_REMEDIATION_ALERT);
        }
    }

    /**
     * Write a marker file containing the string '✓'.
     *
     * @param file the name of the marker file
     */
    protected void writeCheckMarker(@NonNull final String file) {
        requireNonNull(file);
        writeMarker(file, null);
    }

    /**
     * Write a marker file containing the string representation of the given timestamp.
     *
     * @param file the name of the marker file
     * @param now  the timestamp to write to the marker file
     */
    protected void writeSecondMarker(@NonNull final String file, @Nullable final Timestamp now) {
        requireNonNull(file);
        writeMarker(file, now);
    }

    public void catchUpOnMissedSideEffects(@NonNull final ReadablePlatformStateStore platformStateStore) {
        catchUpOnMissedFreezeScheduling(platformStateStore);
        catchUpOnMissedUpgradePrep();
    }

    /**
     * Check whether the two given hashes match.
     *
     * @param curSpecialFilesHash the first hash
     * @param hashFromTxnBody     the second hash
     * @return true if the hashes match, false otherwise
     */
    public boolean isPreparedFileHashValidGiven(final byte[] curSpecialFilesHash, final byte[] hashFromTxnBody) {
        return Arrays.equals(curSpecialFilesHash, hashFromTxnBody);
    }

    /**
     * Extract the telemetry upgrade from the given archive data.
     *
     * @param archiveData the archive data
     * @param now         the timestamp to write to the marker file
     * @return a future that completes when the extraction is done
     */
    public CompletableFuture<Void> extractTelemetryUpgrade(
            @NonNull final Bytes archiveData, @Nullable final Timestamp now) {
        requireNonNull(archiveData);
        return extractNow(archiveData, TELEMETRY_UPGRADE_DESC, EXEC_TELEMETRY_MARKER, now);
    }

    /**
     * Extract the software upgrade from the given archive data.
     *
     * @param archiveData the archive data
     * @return a future that completes when the extraction is done
     */
    public CompletableFuture<Void> extractSoftwareUpgrade(@NonNull final Bytes archiveData) {
        requireNonNull(archiveData);
        return extractNow(archiveData, PREPARE_UPGRADE_DESC, EXEC_IMMEDIATE_MARKER, null);
    }

    /**
     * Check whether a freeze is scheduled.
     *
     * @param platformStateStore the platform state
     * @return true if a freeze is scheduled, false otherwise
     */
    public boolean isFreezeScheduled(@NonNull final ReadablePlatformStateStore platformStateStore) {
        requireNonNull(platformStateStore, "Cannot check freeze schedule without access to the platform state");
        final var freezeTime = platformStateStore.getFreezeTime();
        return freezeTime != null && !freezeTime.equals(platformStateStore.getLastFrozenTime());
    }

    /* -------- Internal Methods */
    private CompletableFuture<Void> extractNow(
            @NonNull final Bytes archiveData,
            @NonNull final String desc,
            @NonNull final String marker,
            @Nullable final Timestamp now) {
        requireNonNull(archiveData);
        requireNonNull(desc);
        requireNonNull(marker);

        final Path artifactsLoc = getAbsolutePath(networkAdminConfig.upgradeArtifactsPath());
        requireNonNull(artifactsLoc);
        final long size = archiveData.length();
        log.info("About to unzip {} bytes for {} update into {}", size, desc, artifactsLoc);
        // we spin off a separate thread to avoid blocking handleTransaction
        // if we block handle, there could be a dramatic spike in E2E latency at the time of PREPARE_UPGRADE
        return runAsync(() -> extractAndReplaceArtifacts(artifactsLoc, archiveData, size, desc, marker, now), executor);
    }

    private void extractAndReplaceArtifacts(
            @NonNull final Path artifactsLoc,
            @NonNull final Bytes archiveData,
            final long size,
            @NonNull final String desc,
            @NonNull final String marker,
            @Nullable final Timestamp now) {
        try {
            final var artifactsDir = artifactsLoc.toFile();
            if (!FileUtils.isDirectory(artifactsDir)) {
                FileUtils.forceMkdir(artifactsDir);
            }
            FileUtils.cleanDirectory(artifactsDir);
            UnzipUtility.unzip(archiveData.toByteArray(), artifactsLoc);
            log.info("Finished unzipping {} bytes for {} update into {}", size, desc, artifactsLoc);
            writeSecondMarker(marker, now);
        } catch (final Exception t) {
            // catch and log instead of throwing because upgrade process looks at the presence or absence
            // of marker files to determine whether to proceed with the upgrade
            // if second marker is present, that means the zip file was successfully extracted
            log.error("Failed to unzip archive for NMT consumption", t);
            log.error(MANUAL_REMEDIATION_ALERT);
        }
    }

    private void catchUpOnMissedFreezeScheduling(@NonNull final ReadablePlatformStateStore platformState) {
        final var isUpgradePrepared = freezeStore.updateFileHash() != null;
        if (isFreezeScheduled(platformState) && isUpgradePrepared) {
            final var freezeTime = requireNonNull(platformState.getFreezeTime());
            writeMarker(
                    FREEZE_SCHEDULED_MARKER,
                    Timestamp.newBuilder()
                            .nanos(freezeTime.getNano())
                            .seconds(freezeTime.getEpochSecond())
                            .build());
        }
        /* If we missed a FREEZE_ABORT, we are at risk of having a problem down the road.
        But writing a "defensive" freeze_aborted.mf is itself too risky, as it will keep
        us from correctly (1) catching up on a missed PREPARE_UPGRADE; or (2) handling an
        imminent PREPARE_UPGRADE. */
    }

    private void catchUpOnMissedUpgradePrep() {
        if (freezeStore.updateFileHash() == null) {
            return;
        }

        var shard = upgradeFileId.shardNum();
        var realm = upgradeFileId.realmNum();

        try {
            final var curSpecialFileContents = upgradeFileStore.getFull(upgradeFileId);
            if (!isPreparedFileHashValidGiven(
                    noThrowSha384HashOf(curSpecialFileContents.toByteArray()),
                    freezeStore.updateFileHash().toByteArray())) {
                log.error(
                        "Cannot redo NMT upgrade prep, file {}.{}.{} changed since FREEZE_UPGRADE",
                        shard,
                        realm,
                        upgradeFileId.fileNum());
                log.error(MANUAL_REMEDIATION_ALERT);
                return;
            }
            extractSoftwareUpgrade(curSpecialFileContents).join();
        } catch (final IOException e) {
            log.error(
                    "Cannot redo NMT upgrade prep, file {}.{}.{} changed since FREEZE_UPGRADE",
                    shard,
                    realm,
                    upgradeFileId.fileNum(),
                    e);
            log.error(MANUAL_REMEDIATION_ALERT);
        }
    }
}

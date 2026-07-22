// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.handlers;

import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.EXEC_IMMEDIATE_MARKER;
import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.EXEC_TELEMETRY_MARKER;
import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.NOW_FROZEN_MARKER;
import static com.hedera.node.app.service.networkadmin.impl.handlers.ReadableFreezeUpgradeActions.UPGRADE_FILE_ID;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.file.impl.WritableUpgradeFileStore;
import com.hedera.node.app.service.networkadmin.impl.WritableFreezeStore;
import com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions;
import com.hedera.node.app.service.networkadmin.impl.handlers.ReadableFreezeUpgradeActions;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.hiero.consensus.platformstate.ReadablePlatformStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class ReadableFreezeUpgradeActionsTest {

    private static final Timestamp then =
            Timestamp.newBuilder().seconds(1_234_567L).nanos(890).build();

    private Path noiseFileLoc;
    private Path noiseSubFileLoc;
    private Path zipArchivePath; // path to valid.zip test zip file (in zipSourceDir directory)

    @TempDir
    private Path zipSourceDir; // temp directory to place test zip files

    @TempDir
    private File zipOutputDir; // temp directory to place marker files and output of zip extraction

    @Mock
    private WritableFreezeStore writableFreezeStore;

    @Mock
    private NetworkAdminConfig adminServiceConfig;

    @LoggingTarget
    private LogCaptor logCaptor;

    @LoggingSubject
    private ReadableFreezeUpgradeActions subject;

    @Mock
    private WritableUpgradeFileStore upgradeFileStore;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    private Configuration configuration;

    private Executor freezeExecutor;

    @BeforeEach
    void setUp() throws IOException {
        given(configuration.getConfigData(NetworkAdminConfig.class)).willReturn(adminServiceConfig);

        noiseFileLoc = zipOutputDir.toPath().resolve("forgotten.cfg");
        noiseSubFileLoc = zipOutputDir.toPath().resolve("edargpu");

        freezeExecutor = new ForkJoinPool(
                1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, Thread.getDefaultUncaughtExceptionHandler(), true);

        given(entityIdFactory.newFileId(UPGRADE_FILE_ID)).willReturn(FileID.DEFAULT);
        subject = new FreezeUpgradeActions(
                configuration, writableFreezeStore, freezeExecutor, upgradeFileStore, entityIdFactory);

        // set up test zip
        zipSourceDir = Files.createTempDirectory("zipSourceDir");
        zipArchivePath = Path.of(zipSourceDir + "/valid.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipArchivePath.toFile()))) {
            ZipEntry e = new ZipEntry("garden_path_sentence.txt");
            out.putNextEntry(e);

            String fileContent = "The old man the boats";
            byte[] data = fileContent.getBytes();
            out.write(data, 0, data.length);
            out.closeEntry();
        }
    }

    @Test
    void complainsLoudlyWhenUnableToUnzipArchive() {
        rmIfPresent(EXEC_IMMEDIATE_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());

        final Bytes invalidArchive = Bytes.wrap("Not a valid zip archive".getBytes(StandardCharsets.UTF_8));
        subject.extractSoftwareUpgrade(invalidArchive).join();

        final var errors = logCaptor.errorLogs();

        assertThat(errors)
                .hasSize(2)
                .anyMatch(l -> l.startsWith("Failed to unzip archive for NMT consumption"))
                .anyMatch(l -> l.startsWith("Manual remediation may be necessary to avoid node ISS"));

        assertThat(new File(zipOutputDir, EXEC_IMMEDIATE_MARKER)).doesNotExist();
    }

    @Test
    void preparesForUpgrade() throws IOException {
        setupNoiseFiles();
        rmIfPresent(EXEC_IMMEDIATE_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());

        final Bytes realArchive = Bytes.wrap(Files.readAllBytes(zipArchivePath));
        subject.extractSoftwareUpgrade(realArchive).join();

        assertMarkerCreated(EXEC_IMMEDIATE_MARKER, null);
    }

    @Test
    void upgradesTelemetry() throws IOException {
        rmIfPresent(EXEC_TELEMETRY_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());

        final Bytes realArchive = Bytes.wrap(Files.readAllBytes(zipArchivePath));
        subject.extractTelemetryUpgrade(realArchive, then).join();

        assertMarkerCreated(EXEC_TELEMETRY_MARKER, then);
    }

    @Test
    void externalizesFreeze() throws IOException {
        rmIfPresent(NOW_FROZEN_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());
        given(writableFreezeStore.updateFileHash()).willReturn(Bytes.wrap("fake hash"));

        subject.externalizeFreezeIfUpgradePending();

        assertMarkerCreated(NOW_FROZEN_MARKER, null);
    }

    @Test
    void testCatchUpOnMissedSideEffects() throws IOException {
        final var store = mock(ReadablePlatformStateStore.class);
        LocalDateTime localDateTime = LocalDateTime.of(2024, 1, 1, 0, 0);
        Instant checkpoint = localDateTime.atZone(ZoneId.of("UTC")).toInstant();
        given(store.getFreezeTime()).willReturn(checkpoint);
        given(store.getLastFrozenTime()).willReturn(checkpoint.minusMillis(10000));
        given(writableFreezeStore.updateFileHash()).willReturn(Bytes.wrap("fake hash"));

        assertThatNoException().isThrownBy(() -> subject.isFreezeScheduled(store));
        assertThat(subject.isFreezeScheduled(store)).isTrue();

        rmIfPresent(NOW_FROZEN_MARKER);
        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());

        Bytes expectedContent = Bytes.wrap("expected");
        given(upgradeFileStore.getFull(any())).willReturn(expectedContent);

        assertThatNoException().isThrownBy(() -> subject.catchUpOnMissedSideEffects(store));
    }

    @Test
    void invokeMissedUpgradePrepException() {
        final var store = mock(ReadablePlatformStateStore.class);
        given(writableFreezeStore.updateFileHash()).willReturn(Bytes.wrap("fake hash"));
        subject.isPreparedFileHashValidGiven(null, null);
        assertThatNullPointerException().isThrownBy(() -> subject.catchUpOnMissedSideEffects(store));
        given(writableFreezeStore.updateFileHash()).willReturn(null);
        assertThatNoException().isThrownBy(() -> subject.catchUpOnMissedSideEffects(store));
    }

    @Test
    void validatePreparedFileHashGiven() {
        given(writableFreezeStore.updateFileHash()).willReturn(Bytes.wrap("fake hash"));
        byte[] curSpecialFileHash = new BigInteger("e04fd020ea3a6910a2d808002b30309d", 16).toByteArray();
        byte[] hashFromTxnBody =
                requireNonNull(writableFreezeStore.updateFileHash()).toByteArray();
        assertThat(subject.isPreparedFileHashValidGiven(curSpecialFileHash, hashFromTxnBody))
                .isFalse();
    }

    @Test
    void testIfFreezeIsScheduled() {
        final var store = mock(ReadablePlatformStateStore.class);
        LocalDateTime localDateTime = LocalDateTime.of(2024, 1, 1, 0, 0);
        Instant checkpoint = localDateTime.atZone(ZoneId.of("UTC")).toInstant();

        given(store.getFreezeTime()).willReturn(checkpoint);
        given(store.getLastFrozenTime()).willReturn(checkpoint.minusMillis(10000));
        assertThatNoException().isThrownBy(() -> subject.isFreezeScheduled(store));
        assertThat(subject.isFreezeScheduled(store)).isTrue();

        given(store.getFreezeTime()).willReturn(null);
        given(store.getLastFrozenTime()).willReturn(checkpoint.plusSeconds(2));
        assertThat(subject.isFreezeScheduled(store)).isFalse();

        given(store.getFreezeTime()).willReturn(checkpoint);
        given(store.getLastFrozenTime()).willReturn(checkpoint);
        assertThat(subject.isFreezeScheduled(store)).isFalse();
    }

    private void rmIfPresent(final String file) {
        rmIfPresent(zipOutputDir.toPath(), file);
    }

    private static void rmIfPresent(final Path baseDir, final String file) {
        final File f = baseDir.resolve(file).toFile();
        if (f.exists()) {
            boolean deleted = f.delete();
            assert (deleted);
        }
    }

    private void assertMarkerCreated(final String file, final @Nullable Timestamp when) throws IOException {
        assertMarkerCreated(file, when, zipOutputDir.toPath());
    }

    private void assertMarkerCreated(final String file, final @Nullable Timestamp when, final Path baseDir)
            throws IOException {
        final Path filePath = baseDir.resolve(file);
        assertThat(filePath.toFile()).exists();
        final var contents = Files.readString(filePath);
        assertThat(filePath.toFile().delete()).isTrue();

        if (file.equals(EXEC_IMMEDIATE_MARKER)) {
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("About to unzip ")
                            && l.contains(" bytes for software update into " + baseDir)));
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("Finished unzipping ")
                            && l.contains(" bytes for software update into " + baseDir)));
            assertThat(logCaptor.infoLogs()).anyMatch(l -> (l.contains("Wrote marker " + filePath)));
        } else if (file.equals(EXEC_TELEMETRY_MARKER)) {
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("About to unzip ")
                            && l.contains(" bytes for telemetry update into " + baseDir)));
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l ->
                            (l.startsWith("Finished unzipping ") && l.contains(" bytes for telemetry update into ")));
            assertThat(logCaptor.infoLogs()).anyMatch(l -> (l.contains("Wrote marker " + filePath)));
        } else {
            assertThat(logCaptor.infoLogs()).anyMatch(l -> (l.contains("Wrote marker " + filePath)));
        }
        if (when != null) {
            final var writtenEpochSecond = Long.parseLong(contents);
            assertThat(when.seconds()).isEqualTo(writtenEpochSecond);
        } else {
            assertThat(contents).isEqualTo(FreezeUpgradeActions.MARK);
        }
    }

    private void setupNoiseFiles() throws IOException {
        Files.write(
                noiseFileLoc,
                List.of("There, the eyes are", "Sunlight on a broken column", "There, is a tree swinging"));
        Files.write(
                noiseSubFileLoc,
                List.of(
                        "And voices are",
                        "In the wind's singing",
                        "More distant and more solemn",
                        "Than a fading star"));
    }
}

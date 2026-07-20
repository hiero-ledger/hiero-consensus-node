// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.handlers;

import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.EXEC_IMMEDIATE_MARKER;
import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.EXEC_TELEMETRY_MARKER;
import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.FREEZE_ABORTED_MARKER;
import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.FREEZE_SCHEDULED_MARKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.file.impl.WritableUpgradeFileStore;
import com.hedera.node.app.service.networkadmin.impl.WritableFreezeStore;
import com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions;
import com.hedera.node.app.service.networkadmin.impl.handlers.ReadableFreezeUpgradeActions;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.data.NodesConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class FreezeUpgradeActionsTest {
    private static final Timestamp then =
            Timestamp.newBuilder().seconds(1_234_567L).nanos(890).build();
    private Path noiseSubFileLoc;
    private Path zipArchivePath; // path to valid.zip test zip file (in zipSourceDir directory)

    @TempDir
    private Path zipSourceDir; // temp directory to place test zip files

    @TempDir
    private File zipOutputDir; // temp directory to place marker files and output of zip extraction

    @Mock
    private WritableFreezeStore freezeStore;

    @LoggingTarget
    private LogCaptor logCaptor;

    private FreezeUpgradeActions subject;

    // Since all logs are moved to base class
    @LoggingSubject
    private ReadableFreezeUpgradeActions loggingSubject;

    @Mock
    private WritableUpgradeFileStore upgradeFileStore;

    @Mock
    private ReadableNodeStore nodeStore;

    @Mock
    private ReadableStakingInfoStore stakingInfoStore;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    private Configuration configuration;

    @Mock
    private NetworkAdminConfig adminServiceConfig;

    @Mock
    private NodesConfig nodesConfig;

    @BeforeEach
    void setUp() throws IOException {
        given(configuration.getConfigData(NetworkAdminConfig.class)).willReturn(adminServiceConfig);
        given(configuration.getConfigData(NodesConfig.class)).willReturn(nodesConfig);

        noiseSubFileLoc = zipOutputDir.toPath().resolve("edargpu");

        final Executor freezeExectuor = new ForkJoinPool(
                1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, Thread.getDefaultUncaughtExceptionHandler(), true);
        subject = new FreezeUpgradeActions(
                configuration,
                freezeStore,
                freezeExectuor,
                upgradeFileStore,
                nodeStore,
                stakingInfoStore,
                entityIdFactory);

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
    void setsExpectedFreezeAndWritesMarkerForFreezeUpgrade() throws IOException {
        rmIfPresent(FREEZE_SCHEDULED_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());

        subject.scheduleFreezeUpgradeAt(then);

        verify(freezeStore).freezeTime(then);

        assertMarkerCreated(FREEZE_SCHEDULED_MARKER, then);
    }

    @Test
    void setsExpectedFreezeOnlyForFreezeOnly() {
        rmIfPresent(FREEZE_SCHEDULED_MARKER);

        subject.scheduleFreezeOnlyAt(then);

        verify(freezeStore).freezeTime(then);
    }

    @Test
    void nullsOutDualOnAborting() throws IOException {
        rmIfPresent(FREEZE_ABORTED_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());

        subject.abortScheduledFreeze();

        verify(freezeStore).freezeTime(Timestamp.DEFAULT);

        assertMarkerCreated(FREEZE_ABORTED_MARKER, Timestamp.DEFAULT);
    }

    @Test
    void canStillWriteMarkersEvenIfDirDoesntExist() throws IOException {
        final Path otherMarkerFilesLoc = noiseSubFileLoc;
        rmIfPresent(otherMarkerFilesLoc, FREEZE_ABORTED_MARKER);
        final var d = otherMarkerFilesLoc.toFile();
        if (d.exists()) {
            assertThat(d.delete()).isTrue();
        }

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(otherMarkerFilesLoc.toString());

        subject.abortScheduledFreeze();

        verify(freezeStore).freezeTime(Timestamp.DEFAULT);

        assertMarkerCreated(FREEZE_ABORTED_MARKER, Timestamp.DEFAULT, otherMarkerFilesLoc);
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
        if (when != null && !when.equals(Timestamp.DEFAULT)) {
            final var writtenEpochSecond = Long.parseLong(contents);
            assertThat(when.seconds()).isEqualTo(writtenEpochSecond);
        } else {
            assertThat(contents).isEqualTo(FreezeUpgradeActions.MARK);
        }
    }
}

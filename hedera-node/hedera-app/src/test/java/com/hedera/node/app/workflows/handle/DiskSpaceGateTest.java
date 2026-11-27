// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.DiskSpaceConfig;
import com.hedera.node.config.data.FailureBlockStagingConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.Platform;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.appender.MemoryMappedFileAppender;
import org.apache.logging.log4j.core.appender.RandomAccessFileAppender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.RollingRandomAccessFileAppender;
import org.hiero.consensus.config.RecycleBinConfig;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.pces.config.PcesConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskSpaceGateTest {
    private static final int RESERVE_MB = 1;
    private static final long RESERVE = RESERVE_MB * 1024L * 1024L;
    private static final Path SAVED = Path.of("/data/saved");
    private static final Path BLOCKS = Path.of("/data/blocks");
    private static final Path WORKING_DIR = Path.of("");

    private final List<Duration> sleeps = new ArrayList<>();
    private final FileStore savedStore = mock(FileStore.class);
    private final FileStore blockStore = mock(FileStore.class);

    @Test
    void passesWithoutWaitingWhenEveryVolumeHasTheReserve() throws IOException {
        given(savedStore.getUsableSpace()).willReturn(RESERVE);
        given(blockStore.getUsableSpace()).willReturn(RESERVE + 1);

        gateWith(RESERVE_MB, Map.of(SAVED, savedStore, BLOCKS, blockStore)).awaitFreeSpace();

        assertThat(sleeps).isEmpty();
    }

    @Test
    void waitsUntilTheLowVolumeHasTheReserveAgain() throws IOException {
        given(savedStore.getUsableSpace()).willReturn(RESERVE);
        given(blockStore.getUsableSpace()).willReturn(RESERVE - 1, RESERVE - 1, RESERVE);

        gateWith(RESERVE_MB, Map.of(SAVED, savedStore, BLOCKS, blockStore)).awaitFreeSpace();

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
        verify(blockStore, times(3)).getUsableSpace();
    }

    @Test
    void waitsUntilEveryLowVolumeHasTheReserveAgain() throws IOException {
        // The block volume is checked first and recovers first
        given(blockStore.getUsableSpace()).willReturn(RESERVE - 1, RESERVE);
        given(savedStore.getUsableSpace()).willReturn(RESERVE - 1, RESERVE - 1, RESERVE - 1, RESERVE);

        gateWith(RESERVE_MB, Map.of(SAVED, savedStore, BLOCKS, blockStore)).awaitFreeSpace();

        assertThat(sleeps).hasSize(3);
        verify(savedStore, times(4)).getUsableSpace();
    }

    @Test
    void zeroReserveChecksNothing() throws IOException {
        gateWith(0, Map.of(SAVED, savedStore)).awaitFreeSpace();

        assertThat(sleeps).isEmpty();
        verify(savedStore, never()).getUsableSpace();
        verify(savedStore, never()).getTotalSpace();
    }

    @Test
    void stopsWaitingAndKeepsTheFlagWhenInterrupted() throws IOException, InterruptedException {
        given(savedStore.getUsableSpace()).willReturn(0L);
        final var subject = new DiskSpaceGate(
                configWith(RESERVE_MB), new NoOpMetrics(), List.of(SAVED), dir -> savedStore, duration -> {
                    throw new InterruptedException();
                });
        final var interrupted = new AtomicBoolean();

        // Own thread, so the test worker thread is never interrupted
        final var thread = Thread.ofVirtual().start(() -> {
            subject.awaitFreeSpace();
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        assertThat(thread.join(Duration.ofSeconds(5))).isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    void volumeWithUnreadableFreeSpaceDoesNotBlock() throws IOException {
        given(savedStore.getUsableSpace()).willThrow(new IOException("gone"));

        gateWith(RESERVE_MB, Map.of(SAVED, savedStore)).awaitFreeSpace();

        assertThat(sleeps).isEmpty();
    }

    @Test
    void dirWithoutAFileStoreIsSkipped() throws IOException {
        given(savedStore.getUsableSpace()).willReturn(RESERVE);
        final var subject = new DiskSpaceGate(
                configWith(RESERVE_MB),
                new NoOpMetrics(),
                List.of(SAVED, BLOCKS),
                dir -> {
                    if (dir.equals(BLOCKS)) {
                        throw new IOException("no store");
                    }
                    return savedStore;
                },
                sleeps::add);

        subject.awaitFreeSpace();

        assertThat(sleeps).isEmpty();
    }

    @Test
    void dirsOnTheSameFileStoreAreCheckedOnce() throws IOException {
        given(savedStore.getUsableSpace()).willReturn(RESERVE);
        final var logCaptor = new LogCaptor(LogManager.getLogger(DiskSpaceGate.class));
        try {
            gateWith(RESERVE_MB, Map.of(SAVED, savedStore, BLOCKS, savedStore)).awaitFreeSpace();

            verify(savedStore, times(1)).getUsableSpace();
            assertThat(logCaptor.infoLogs())
                    .singleElement()
                    .asString()
                    .contains(BLOCKS.toString())
                    .contains(SAVED.toString());
        } finally {
            logCaptor.stopCapture();
        }
    }

    @Test
    void missingDirUsesTheFileStoreOfItsNearestExistingParent(@TempDir final Path tempDir) throws IOException {
        assertThat(DiskSpaceGate.fileStoreOf(tempDir.resolve("not/created/yet")))
                .isEqualTo(Files.getFileStore(tempDir));
    }

    @Test
    void gaugesShowTheLowestFreeSpaceTheReserveAndThePause() throws IOException {
        final Map<String, LongGauge> gauges = new HashMap<>();
        final var metrics = mock(Metrics.class);
        given(metrics.getOrCreate(any(LongGauge.Config.class)))
                .willAnswer(invocation -> gauges.computeIfAbsent(
                        invocation.<LongGauge.Config>getArgument(0).getName(), name -> mock(LongGauge.class)));
        given(savedStore.getUsableSpace()).willReturn(RESERVE + 7);
        given(blockStore.getUsableSpace()).willReturn(RESERVE - 1, RESERVE + 5);
        final Map<Path, FileStore> stores = Map.of(SAVED, savedStore, BLOCKS, blockStore);

        new DiskSpaceGate(configWith(RESERVE_MB), metrics, List.of(SAVED, BLOCKS), stores::get, sleeps::add)
                .awaitFreeSpace();

        verify(gauges.get("diskSpaceReserved")).set(RESERVE);
        final var free = gauges.get("diskSpaceFree");
        final var freeInOrder = inOrder(free);
        freeInOrder.verify(free).set(RESERVE - 1);
        freeInOrder.verify(free).set(RESERVE + 5);
        final var paused = gauges.get("diskSpacePaused");
        final var pausedInOrder = inOrder(paused);
        pausedInOrder.verify(paused).set(1);
        pausedInOrder.verify(paused).set(0);
    }

    @Test
    void warnsAtStartupAboutAVolumeNotBiggerThanTheReserve() throws IOException {
        given(savedStore.getTotalSpace()).willReturn(RESERVE);
        given(blockStore.getTotalSpace()).willReturn(RESERVE + 1);
        final var logCaptor = new LogCaptor(LogManager.getLogger(DiskSpaceGate.class));
        try {
            gateWith(RESERVE_MB, Map.of(SAVED, savedStore, BLOCKS, blockStore));

            assertThat(logCaptor.warnLogs())
                    .singleElement()
                    .asString()
                    .contains(SAVED.toString())
                    .contains("diskSpace.reservedMb=1");
        } finally {
            logCaptor.stopCapture();
        }
    }

    @Test
    void reserveComesFromTheBootstrapConfigNotFromFile121() {
        final Configuration appConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(FailureBlockStagingConfig.class)
                .withValue("diskSpace.reservedMb", Integer.MAX_VALUE)
                .getOrCreateConfig();
        final ConfigProvider configProvider = () -> new VersionedConfigImpl(appConfig, 1L);
        final var bootstrapConfigProvider = new BootstrapConfigProviderImpl(Map.of("diskSpace.reservedMb", "0"));
        final var platform = mock(Platform.class, RETURNS_DEEP_STUBS);
        given(platform.getContext().getConfiguration())
                .willReturn(HederaTestConfigBuilder.create().getOrCreateConfig());

        final var subject = new DiskSpaceGate(configProvider, bootstrapConfigProvider, platform, new NoOpMetrics());

        assertTimeoutPreemptively(Duration.ofSeconds(5), subject::awaitFreeSpace);
    }

    @Test
    void checksEveryDirTheNodeWritesTo() {
        final var appConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(FailureBlockStagingConfig.class)
                .withValue("blockStream.streamMode", "BOTH")
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue("blockStream.blockFileDir", "/blocks")
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", "true")
                .withValue("blockStream.buffer.bufferDirectory", "/buffer")
                .withValue("hedera.recordStream.logDir", "/records")
                .withValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", "true")
                .withValue("hedera.recordStream.wrappedRecordHashesDir", "/wrapped")
                .withValue("networkAdmin.upgradeArtifactsPath", "/upgrade")
                .withValue("networkAdmin.diskNetworkExport", "EVERY_BLOCK")
                .withValue("networkAdmin.diskNetworkExportFile", "/export/network.json")
                .withValue("tss.tssKeysPath", "/tss")
                .withValue("failureBlockStaging.issBlockStagingEnabled", "true")
                .withValue("failureBlockStaging.issBlockDir", "/iss")
                .withValue("hedera.transaction.pendingTransactionsPersistenceEnabled", "true")
                .withValue("hedera.transaction.pendingTransactionsDirectory", "/pending")
                .getOrCreateConfig();
        final var platformConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(PcesConfig.class)
                .withConfigDataType(RecycleBinConfig.class)
                .withValue("paths.savedStateDir", "/saved")
                .withValue("paths.tmpDir", "/tmp-dir")
                .withValue("event.preconsensus.databaseDirectory", "pces")
                .withValue("recycleBin.dirName", "bin")
                .withValue("event.enableEventStreaming", "true")
                .withValue("event.eventsLogDir", "/events")
                .withValue("metrics.csvOutputFolder", "/stats")
                .getOrCreateConfig();

        assertThat(DiskSpaceGate.dataDirs(appConfig, platformConfig, List.of(Path.of("/logs"))))
                .containsExactly(
                        Path.of("/saved"),
                        Path.of("/tmp-dir"),
                        Path.of("/saved/pces"),
                        Path.of("/saved/bin"),
                        Path.of("/events"),
                        Path.of("/stats"),
                        Path.of("/blocks"),
                        Path.of("/records"),
                        Path.of("/wrapped"),
                        Path.of("/buffer"),
                        Path.of("/upgrade"),
                        Path.of("/export"),
                        Path.of("/tss"),
                        Path.of("/iss"),
                        Path.of("/pending"),
                        Path.of("/logs"),
                        WORKING_DIR);
    }

    @Test
    void skipsDirsOfWritersThatAreOff() {
        final var appConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(FailureBlockStagingConfig.class)
                .withValue("blockStream.streamMode", "RECORDS")
                .withValue("blockStream.writerMode", "FILE")
                .withValue("blockStream.blockFileDir", "/blocks")
                .withValue("blockStream.buffer.bufferDirectory", "/buffer")
                .withValue("hedera.recordStream.logDir", "/records")
                .withValue("networkAdmin.upgradeArtifactsPath", "/upgrade")
                .withValue("tss.hintsEnabled", "false")
                .withValue("tss.historyEnabled", "false")
                .getOrCreateConfig();
        final var platformConfig = HederaTestConfigBuilder.create()
                .withValue("paths.savedStateDir", "/saved")
                .withValue("paths.tmpDir", "tmp")
                .withValue("event.enableEventStreaming", "false")
                .withValue("metrics.disableMetricsOutput", "true")
                .getOrCreateConfig();

        assertThat(DiskSpaceGate.dataDirs(appConfig, platformConfig, List.of()))
                .containsExactly(
                        Path.of("/saved"),
                        Path.of("/saved/tmp"),
                        Path.of("/records"),
                        Path.of("/upgrade"),
                        WORKING_DIR);
    }

    @Test
    void blocksModeSkipsTheRecordDir() {
        final var appConfig = HederaTestConfigBuilder.create()
                .withConfigDataType(FailureBlockStagingConfig.class)
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("blockStream.writerMode", "FILE")
                .withValue("blockStream.blockFileDir", "/blocks")
                .withValue("hedera.recordStream.logDir", "/records")
                .getOrCreateConfig();
        final var platformConfig = HederaTestConfigBuilder.create().getOrCreateConfig();

        assertThat(DiskSpaceGate.dataDirs(appConfig, platformConfig, List.of()))
                .contains(Path.of("/blocks"))
                .doesNotContain(Path.of("/records"));
    }

    @Test
    void logDirsAreTheDirsOfTheLogFiles() {
        final var file = mock(FileAppender.class);
        given(file.getFileName()).willReturn("/logs/a/node.log");
        final var rolling = mock(RollingFileAppender.class);
        given(rolling.getFileName()).willReturn("/logs/b/node.log");
        final var randomAccess = mock(RandomAccessFileAppender.class);
        given(randomAccess.getFileName()).willReturn("/logs/c/node.log");
        final var rollingRandomAccess = mock(RollingRandomAccessFileAppender.class);
        given(rollingRandomAccess.getFileName()).willReturn("/logs/d/node.log");
        final var memoryMapped = mock(MemoryMappedFileAppender.class);
        given(memoryMapped.getFileName()).willReturn("/logs/e/node.log");
        final var console = mock(Appender.class);

        assertThat(DiskSpaceGate.logDirsOf(
                        List.of(file, rolling, randomAccess, rollingRandomAccess, memoryMapped, console)))
                .containsExactly(
                        Path.of("/logs/a"),
                        Path.of("/logs/b"),
                        Path.of("/logs/c"),
                        Path.of("/logs/d"),
                        Path.of("/logs/e"));
    }

    @Test
    void logDirsComeFromTheLog4jConfiguration() {
        assertThat(DiskSpaceGate.logDirs()).allSatisfy(dir -> assertThat(dir).isAbsolute());
    }

    private DiskSpaceGate gateWith(final int reservedMb, final Map<Path, FileStore> stores) {
        return new DiskSpaceGate(
                configWith(reservedMb),
                new NoOpMetrics(),
                List.copyOf(new TreeMap<>(stores).keySet()),
                stores::get,
                sleeps::add);
    }

    private static DiskSpaceConfig configWith(final int reservedMb) {
        return new DiskSpaceConfig(reservedMb, Duration.ofSeconds(1));
    }
}

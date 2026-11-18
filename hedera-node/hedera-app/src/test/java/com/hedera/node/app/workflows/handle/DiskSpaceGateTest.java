// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskSpaceGateTest {
    private static final long RESERVE = 1_000L;
    private static final Path SAVED = Path.of("/data/saved");
    private static final Path BLOCKS = Path.of("/data/blocks");

    private final List<Duration> sleeps = new ArrayList<>();
    private final FileStore savedStore = mock(FileStore.class);
    private final FileStore blockStore = mock(FileStore.class);

    @Test
    void passesWithoutWaitingWhenEveryVolumeHasTheReserve() throws IOException {
        given(savedStore.getUsableSpace()).willReturn(RESERVE);
        given(blockStore.getUsableSpace()).willReturn(RESERVE + 1);

        gateWith(RESERVE, Map.of(SAVED, savedStore, BLOCKS, blockStore)).awaitFreeSpace();

        assertThat(sleeps).isEmpty();
    }

    @Test
    void waitsUntilTheLowVolumeHasTheReserveAgain() throws IOException {
        given(savedStore.getUsableSpace()).willReturn(RESERVE);
        given(blockStore.getUsableSpace()).willReturn(RESERVE - 1, RESERVE - 1, RESERVE);

        gateWith(RESERVE, Map.of(SAVED, savedStore, BLOCKS, blockStore)).awaitFreeSpace();

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
        verify(blockStore, times(3)).getUsableSpace();
    }

    @Test
    void zeroReserveChecksNothing() throws IOException {
        gateWith(0, Map.of(SAVED, savedStore)).awaitFreeSpace();

        assertThat(sleeps).isEmpty();
        verify(savedStore, never()).getUsableSpace();
    }

    @Test
    void stopsWaitingAndKeepsTheFlagWhenInterrupted() throws IOException {
        given(savedStore.getUsableSpace()).willReturn(0L);
        final var subject =
                new DiskSpaceGate(configProviderWith(RESERVE), List.of(SAVED), dir -> savedStore, duration -> {
                    throw new InterruptedException();
                });

        try {
            subject.awaitFreeSpace();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void volumeWithUnreadableFreeSpaceDoesNotBlock() throws IOException {
        given(savedStore.getUsableSpace()).willThrow(new IOException("gone"));

        gateWith(RESERVE, Map.of(SAVED, savedStore)).awaitFreeSpace();

        assertThat(sleeps).isEmpty();
    }

    @Test
    void dirWithoutAFileStoreIsSkipped() throws IOException {
        given(savedStore.getUsableSpace()).willReturn(RESERVE);
        final var subject = new DiskSpaceGate(
                configProviderWith(RESERVE),
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

        gateWith(RESERVE, Map.of(SAVED, savedStore, BLOCKS, savedStore)).awaitFreeSpace();

        verify(savedStore, times(1)).getUsableSpace();
    }

    @Test
    void missingDirUsesTheFileStoreOfItsNearestExistingParent(@TempDir final Path tempDir) throws IOException {
        assertThat(DiskSpaceGate.fileStoreOf(tempDir.resolve("not/created/yet")))
                .isEqualTo(Files.getFileStore(tempDir));
    }

    @Test
    void blocksModeChecksSavedStatesBlocksAndBuffer() {
        final var appConfig = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue("blockStream.blockFileDir", "/blocks")
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", "true")
                .withValue("blockStream.buffer.bufferDirectory", "/buffer")
                .withValue("hedera.recordStream.logDir", "/records")
                .getOrCreateConfig();

        assertThat(DiskSpaceGate.dataDirs(appConfig, platformConfig()))
                .containsExactly(Path.of("/saved"), Path.of("/blocks"), Path.of("/buffer"));
    }

    @Test
    void recordsModeWithFileWriterChecksSavedStatesAndRecords() {
        final var appConfig = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "RECORDS")
                .withValue("blockStream.writerMode", "FILE")
                .withValue("blockStream.blockFileDir", "/blocks")
                .withValue("blockStream.buffer.bufferDirectory", "/buffer")
                .withValue("hedera.recordStream.logDir", "/records")
                .getOrCreateConfig();

        assertThat(DiskSpaceGate.dataDirs(appConfig, platformConfig()))
                .containsExactly(Path.of("/saved"), Path.of("/records"));
    }

    private DiskSpaceGate gateWith(final long reservedBytes, final Map<Path, FileStore> stores) {
        return new DiskSpaceGate(
                configProviderWith(reservedBytes),
                List.copyOf(new TreeMap<>(stores).keySet()),
                stores::get,
                sleeps::add);
    }

    private static ConfigProvider configProviderWith(final long reservedBytes) {
        final Configuration config = HederaTestConfigBuilder.create()
                .withValue("diskSpace.reservedBytes", reservedBytes)
                .withValue("diskSpace.recheckInterval", "1s")
                .getOrCreateConfig();
        return () -> new VersionedConfigImpl(config, 1L);
    }

    private static Configuration platformConfig() {
        return HederaTestConfigBuilder.create()
                .withValue("paths.savedStateDir", "/saved")
                .getOrCreateConfig();
    }
}

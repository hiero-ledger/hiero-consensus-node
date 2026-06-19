// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.FailureBlockUploadConfig;
import com.hedera.node.config.types.BlockStreamWriterMode;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssDetectionUploadCoordinatorTest {

    @TempDir
    Path tempDir;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfiguration;

    @Mock
    private FailureBlockUploadConfig issConfig;

    @Mock
    private BlockStreamConfig blockStreamConfig;

    @Mock
    private BlockUploader uploader;

    @Mock
    private IssBlockResolver diskResolver;

    @Mock
    private IssBufferBlockReader bufferReader;

    @Mock
    private SelfNodeAccountIdManager selfNodeAccountIdManager;

    @Captor
    private ArgumentCaptor<List<Path>> filesCaptor;

    private final InstantSource instantSource = InstantSource.fixed(Instant.parse("2026-06-16T14:32:05Z"));
    private static final String EXPECTED_FOLDER = "2026-06-16T14-32-05Z";

    private Path issBlockDir;
    private IssDetectionUploadCoordinator subject;

    @BeforeEach
    void setUp() {
        issBlockDir = tempDir.resolve("iss-blocks");
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        lenient()
                .when(versionedConfiguration.getConfigData(FailureBlockUploadConfig.class))
                .thenReturn(issConfig);
        lenient()
                .when(versionedConfiguration.getConfigData(BlockStreamConfig.class))
                .thenReturn(blockStreamConfig);
        lenient().when(issConfig.issBlockDir()).thenReturn(issBlockDir.toString());
        lenient().when(issConfig.precedingBlocks()).thenReturn(0);
        lenient().when(issConfig.captureTimeout()).thenReturn(Duration.ofSeconds(5));
        lenient().when(issConfig.uploadTimeout()).thenReturn(Duration.ofSeconds(5));
        lenient()
                .when(selfNodeAccountIdManager.getSelfNodeAccountId())
                .thenReturn(AccountID.newBuilder().accountNum(3).build());
        subject = new IssDetectionUploadCoordinator(
                configProvider,
                uploader,
                diskResolver,
                bufferReader,
                selfNodeAccountIdManager,
                FileSystems.getDefault(),
                instantSource);
    }

    @Test
    void noOpWhenDetectionDisabled() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(false);

        subject.captureAndUpload(IssType.SELF_ISS, 9);

        verifyNoInteractions(diskResolver, bufferReader, uploader);
    }

    @Test
    void fileModeCopiesResolvedBlockToIssDirAndUploadsToIss() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 0))
                .thenReturn(List.of(new IssBlockRef(IssType.SELF_ISS, 9, 7, List.of(sourceBlk))));
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), any()))
                .thenReturn(List.of("uri"));

        subject.captureAndUpload(IssType.SELF_ISS, 9);

        verify(uploader).uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), filesCaptor.capture());
        // Staged under a per-incident timestamp dir and kept on disk.
        final Path copied =
                issBlockDir.resolve("block-0.0.3").resolve(EXPECTED_FOLDER).resolve(base + ".blk.gz");
        assertThat(filesCaptor.getValue()).containsExactly(copied);
        assertThat(copied).exists();
        verifyNoInteractions(bufferReader);
    }

    @Test
    void fileModePollsUntilTheIssBlockBecomesDurable() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        // The ISS-round block isn't durable on disk yet (resolver empty), then it appears on a later poll.
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 0))
                .thenReturn(List.of())
                .thenReturn(List.of(new IssBlockRef(IssType.SELF_ISS, 9, 7, List.of(sourceBlk))));
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), any()))
                .thenReturn(List.of("uri"));

        subject.captureAndUpload(IssType.SELF_ISS, 9);

        verify(uploader).uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), filesCaptor.capture());
        final Path copied =
                issBlockDir.resolve("block-0.0.3").resolve(EXPECTED_FOLDER).resolve(base + ".blk.gz");
        assertThat(filesCaptor.getValue()).containsExactly(copied);
    }

    @Test
    void grpcModeUploadsBufferReconstructedBlockToIss() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);

        final Path issGz =
                issBlockDir.resolve("block-0.0.3").resolve(FileBlockItemWriter.longToFileName(7L) + ".iss.gz");
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of(issGz));
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), eq(List.of(issGz))))
                .thenReturn(List.of("uri"));

        subject.captureAndUpload(IssType.SELF_ISS, 9);

        verify(uploader).uploadBlockFiles(UploadCategory.ISS, EXPECTED_FOLDER, List.of(issGz));
        verifyNoInteractions(diskResolver);
    }

    @Test
    void skipsUploadWhenBlockNotLocatable() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        when(bufferReader.captureToDir(anyLong(), anyInt(), any())).thenReturn(List.of());

        subject.captureAndUpload(IssType.SELF_ISS, 9);

        verify(uploader, never()).uploadBlockFiles(any(), anyString(), any());
    }

    @Test
    void swallowsUploaderExceptions() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        final Path issGz =
                issBlockDir.resolve("block-0.0.3").resolve(FileBlockItemWriter.longToFileName(7L) + ".iss.gz");
        when(bufferReader.captureToDir(anyLong(), anyInt(), any())).thenReturn(List.of(issGz));
        when(uploader.uploadBlockFiles(any(), anyString(), any())).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> subject.captureAndUpload(IssType.SELF_ISS, 9)).doesNotThrowAnyException();
    }

    @Test
    void hardTimeoutAbandonsSlowUpload() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(issConfig.uploadTimeout()).thenReturn(Duration.ofMillis(200));
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        final Path issGz =
                issBlockDir.resolve("block-0.0.3").resolve(FileBlockItemWriter.longToFileName(7L) + ".iss.gz");
        when(bufferReader.captureToDir(anyLong(), anyInt(), any())).thenReturn(List.of(issGz));
        when(uploader.uploadBlockFiles(any(), anyString(), any())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return List.of("late");
        });

        final long start = System.nanoTime();
        assertThatCode(() -> subject.captureAndUpload(IssType.SELF_ISS, 9)).doesNotThrowAnyException();
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs).isLessThan(2_000L);
    }

    @Test
    void uploadDetectedIssOnFailureNoOpWhenNoIssRecorded() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);

        subject.uploadDetectedIssOnFailure();

        verifyNoInteractions(diskResolver, bufferReader, uploader);
    }

    @Test
    void uploadDetectedIssOnFailureUploadsTheRecordedBlockSynchronously() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(issConfig.captureTimeout()).thenReturn(Duration.ofMillis(100));
        // Detection runs first but the block is not yet durable on disk → nothing uploaded, but the ISS is recorded.
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 0)).thenReturn(List.of());
        subject.captureAndUpload(IssType.SELF_ISS, 9);
        verify(uploader, never()).uploadBlockFiles(any(), anyString(), any());

        // The fatal flush has now made the block durable; the synchronous failure path resolves it once and uploads.
        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 0))
                .thenReturn(List.of(new IssBlockRef(IssType.SELF_ISS, 9, 7, List.of(sourceBlk))));
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), any()))
                .thenReturn(List.of("uri"));

        subject.uploadDetectedIssOnFailure();

        verify(uploader).uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), filesCaptor.capture());
        final Path copied =
                issBlockDir.resolve("block-0.0.3").resolve(EXPECTED_FOLDER).resolve(base + ".blk.gz");
        assertThat(filesCaptor.getValue()).containsExactly(copied);
    }

    @Test
    void failurePathSkipsWhenDetectionAlreadyUploaded() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 0))
                .thenReturn(List.of(new IssBlockRef(IssType.SELF_ISS, 9, 7, List.of(sourceBlk))));
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), any()))
                .thenReturn(List.of("uri"));

        // Detection uploads the round; the later failure path must de-duplicate and not upload again.
        subject.captureAndUpload(IssType.SELF_ISS, 9);
        subject.uploadDetectedIssOnFailure();

        verify(uploader, times(1)).uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), any());
    }
}

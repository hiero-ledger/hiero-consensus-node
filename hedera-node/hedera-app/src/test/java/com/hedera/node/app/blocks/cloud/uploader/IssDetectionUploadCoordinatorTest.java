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
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Mock
    private BlockBufferService blockBufferService;

    @Mock
    private BlockNodeConnectionManager blockNodeConnectionManager;

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
        // A direct executor runs the offloaded capture synchronously so these tests can assert on the outcome.
        subject = new IssDetectionUploadCoordinator(
                configProvider,
                uploader,
                diskResolver,
                bufferReader,
                selfNodeAccountIdManager,
                FileSystems.getDefault(),
                instantSource,
                blockBufferService,
                blockNodeConnectionManager,
                Runnable::run);
    }

    @Test
    void noOpWhenDetectionDisabled() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(false);

        subject.captureAndUpload(IssType.SELF_ISS, 9);

        verifyNoInteractions(diskResolver, bufferReader, uploader);
    }

    @Test
    void captureAndUploadOffloadsWorkOffTheCallingThread() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        final List<Runnable> deferred = new ArrayList<>();
        final IssDetectionUploadCoordinator asyncSubject = new IssDetectionUploadCoordinator(
                configProvider,
                uploader,
                diskResolver,
                bufferReader,
                selfNodeAccountIdManager,
                FileSystems.getDefault(),
                instantSource,
                blockBufferService,
                blockNodeConnectionManager,
                deferred::add);

        asyncSubject.captureAndUpload(IssType.SELF_ISS, 9);

        // The blocking capture/upload was handed to the executor, so the calling (ISS dispatcher) thread was not
        // blocked on disk polling or the upload.
        assertThat(deferred).hasSize(1);
        verifyNoInteractions(diskResolver, bufferReader, uploader);

        // Running the deferred task performs the actual capture + upload.
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        final Path issGz =
                issBlockDir.resolve("block-0.0.3").resolve(FileBlockItemWriter.longToFileName(7L) + ".iss.gz");
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of(issGz));
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), eq(List.of(issGz))))
                .thenReturn(List.of("uri"));

        deferred.get(0).run();

        verify(uploader).uploadBlockFiles(UploadCategory.ISS, EXPECTED_FOLDER, List.of(issGz));
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
        // Staged under a per-incident timestamp dir, in the detection path's own subdir, and kept on disk.
        final Path copied = issBlockDir
                .resolve("block-0.0.3")
                .resolve(EXPECTED_FOLDER)
                .resolve("detect")
                .resolve(base + ".blk.gz");
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
        final Path copied = issBlockDir
                .resolve("block-0.0.3")
                .resolve(EXPECTED_FOLDER)
                .resolve("detect")
                .resolve(base + ".blk.gz");
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
    void fileModeDoesNotWriteMarkerWhenBlockNotLocatable() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(issConfig.captureTimeout()).thenReturn(Duration.ofMillis(50));
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 0)).thenReturn(List.of());

        subject.captureAndUpload(IssType.SELF_ISS, 9);

        // The .txt pointer fallback is GRPC-only; FILE mode with no resolvable block uploads nothing.
        verify(uploader, never()).uploadBlockFiles(any(), anyString(), any());
        verifyNoInteractions(bufferReader);
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
        final Path copied = issBlockDir
                .resolve("block-0.0.3")
                .resolve(EXPECTED_FOLDER)
                .resolve("failure")
                .resolve(base + ".blk.gz");
        assertThat(filesCaptor.getValue()).containsExactly(copied);
    }

    @Test
    void uploadDetectedIssOnFailureCapturesFromBufferInGrpcMode() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        // Detection records the ISS but the block is not in the buffer, so it only uploads a pointer marker; left to
        // fail (Mockito default empty result) so the round stays unmarked and the failure path still runs.
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of());
        subject.captureAndUpload(IssType.SELF_ISS, 9);
        final Path detectMarker = issBlockDir
                .resolve("block-0.0.3")
                .resolve(EXPECTED_FOLDER)
                .resolve("detect")
                .resolve("iss-round-9.txt");
        verify(uploader).uploadBlockFiles(UploadCategory.ISS, EXPECTED_FOLDER, List.of(detectMarker));

        // On CATASTROPHIC_FAILURE the failure path must capture the closed gRPC ISS block from the BUFFER (a closed
        // gRPC block is never written to disk), and must never consult the disk resolver.
        final Path issGz =
                issBlockDir.resolve("block-0.0.3").resolve(FileBlockItemWriter.longToFileName(7L) + ".iss.gz");
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of(issGz));
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), eq(List.of(issGz))))
                .thenReturn(List.of("uri"));

        subject.uploadDetectedIssOnFailure();

        verify(uploader).uploadBlockFiles(UploadCategory.ISS, EXPECTED_FOLDER, List.of(issGz));
        verifyNoInteractions(diskResolver);
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

    @Test
    void partialUploadOfOnlyAPrecedingBlockDoesNotMarkTheRoundDone() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(issConfig.precedingBlocks()).thenReturn(1);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);

        final Path precedingGz =
                issBlockDir.resolve("block-0.0.3").resolve(FileBlockItemWriter.longToFileName(6L) + ".iss.gz");
        final Path issGz =
                issBlockDir.resolve("block-0.0.3").resolve(FileBlockItemWriter.longToFileName(7L) + ".iss.gz");
        // The buffer capture returns the preceding context block then the ISS block (oldest→newest).
        when(bufferReader.captureToDir(eq(9L), eq(1), any())).thenReturn(List.of(precedingGz, issGz));
        // The preceding context block uploads successfully...
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), anyString(), eq(List.of(precedingGz))))
                .thenReturn(List.of("uriPreceding"));
        // ...but the EXACT ISS block fails on the detection attempt, then succeeds on the CATASTROPHIC_FAILURE attempt.
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), anyString(), eq(List.of(issGz))))
                .thenReturn(List.of())
                .thenReturn(List.of("uriIss"));

        subject.captureAndUpload(IssType.SELF_ISS, 9);
        // A context block succeeding while the ISS block failed must NOT mark the round done, so the authoritative
        // failure path still retries the exact ISS block.
        subject.uploadDetectedIssOnFailure();

        // The ISS block was attempted on BOTH paths (it would be attempted only once had the partial detection upload
        // wrongly marked the round complete and suppressed the failure-path retry).
        verify(uploader, times(2)).uploadBlockFiles(eq(UploadCategory.ISS), anyString(), eq(List.of(issGz)));
    }

    @Test
    void missingPrecedingContextBlockDoesNotAbortTheIssBlockCapture() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(issConfig.precedingBlocks()).thenReturn(1);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String issBase = FileBlockItemWriter.longToFileName(7L);
        // The context block was resolved but has since vanished (e.g. deleted by retention cleanup); only the ISS
        // block's file exists.
        final Path missingContext = tempDir.resolve(FileBlockItemWriter.longToFileName(6L) + ".blk.gz");
        final Path sourceBlk = Files.write(tempDir.resolve(issBase + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 1))
                .thenReturn(List.of(
                        new IssBlockRef(IssType.SELF_ISS, 9, 6, List.of(missingContext)),
                        new IssBlockRef(IssType.SELF_ISS, 9, 7, List.of(sourceBlk))));
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), any()))
                .thenReturn(List.of("uri"));

        subject.captureAndUpload(IssType.SELF_ISS, 9);

        // The unreadable context block is skipped, not fatal: the ISS block itself must still be staged and uploaded.
        verify(uploader).uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), filesCaptor.capture());
        final Path copied = issBlockDir
                .resolve("block-0.0.3")
                .resolve(EXPECTED_FOLDER)
                .resolve("detect")
                .resolve(issBase + ".blk.gz");
        assertThat(filesCaptor.getValue()).containsExactly(copied);
    }

    @Test
    void failurePathAwaitsAnInFlightDetectionUploadOfTheSameRound() throws Exception {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        final Path issGz =
                issBlockDir.resolve("block-0.0.3").resolve(FileBlockItemWriter.longToFileName(7L) + ".iss.gz");
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of(issGz));
        final CountDownLatch uploadEntered = new CountDownLatch(1);
        final CountDownLatch releaseUpload = new CountDownLatch(1);
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), anyString(), eq(List.of(issGz))))
                .thenAnswer(inv -> {
                    uploadEntered.countDown();
                    releaseUpload.await(5, TimeUnit.SECONDS);
                    return List.of("uri");
                });

        // The detection path claims round 9 and blocks inside the upload...
        final Thread detection = new Thread(() -> subject.captureAndUpload(IssType.SELF_ISS, 9));
        detection.start();
        assertThat(uploadEntered.await(5, TimeUnit.SECONDS)).isTrue();

        // ...while the CATASTROPHIC_FAILURE path arrives for the SAME round. It must AWAIT the in-flight outcome
        // rather than return instantly (which used to fire a false "NOT preserved" FATAL).
        final Thread failure = new Thread(subject::uploadDetectedIssOnFailure);
        failure.start();
        failure.join(300);
        assertThat(failure.isAlive()).isTrue();

        releaseUpload.countDown();
        failure.join(5_000);
        detection.join(5_000);
        assertThat(failure.isAlive()).isFalse();
        // The detection upload succeeded, so the awaiting failure path must not upload the round a second time.
        verify(uploader, times(1)).uploadBlockFiles(eq(UploadCategory.ISS), anyString(), any());
    }

    @Test
    void failurePathRetriesWhenAnInFlightDetectionUploadFails() throws Exception {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        final Path issGz =
                issBlockDir.resolve("block-0.0.3").resolve(FileBlockItemWriter.longToFileName(7L) + ".iss.gz");
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of(issGz));
        final CountDownLatch uploadEntered = new CountDownLatch(1);
        final CountDownLatch releaseUpload = new CountDownLatch(1);
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), anyString(), eq(List.of(issGz))))
                // The in-flight detection upload ultimately FAILS (empty result)...
                .thenAnswer(inv -> {
                    uploadEntered.countDown();
                    releaseUpload.await(5, TimeUnit.SECONDS);
                    return List.of();
                })
                // ...and the failure path's retry succeeds.
                .thenReturn(List.of("uri"));

        final Thread detection = new Thread(() -> subject.captureAndUpload(IssType.SELF_ISS, 9));
        detection.start();
        assertThat(uploadEntered.await(5, TimeUnit.SECONDS)).isTrue();

        final Thread failure = new Thread(subject::uploadDetectedIssOnFailure);
        failure.start();
        failure.join(300);
        assertThat(failure.isAlive()).isTrue();

        releaseUpload.countDown();
        failure.join(5_000);
        detection.join(5_000);
        assertThat(failure.isAlive()).isFalse();
        // The failure path observed the failed in-flight attempt and retried with its own staged files (it would have
        // uploaded only once had the failure path silently deferred to the doomed detection attempt).
        verify(uploader, times(2)).uploadBlockFiles(eq(UploadCategory.ISS), anyString(), eq(List.of(issGz)));
    }

    @Test
    void twoDistinctConcurrentRoundsAreBothUploaded() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        final Path gzA = issBlockDir.resolve("block-0.0.3").resolve(FileBlockItemWriter.longToFileName(7L) + ".iss.gz");
        final Path gzB = issBlockDir.resolve("block-0.0.3").resolve(FileBlockItemWriter.longToFileName(8L) + ".iss.gz");
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of(gzA));
        when(bufferReader.captureToDir(eq(10L), eq(0), any())).thenReturn(List.of(gzB));
        // While round 9's upload is in flight (so 9 is in the in-progress set), a DISTINCT round 10 arrives. With a
        // single global in-progress slot it would be dropped; a per-round set must let it through.
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), anyString(), eq(List.of(gzA))))
                .thenAnswer(inv -> {
                    subject.captureAndUpload(IssType.SELF_ISS, 10);
                    return List.of("uriA");
                });
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), anyString(), eq(List.of(gzB))))
                .thenReturn(List.of("uriB"));

        subject.captureAndUpload(IssType.SELF_ISS, 9);

        verify(uploader).uploadBlockFiles(eq(UploadCategory.ISS), anyString(), eq(List.of(gzA)));
        verify(uploader).uploadBlockFiles(eq(UploadCategory.ISS), anyString(), eq(List.of(gzB)));
    }

    @Test
    void grpcModeWritesAndUploadsPointerMarkerWhenBlockNotInBuffer() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of());
        when(blockNodeConnectionManager.activeConnectionSnapshot())
                .thenReturn(Optional.of(
                        new BlockNodeConnectionManager.ActiveBlockNodeSnapshot("bn-host", 8080, 0, 538L, 535L)));
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), any()))
                .thenReturn(List.of("uri"));

        subject.captureAndUpload(IssType.SELF_ISS, 9);

        // The missing block falls back to a .txt pointer, staged in the detection subdir and uploaded to iss/.
        verify(uploader).uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), filesCaptor.capture());
        final Path marker = issBlockDir
                .resolve("block-0.0.3")
                .resolve(EXPECTED_FOLDER)
                .resolve("detect")
                .resolve("iss-round-9.txt");
        assertThat(filesCaptor.getValue()).containsExactly(marker);
        assertThat(marker).exists();
        assertThat(Files.readString(marker))
                .contains("issRound=9")
                .contains("writerMode=GRPC")
                .contains("activeBlockNode=bn-host:8080");
        verifyNoInteractions(diskResolver);
    }

    @Test
    void detectionMarkerSuccessMakesFailurePathSkip() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of());
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), any()))
                .thenReturn(List.of("uri"));

        // The detection marker upload succeeds and marks the round, so the failure path must de-duplicate and skip.
        subject.captureAndUpload(IssType.SELF_ISS, 9);
        subject.uploadDetectedIssOnFailure();

        verify(uploader, times(1)).uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), any());
    }

    @Test
    void markerUploadFailureLeavesRoundUnmarkedSoFailurePathUploadsItsOwnMarker() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of());
        // The detection marker upload fails (empty), then the failure path's own marker upload succeeds.
        when(uploader.uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), any()))
                .thenReturn(List.of())
                .thenReturn(List.of("uri"));

        subject.captureAndUpload(IssType.SELF_ISS, 9);
        subject.uploadDetectedIssOnFailure();

        // Detection staged its marker under detect/ and failure under failure/; both were uploaded (round only marked
        // once the failure upload succeeded).
        verify(uploader, times(2)).uploadBlockFiles(eq(UploadCategory.ISS), eq(EXPECTED_FOLDER), filesCaptor.capture());
        final Path detectMarker = issBlockDir
                .resolve("block-0.0.3")
                .resolve(EXPECTED_FOLDER)
                .resolve("detect")
                .resolve("iss-round-9.txt");
        final Path failureMarker = issBlockDir
                .resolve("block-0.0.3")
                .resolve(EXPECTED_FOLDER)
                .resolve("failure")
                .resolve("iss-round-9.txt");
        assertThat(filesCaptor.getAllValues()).containsExactly(List.of(detectMarker), List.of(failureMarker));
        assertThat(failureMarker).exists();
    }

    @Test
    void markerWriteIoErrorIsSwallowedAndUploadsNothing() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of());
        // Point issBlockDir at a regular file so the marker's staging dir cannot be created.
        final Path notADir = Files.write(tempDir.resolve("not-a-dir"), new byte[] {1});
        when(issConfig.issBlockDir()).thenReturn(notADir.toString());

        assertThatCode(() -> subject.captureAndUpload(IssType.SELF_ISS, 9)).doesNotThrowAnyException();

        verify(uploader, never()).uploadBlockFiles(any(), anyString(), any());
    }
}

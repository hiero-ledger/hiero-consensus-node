// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import java.util.concurrent.Executor;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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
    private IssBlockResolver diskResolver;

    @Mock
    private IssBufferBlockReader bufferReader;

    @Mock
    private SelfNodeAccountIdManager selfNodeAccountIdManager;

    @Mock
    private BlockBufferService blockBufferService;

    @Mock
    private BlockNodeConnectionManager blockNodeConnectionManager;

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
        lenient()
                .when(selfNodeAccountIdManager.getSelfNodeAccountId())
                .thenReturn(AccountID.newBuilder().accountNum(3).build());
        // A direct executor runs the offloaded capture synchronously so these tests can assert on the staged files.
        subject = newSubject(Runnable::run);
    }

    private IssDetectionUploadCoordinator newSubject(final Executor captureExecutor) {
        return new IssDetectionUploadCoordinator(
                configProvider,
                diskResolver,
                bufferReader,
                selfNodeAccountIdManager,
                FileSystems.getDefault(),
                instantSource,
                blockBufferService,
                blockNodeConnectionManager,
                captureExecutor);
    }

    private Path detectDir() {
        return issBlockDir.resolve("block-0.0.3").resolve(EXPECTED_FOLDER).resolve("detect");
    }

    private Path failureDir() {
        return issBlockDir.resolve("block-0.0.3").resolve(EXPECTED_FOLDER).resolve("failure");
    }

    @Test
    void noOpWhenDetectionDisabled() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(false);

        subject.captureAndStage(IssType.SELF_ISS, 9);

        verifyNoInteractions(diskResolver, bufferReader);
        assertThat(issBlockDir).doesNotExist();
    }

    @Test
    void captureAndStageOffloadsCaptureOffTheCallingThread() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        final List<Runnable> deferred = new ArrayList<>();
        final IssDetectionUploadCoordinator asyncSubject = newSubject(deferred::add);

        asyncSubject.captureAndStage(IssType.SELF_ISS, 9);

        // The blocking capture was handed to the executor, so the ISS-notification dispatcher thread does no disk work.
        assertThat(deferred).hasSize(1);
        verifyNoInteractions(diskResolver, bufferReader);
    }

    @Test
    void fileModeStagesResolvedBlockUnderDetect() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 0))
                .thenReturn(List.of(new IssBlockRef(IssType.SELF_ISS, 9, 7, List.of(sourceBlk))));

        subject.captureAndStage(IssType.SELF_ISS, 9);

        final Path staged = detectDir().resolve(base + ".blk.gz");
        assertThat(staged).exists();
        assertThat(Files.readAllBytes(staged)).isEqualTo(new byte[] {1, 2, 3});
        verifyNoInteractions(bufferReader);
    }

    @Test
    void fileModeStagesPrecedingContextAndProofSidecarUnderDetect() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(issConfig.precedingBlocks()).thenReturn(1);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String precedingBase = FileBlockItemWriter.longToFileName(6L);
        final String issBase = FileBlockItemWriter.longToFileName(7L);
        final Path precedingBlk = Files.write(tempDir.resolve(precedingBase + ".blk.gz"), new byte[] {1});
        // The ISS block is a pending block, so it carries a .pnd.json proof sidecar that must be staged alongside it.
        final Path issPnd = Files.write(tempDir.resolve(issBase + ".pnd.gz"), new byte[] {2});
        final Path issProof = Files.write(tempDir.resolve(issBase + ".pnd.json"), new byte[] {3});
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 1))
                .thenReturn(List.of(
                        new IssBlockRef(IssType.SELF_ISS, 9, 6, List.of(precedingBlk)),
                        new IssBlockRef(IssType.SELF_ISS, 9, 7, List.of(issPnd, issProof))));

        subject.captureAndStage(IssType.SELF_ISS, 9);

        assertThat(detectDir().resolve(precedingBase + ".blk.gz")).exists();
        assertThat(detectDir().resolve(issBase + ".pnd.gz")).exists();
        assertThat(detectDir().resolve(issBase + ".pnd.json")).exists();
    }

    @Test
    void grpcModeStagesBufferReconstructedBlockUnderDetect() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);

        final String issName = FileBlockItemWriter.longToFileName(7L) + ".iss.gz";
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenAnswer(inv -> writeInto(inv.getArgument(2), issName));

        subject.captureAndStage(IssType.SELF_ISS, 9);

        // captureToDir is handed the detection subdir, so the reconstructed .iss.gz lands there.
        assertThat(detectDir().resolve(issName)).exists();
        verifyNoInteractions(diskResolver);
    }

    @Test
    void grpcModeStagesPointerMarkerWhenBlockNotInBuffer() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of());
        when(blockNodeConnectionManager.activeConnectionSnapshot())
                .thenReturn(Optional.of(
                        new BlockNodeConnectionManager.ActiveBlockNodeSnapshot("bn-host", 8080, 0, 538L, 535L)));

        subject.captureAndStage(IssType.SELF_ISS, 9);

        final Path marker = detectDir().resolve("iss-round-9.txt");
        assertThat(marker).exists();
        assertThat(Files.readString(marker))
                .contains("issRound=9")
                .contains("writerMode=GRPC")
                .contains("activeBlockNode=bn-host:8080");
        verifyNoInteractions(diskResolver);
    }

    @Test
    void stagingSameRoundTwiceDoesNotRestage() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 0))
                .thenReturn(List.of(new IssBlockRef(IssType.SELF_ISS, 9, 7, List.of(sourceBlk))));

        subject.captureAndStage(IssType.SELF_ISS, 9);
        subject.captureAndStage(IssType.SELF_ISS, 9);

        // The second call sees the round already staged and short-circuits before re-resolving.
        verify(diskResolver, times(1)).resolve(IssType.SELF_ISS, 9, 0);
        assertThat(detectDir().resolve(base + ".blk.gz")).exists();
    }

    @Test
    void failurePathNoOpWhenNoIssRecorded() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);

        subject.stageDetectedIssOnFailure();

        verifyNoInteractions(diskResolver, bufferReader);
        assertThat(issBlockDir).doesNotExist();
    }

    @Test
    void failurePathStagesRecordedBlockUnderFailure() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(issConfig.captureTimeout()).thenReturn(Duration.ZERO);
        // Detection records the ISS but the block is not yet durable on disk, so nothing is staged at detection.
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 0)).thenReturn(List.of());
        subject.captureAndStage(IssType.SELF_ISS, 9);
        assertThat(detectDir()).doesNotExist();

        // The fatal flush has made the block durable; the failure path resolves it once and stages it under failure/.
        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 0))
                .thenReturn(List.of(new IssBlockRef(IssType.SELF_ISS, 9, 7, List.of(sourceBlk))));

        subject.stageDetectedIssOnFailure();

        assertThat(failureDir().resolve(base + ".blk.gz")).exists();
    }

    @Test
    void failurePathDoesNotThrowWhenNothingCanBeStaged() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(issConfig.captureTimeout()).thenReturn(Duration.ZERO);
        // Neither the detection poll nor the failure resolve can locate the block.
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 0)).thenReturn(List.of());
        subject.captureAndStage(IssType.SELF_ISS, 9);

        assertThatCode(() -> subject.stageDetectedIssOnFailure()).doesNotThrowAnyException();
        assertThat(failureDir()).doesNotExist();
    }

    @Test
    void failurePathSkipsWhenDetectionAlreadyStaged() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(IssType.SELF_ISS, 9, 0))
                .thenReturn(List.of(new IssBlockRef(IssType.SELF_ISS, 9, 7, List.of(sourceBlk))));

        subject.captureAndStage(IssType.SELF_ISS, 9);
        subject.stageDetectedIssOnFailure();

        // Detection staged the round, so the failure path de-duplicates and never stages under failure/.
        assertThat(detectDir().resolve(base + ".blk.gz")).exists();
        assertThat(failureDir()).doesNotExist();
        verify(diskResolver, times(1)).resolve(IssType.SELF_ISS, 9, 0);
    }

    @Test
    void failurePathCapturesFromBufferInGrpcModeWhenDetectionStillPending() {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        // A deferred executor holds the detection capture, mimicking detection not completing before the halt arrives.
        final List<Runnable> deferred = new ArrayList<>();
        final IssDetectionUploadCoordinator asyncSubject = newSubject(deferred::add);

        final String issName = FileBlockItemWriter.longToFileName(7L) + ".iss.gz";
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenAnswer(inv -> writeInto(inv.getArgument(2), issName));

        asyncSubject.captureAndStage(IssType.SELF_ISS, 9);
        assertThat(deferred).hasSize(1);
        assertThat(detectDir()).doesNotExist();

        // The synchronous failure path captures the ISS block from the buffer into failure/.
        asyncSubject.stageDetectedIssOnFailure();

        assertThat(failureDir().resolve(issName)).exists();
        verifyNoInteractions(diskResolver);
    }

    @Test
    void grpcMarkerWriteErrorIsSwallowedAndStagesNothing() throws IOException {
        when(issConfig.issBlockUploadEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of());
        // Point issBlockDir at a regular file so the marker's staging dir cannot be created.
        final Path notADir = Files.write(tempDir.resolve("not-a-dir"), new byte[] {1});
        when(issConfig.issBlockDir()).thenReturn(notADir.toString());

        assertThatCode(() -> subject.captureAndStage(IssType.SELF_ISS, 9)).doesNotThrowAnyException();
    }

    /** Simulates the buffer reader writing a captured block into the target dir it is handed, and returns it. */
    private static List<Path> writeInto(final Path targetDir, final String fileName) throws IOException {
        Files.createDirectories(targetDir);
        final Path file = targetDir.resolve(fileName);
        Files.write(file, new byte[] {1, 2, 3});
        return List.of(file);
    }
}

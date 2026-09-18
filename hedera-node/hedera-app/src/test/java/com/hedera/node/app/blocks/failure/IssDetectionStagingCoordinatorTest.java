// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.FailureBlockStagingConfig;
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
import java.util.concurrent.Executor;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssDetectionStagingCoordinatorTest {

    @TempDir
    Path tempDir;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfiguration;

    @Mock
    private FailureBlockStagingConfig issConfig;

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

    private final InstantSource instantSource = InstantSource.fixed(Instant.parse("2026-06-16T14:32:05Z"));
    private static final String EXPECTED_FOLDER = "2026-06-16T14-32-05Z";

    private Path issBlockDir;
    private IssDetectionStagingCoordinator subject;

    @BeforeEach
    void setUp() {
        issBlockDir = tempDir.resolve("iss-blocks");
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        lenient()
                .when(versionedConfiguration.getConfigData(FailureBlockStagingConfig.class))
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

    private IssDetectionStagingCoordinator newSubject(final Executor captureExecutor) {
        return new IssDetectionStagingCoordinator(
                configProvider,
                diskResolver,
                bufferReader,
                selfNodeAccountIdManager,
                FileSystems.getDefault(),
                instantSource,
                blockBufferService,
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
        when(issConfig.issBlockStagingEnabled()).thenReturn(false);

        subject.captureAndStage(IssType.SELF_ISS, 9);

        verifyNoInteractions(diskResolver, bufferReader);
        assertThat(issBlockDir).doesNotExist();
    }

    @Test
    void captureAndStageOffloadsCaptureOffTheCallingThread() {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        final List<Runnable> deferred = new ArrayList<>();
        final IssDetectionStagingCoordinator asyncSubject = newSubject(deferred::add);

        asyncSubject.captureAndStage(IssType.SELF_ISS, 9);

        // The blocking capture was handed to the executor, so the ISS-notification dispatcher thread does no disk work.
        assertThat(deferred).hasSize(1);
        verifyNoInteractions(diskResolver, bufferReader);
    }

    @Test
    void fileModeStagesResolvedBlockUnderDetect() throws IOException {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(9, 0)).thenReturn(List.of(new IssBlockRef(7, List.of(sourceBlk))));

        subject.captureAndStage(IssType.SELF_ISS, 9);

        final Path staged = detectDir().resolve(base + ".blk.gz");
        assertThat(staged).exists();
        assertThat(Files.readAllBytes(staged)).isEqualTo(new byte[] {1, 2, 3});
        verifyNoInteractions(bufferReader);
        // The polling detection path must NOT clear the negative cache — only the authoritative failure path does.
        verify(diskResolver, never()).forgetUnreadable();
    }

    @Test
    void fileModeStagesPrecedingContextAndProofSidecarUnderDetect() throws IOException {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(issConfig.precedingBlocks()).thenReturn(1);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String precedingBase = FileBlockItemWriter.longToFileName(6L);
        final String issBase = FileBlockItemWriter.longToFileName(7L);
        final Path precedingBlk = Files.write(tempDir.resolve(precedingBase + ".blk.gz"), new byte[] {1});
        // The ISS block is a pending block, so it carries a .pnd.json proof sidecar that must be staged alongside it.
        final Path issPnd = Files.write(tempDir.resolve(issBase + ".pnd.gz"), new byte[] {2});
        final Path issProof = Files.write(tempDir.resolve(issBase + ".pnd.json"), new byte[] {3});
        when(diskResolver.resolve(9, 1))
                .thenReturn(List.of(
                        new IssBlockRef(6, List.of(precedingBlk)), new IssBlockRef(7, List.of(issPnd, issProof))));

        subject.captureAndStage(IssType.SELF_ISS, 9);

        assertThat(detectDir().resolve(precedingBase + ".blk.gz")).exists();
        assertThat(detectDir().resolve(issBase + ".pnd.gz")).exists();
        assertThat(detectDir().resolve(issBase + ".pnd.json")).exists();
    }

    @Test
    void grpcModeStagesBufferReconstructedBlockUnderDetect() {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
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
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of());

        subject.captureAndStage(IssType.SELF_ISS, 9);

        final Path marker = detectDir().resolve("iss-round-9.txt");
        assertThat(marker).exists();
        assertThat(Files.readString(marker)).contains("issRound=9").contains("writerMode=GRPC");
        verifyNoInteractions(diskResolver);
    }

    @Test
    void stagingSameRoundTwiceDoesNotRestage() throws IOException {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(9, 0)).thenReturn(List.of(new IssBlockRef(7, List.of(sourceBlk))));

        subject.captureAndStage(IssType.SELF_ISS, 9);
        subject.captureAndStage(IssType.SELF_ISS, 9);

        // The second call sees the round already staged and short-circuits before re-resolving.
        verify(diskResolver, times(1)).resolve(9, 0);
        assertThat(detectDir().resolve(base + ".blk.gz")).exists();
    }

    @Test
    void failurePathNoOpWhenNoIssRecorded() {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);

        subject.stageDetectedIssOnFailure();

        verifyNoInteractions(diskResolver, bufferReader);
        assertThat(issBlockDir).doesNotExist();
    }

    @Test
    void failurePathStagesRecordedBlockUnderFailure() throws IOException {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(issConfig.captureTimeout()).thenReturn(Duration.ZERO);
        // Detection records the ISS but the block is not yet durable on disk, so nothing is staged at detection.
        when(diskResolver.resolve(9, 0)).thenReturn(List.of());
        subject.captureAndStage(IssType.SELF_ISS, 9);
        assertThat(detectDir()).doesNotExist();

        // The fatal flush has made the block durable; the failure path resolves it once and stages it under failure/.
        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(9, 0)).thenReturn(List.of(new IssBlockRef(7, List.of(sourceBlk))));

        subject.stageDetectedIssOnFailure();

        assertThat(failureDir().resolve(base + ".blk.gz")).exists();
        // The authoritative one-shot resolve drops any negative-cache entry poisoned during the detection poll.
        verify(diskResolver).forgetUnreadable();
    }

    @Test
    void failurePathDoesNotThrowWhenNothingCanBeStaged() {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(issConfig.captureTimeout()).thenReturn(Duration.ZERO);
        // Neither the detection poll nor the failure resolve can locate the block.
        when(diskResolver.resolve(9, 0)).thenReturn(List.of());
        subject.captureAndStage(IssType.SELF_ISS, 9);

        assertThatCode(() -> subject.stageDetectedIssOnFailure()).doesNotThrowAnyException();
        assertThat(failureDir()).doesNotExist();
    }

    @Test
    void failurePathSkipsWhenDetectionAlreadyStaged() throws IOException {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(9, 0)).thenReturn(List.of(new IssBlockRef(7, List.of(sourceBlk))));

        subject.captureAndStage(IssType.SELF_ISS, 9);
        subject.stageDetectedIssOnFailure();

        // Detection staged the round, so the failure path de-duplicates and never stages under failure/.
        assertThat(detectDir().resolve(base + ".blk.gz")).exists();
        assertThat(failureDir()).doesNotExist();
        verify(diskResolver, times(1)).resolve(9, 0);
    }

    @Test
    void failurePathCapturesFromBufferInGrpcModeWhenDetectionStillPending() {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        // A deferred executor holds the detection capture, mimicking detection not completing before the halt arrives.
        final List<Runnable> deferred = new ArrayList<>();
        final IssDetectionStagingCoordinator asyncSubject = newSubject(deferred::add);

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
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of());
        // Point issBlockDir at a regular file so the marker's staging dir cannot be created.
        final Path notADir = Files.write(tempDir.resolve("not-a-dir"), new byte[] {1});
        when(issConfig.issBlockDir()).thenReturn(notADir.toString());

        assertThatCode(() -> subject.captureAndStage(IssType.SELF_ISS, 9)).doesNotThrowAnyException();
    }

    @Test
    void discardsCaptureWhenIssBlockCannotBeStagedButFailurePathStillRecovers() throws IOException {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(issConfig.precedingBlocks()).thenReturn(1);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String precedingBase = FileBlockItemWriter.longToFileName(6L);
        final String issBase = FileBlockItemWriter.longToFileName(7L);
        final Path precedingBlk = Files.write(tempDir.resolve(precedingBase + ".blk.gz"), new byte[] {1});
        final Path missingIss = tempDir.resolve(issBase + ".blk.gz"); // never created -> its copy fails
        when(diskResolver.resolve(9, 1))
                .thenReturn(
                        List.of(new IssBlockRef(6, List.of(precedingBlk)), new IssBlockRef(7, List.of(missingIss))));

        subject.captureAndStage(IssType.SELF_ISS, 9);

        // The preceding context block is copied best-effort, but the ISS block itself could not be staged, so the whole
        // capture is discarded: the ISS file is absent and the round is NOT marked staged.
        assertThat(detectDir().resolve(precedingBase + ".blk.gz")).exists();
        assertThat(detectDir().resolve(issBase + ".blk.gz")).doesNotExist();

        // Because the round was not marked staged, the failure path still stages it once the ISS block is present.
        final Path issBlk = Files.write(missingIss, new byte[] {2, 3});
        when(diskResolver.resolve(9, 1)).thenReturn(List.of(new IssBlockRef(7, List.of(issBlk))));
        subject.stageDetectedIssOnFailure();
        assertThat(failureDir().resolve(issBase + ".blk.gz")).exists();
    }

    @Test
    void resolveWithWaitPollsUntilTheBlockBecomesDurable() throws IOException {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(issConfig.captureTimeout()).thenReturn(Duration.ofSeconds(5));

        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        // Not durable on the first poll, durable on the next — resolveWithWait must retry rather than miss it.
        when(diskResolver.resolve(9, 0))
                .thenReturn(List.of())
                .thenReturn(List.of(new IssBlockRef(7, List.of(sourceBlk))));

        subject.captureAndStage(IssType.SELF_ISS, 9);

        assertThat(detectDir().resolve(base + ".blk.gz")).exists();
        verify(diskResolver, atLeast(2)).resolve(9, 0);
    }

    @Test
    void resolveWithWaitStopsWhenInterrupted() {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(issConfig.captureTimeout()).thenReturn(Duration.ofSeconds(30));
        when(diskResolver.resolve(9, 0)).thenReturn(List.of()); // never becomes durable

        // The capture runs synchronously on this (direct-executor) thread; a pending interrupt aborts the poll wait.
        Thread.currentThread().interrupt();
        subject.captureAndStage(IssType.SELF_ISS, 9);

        assertThat(Thread.interrupted()).isTrue(); // interrupt flag preserved by the wait, cleared here for other tests
        assertThat(detectDir()).doesNotExist();
    }

    @Test
    void currentIncidentFolderIsNullUntilFirstIssThenStable() {
        assertThat(subject.currentIncidentFolder()).isNull();

        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(issConfig.captureTimeout()).thenReturn(Duration.ZERO);
        when(diskResolver.resolve(9, 0)).thenReturn(List.of());

        subject.captureAndStage(IssType.SELF_ISS, 9);

        assertThat(subject.currentIncidentFolder()).isEqualTo(EXPECTED_FOLDER);
    }

    @Test
    void recordsOnlyTheFirstIssRoundAndFolderAcrossRepeatNotifications() throws IOException {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);
        when(issConfig.captureTimeout()).thenReturn(Duration.ZERO);
        when(diskResolver.resolve(9, 0)).thenReturn(List.of());

        // A diverged node re-notifies every round; only the first (round 9) is the incident we capture.
        subject.captureAndStage(IssType.SELF_ISS, 9);
        subject.captureAndStage(IssType.SELF_ISS, 10);
        subject.captureAndStage(IssType.SELF_ISS, 11);

        verify(diskResolver, times(1)).resolve(9, 0);
        verify(diskResolver, never()).resolve(eq(10L), anyInt());
        verify(diskResolver, never()).resolve(eq(11L), anyInt());
        assertThat(subject.currentIncidentFolder()).isEqualTo(EXPECTED_FOLDER);

        // The failure path stages the FIRST round (9), not the last-notified (11).
        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path sourceBlk = Files.write(tempDir.resolve(base + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(9, 0)).thenReturn(List.of(new IssBlockRef(7, List.of(sourceBlk))));
        subject.stageDetectedIssOnFailure();
        assertThat(failureDir().resolve(base + ".blk.gz")).exists();
    }

    @Test
    void grpcPointerMarkerDoesNotSuppressFailurePathRecovery() throws IOException {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.GRPC);
        // Detection finds the block missing from the buffer and stages only a .txt pointer.
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenReturn(List.of());

        subject.captureAndStage(IssType.SELF_ISS, 9);

        final Path marker = detectDir().resolve("iss-round-9.txt");
        assertThat(marker).exists();
        assertThat(Files.readString(marker)).contains("issRound=9").contains("writerMode=GRPC");

        // The pointer alone did NOT mark the round staged, so the failure path still recovers the real block.
        final String issName = FileBlockItemWriter.longToFileName(7L) + ".iss.gz";
        when(bufferReader.captureToDir(eq(9L), eq(0), any())).thenAnswer(inv -> writeInto(inv.getArgument(2), issName));
        subject.stageDetectedIssOnFailure();
        assertThat(failureDir().resolve(issName)).exists();
    }

    @Test
    void skipsAMissingPrecedingContextBlockButStillStagesTheIssBlock() throws IOException {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(issConfig.precedingBlocks()).thenReturn(1);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String precedingBase = FileBlockItemWriter.longToFileName(6L);
        final String issBase = FileBlockItemWriter.longToFileName(7L);
        final Path missingPreceding = tempDir.resolve(precedingBase + ".blk.gz"); // never created -> copy fails
        final Path issBlk = Files.write(tempDir.resolve(issBase + ".blk.gz"), new byte[] {1, 2, 3});
        when(diskResolver.resolve(9, 1))
                .thenReturn(
                        List.of(new IssBlockRef(6, List.of(missingPreceding)), new IssBlockRef(7, List.of(issBlk))));

        subject.captureAndStage(IssType.SELF_ISS, 9);

        // The preceding context block was unstageable and skipped, but the ISS block was staged.
        assertThat(detectDir().resolve(precedingBase + ".blk.gz")).doesNotExist();
        assertThat(detectDir().resolve(issBase + ".blk.gz")).exists();
    }

    @Test
    void skipsAMissingProofSidecarButStillStagesTheIssBlock() throws IOException {
        when(issConfig.issBlockStagingEnabled()).thenReturn(true);
        when(blockStreamConfig.writerMode()).thenReturn(BlockStreamWriterMode.FILE);

        final String issBase = FileBlockItemWriter.longToFileName(7L);
        final Path issPnd = Files.write(tempDir.resolve(issBase + ".pnd.gz"), new byte[] {2});
        final Path missingProof = tempDir.resolve(issBase + ".pnd.json"); // never created -> sidecar copy fails
        when(diskResolver.resolve(9, 0)).thenReturn(List.of(new IssBlockRef(7, List.of(issPnd, missingProof))));

        subject.captureAndStage(IssType.SELF_ISS, 9);

        // The ISS block's contents were staged even though its proof sidecar could not be copied.
        assertThat(detectDir().resolve(issBase + ".pnd.gz")).exists();
        assertThat(detectDir().resolve(issBase + ".pnd.json")).doesNotExist();
    }

    /** Simulates the buffer reader writing a captured block into the target dir it is handed, and returns it. */
    private static List<Path> writeInto(final Path targetDir, final String fileName) throws IOException {
        Files.createDirectories(targetDir);
        final Path file = targetDir.resolve(fileName);
        Files.write(file, new byte[] {1, 2, 3});
        return List.of(file);
    }
}

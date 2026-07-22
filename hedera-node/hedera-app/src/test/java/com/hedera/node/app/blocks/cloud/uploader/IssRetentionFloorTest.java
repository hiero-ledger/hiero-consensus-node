// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferTestDriver;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.app.blocks.impl.streaming.obs.BlockStreamingObs;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockBufferConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.FailureBlockUploadConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end (component) proof of the ISS-block capture path in {@code writerMode=GRPC}, wiring the REAL
 * {@link BlockBufferService} + {@link IssBufferBlockReader} + {@link IssDetectionUploadCoordinator} (with a synchronous
 * capture executor) against a recording {@link BlockUploader}. Unlike the existing coordinator/reader unit tests — which
 * mock the buffer — this drives the real buffer's acknowledge-and-prune lifecycle so it proves the block the ISS
 * notification asks for is still buffered after the detection lag, and that a genuinely-absent block is handled safely.
 *
 * <p>The retention floor's self-heal clamp is exercised separately in {@code BlockBufferServiceTest}; here the floor is
 * the shipped default (27), so pruning keeps exactly the window that must survive the lag.
 */
class IssRetentionFloorTest {

    /** consensus.roundsNonAncient default; the ISS notification can lag the offending round by this many rounds. */
    private static final int ROUNDS_NON_ANCIENT_LAG = 26;

    @TempDir
    Path tempDir;

    @Test
    void grpcModeCapturesIssBlockRetainedThroughTheDetectionLag() throws IOException {
        final ConfigProvider configProvider = configProvider(ROUNDS_NON_ANCIENT_LAG + 1); // 27 = the shipped default
        final BlockBufferService buffer = newStartedBuffer(configProvider);
        final IssBlockResolver diskResolver = mock(IssBlockResolver.class);
        final RecordingUploader uploader = new RecordingUploader();
        final IssDetectionUploadCoordinator coordinator =
                newCoordinator(configProvider, buffer, diskResolver, uploader);

        // ISS round/block 100, preceded by context blocks 95..99 and followed by a full detection lag of 26 blocks
        // (101..126). Acknowledge everything so the acked blocks become prune-eligible, then prune once.
        final long issBlock = 100L;
        for (long n = 95L; n <= issBlock + ROUNDS_NON_ANCIENT_LAG; n++) {
            produceBlock(buffer, n, n);
        }
        buffer.setLatestAcknowledgedBlock(issBlock + ROUNDS_NON_ANCIENT_LAG);
        BlockBufferTestDriver.checkBuffer(buffer);

        // With a floor of 27, pruning keeps exactly [100, 126]: the older context (95..99) is dropped, but the ISS
        // block survives at the oldest edge of the retained window.
        assertThat(buffer.getEarliestAvailableBlockNumber()).isEqualTo(issBlock);
        assertThat(buffer.getBlockState(issBlock)).isNotNull();
        assertThat(buffer.getBlockState(99L)).isNull();

        coordinator.captureAndUpload(IssType.SELF_ISS, issBlock);

        // In GRPC mode the block can only have come from the buffer (the disk resolver is never consulted).
        verifyNoInteractions(diskResolver);
        assertThat(uploader.calls).hasSize(1);
        final RecordingUploader.Call call = uploader.calls.getFirst();
        assertThat(call.category()).isEqualTo(UploadCategory.ISS);
        assertThat(call.files()).hasSize(1);
        final Path uploaded = call.files().getFirst();
        assertThat(uploaded.getFileName().toString())
                .isEqualTo(FileBlockItemWriter.longToFileName(issBlock) + ".iss.gz");
        // The reconstructed artifact re-parses as the exact ISS-round block.
        assertThat(firstRoundOf(uploaded)).isEqualTo(issBlock);
    }

    @Test
    void grpcModeSafelyMissesWhenIssBlockIsGenuinelyAbsent() {
        final ConfigProvider configProvider = configProvider(ROUNDS_NON_ANCIENT_LAG + 1);
        final BlockBufferService buffer = newStartedBuffer(configProvider);
        final IssBlockResolver diskResolver = mock(IssBlockResolver.class);
        final RecordingUploader uploader = new RecordingUploader();
        final IssDetectionUploadCoordinator coordinator =
                newCoordinator(configProvider, buffer, diskResolver, uploader);

        for (long n = 100L; n <= 126L; n++) {
            produceBlock(buffer, n, n);
        }
        buffer.setLatestAcknowledgedBlock(126L);
        BlockBufferTestDriver.checkBuffer(buffer);
        assertThat(buffer.getEarliestAvailableBlockNumber()).isEqualTo(100L);

        // Round 50 is far older than the earliest buffered block: the reader finds nothing, so nothing is uploaded and
        // the round is not marked done (the CATASTROPHIC_FAILURE path could still retry). No crash, no false upload.
        coordinator.captureAndUpload(IssType.SELF_ISS, 50L);

        assertThat(uploader.calls).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private BlockBufferService newStartedBuffer(final ConfigProvider configProvider) {
        final BlockBufferService buffer =
                new BlockBufferService(configProvider, mock(BlockStreamMetrics.class), mock(BlockStreamingObs.class));
        BlockBufferTestDriver.markStarted(buffer);
        return buffer;
    }

    private IssDetectionUploadCoordinator newCoordinator(
            final ConfigProvider configProvider,
            final BlockBufferService buffer,
            final IssBlockResolver diskResolver,
            final BlockUploader uploader) {
        final SelfNodeAccountIdManager selfNodeAccountIdManager = mock(SelfNodeAccountIdManager.class);
        when(selfNodeAccountIdManager.getSelfNodeAccountId())
                .thenReturn(AccountID.newBuilder().accountNum(3).build());
        return new IssDetectionUploadCoordinator(
                configProvider,
                uploader,
                diskResolver,
                new IssBufferBlockReader(buffer),
                selfNodeAccountIdManager,
                FileSystems.getDefault(),
                InstantSource.fixed(Instant.parse("2026-06-16T14:32:05Z")),
                Runnable::run); // synchronous capture — no async wait needed in the test
    }

    private ConfigProvider configProvider(final int minAckedBlocksToBuffer) {
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withConfigDataType(FailureBlockUploadConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("blockStream.buffer.maxBlocks", 200)
                .withValue("blockStream.buffer.minAckedBlocksToBuffer", minAckedBlocksToBuffer)
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", false)
                .withValue(
                        "blockStream.buffer.bufferDirectory",
                        tempDir.resolve("buffer").toString())
                .withValue("failureBlockUpload.issBlockUploadEnabled", true)
                .withValue(
                        "failureBlockUpload.issBlockDir",
                        tempDir.resolve("iss-blocks").toString())
                .withValue("failureBlockUpload.precedingBlocks", 0)
                .getOrCreateConfig();
        return () -> new VersionedConfigImpl(config, 1L);
    }

    private static void produceBlock(final BlockBufferService buffer, final long blockNumber, final long round) {
        buffer.openBlock(blockNumber);
        addItem(
                buffer,
                blockNumber,
                BlockItem.newBuilder()
                        .blockHeader(
                                BlockHeader.newBuilder().number(blockNumber).build())
                        .build());
        addItem(
                buffer,
                blockNumber,
                BlockItem.newBuilder()
                        .roundHeader(RoundHeader.newBuilder().roundNumber(round).build())
                        .build());
        buffer.closeBlock(blockNumber);
    }

    private static void addItem(final BlockBufferService buffer, final long blockNumber, final BlockItem item) {
        buffer.addItem(
                blockNumber, BlockItem.PROTOBUF.toBytes(item), item.item().kind());
    }

    /** Gunzips and parses a reconstructed {@code .iss.gz} artifact, returning its first {@code RoundHeader}'s round. */
    private static long firstRoundOf(final Path file) throws IOException {
        try (final GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            final Block block = Block.PROTOBUF.parse(
                    Bytes.wrap(in.readAllBytes()).toReadableSequentialData(), false, false, 512, 500_000_000);
            return block.items().stream()
                    .filter(BlockItem::hasRoundHeader)
                    .map(i -> i.roundHeaderOrThrow().roundNumber())
                    .findFirst()
                    .orElseThrow();
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    /** A {@link BlockUploader} that records each call and returns a non-empty URI list per file (upload "succeeds"). */
    private static final class RecordingUploader implements BlockUploader {
        private record Call(UploadCategory category, String incidentFolder, List<Path> files) {}

        private final List<Call> calls = new CopyOnWriteArrayList<>();

        @Override
        public List<String> uploadBlockFiles(
                final UploadCategory category, final String incidentFolder, final List<Path> contentsFiles) {
            calls.add(new Call(category, incidentFolder, List.copyOf(contentsFiles)));
            return contentsFiles.stream().map(p -> "uri://" + p.getFileName()).toList();
        }
    }
}

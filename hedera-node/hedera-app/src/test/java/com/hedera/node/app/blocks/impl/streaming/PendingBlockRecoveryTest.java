// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.hapi.utils.blocks.BlockFileUtils;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PendingBlockRecoveryTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {".pnd.zstd", ".pnd.gz", ".pnd"})
    void recoversAndCleansUpPendingPair(final String suffix) throws IOException {
        final var contents = writePending(1, 36, suffix, 1);
        final var blocks = load(2);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().contentsPath()).isEqualTo(contents);
        assertThat(blocks.getFirst().items()).containsExactly(item(1));

        FileBlockItemWriter.cleanUpPendingBlock(contents);
        assertThat(contents).doesNotExist();
        assertThat(blocks.getFirst().proofJsonPath()).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectsNewerSidecarBeforeCheckingContinuity(final boolean shortNameIsNewer) throws IOException {
        writePending(1, 36, ".pnd.gz", 1);
        final var legacy = writePending(2, 36, ".pnd.gz", shortNameIsNewer ? 1 : 3);
        final var current = writePending(2, 19, ".pnd.zstd", 2);

        final var blocks = load(3);
        assertThat(blocks)
                .extracting(FileBlockItemWriter.OnDiskPendingBlock::number)
                .containsExactly(1L, 2L);
        assertThat(blocks.getLast().contentsPath()).isEqualTo(shortNameIsNewer ? current : legacy);
    }

    @Test
    void selectsNewerSidecarWithinTheSameMillisecond() throws IOException {
        final var legacy = writePending(1, 36, ".pnd.gz", 1);
        final var current = writePending(1, 19, ".pnd.zstd", 1);
        Files.setLastModifiedTime(
                BlockFileUtils.pendingProofPathFor(legacy), FileTime.from(1_002_000, TimeUnit.NANOSECONDS));
        Files.setLastModifiedTime(
                BlockFileUtils.pendingProofPathFor(current), FileTime.from(1_001_000, TimeUnit.NANOSECONDS));
        assertThat(load(2).getFirst().contentsPath()).isEqualTo(legacy);
    }

    @Test
    void doesNotFallBackToStaleSidecarWhenNewestPayloadIsCorrupt() throws IOException {
        writePending(1, 36, ".pnd.gz", 1);
        writePending(2, 36, ".pnd.gz", 1);
        final var newest = writePending(2, 19, ".pnd.zstd", 2);
        Files.write(newest, new byte[] {1, 2, 3});

        assertThat(load(3)).isEmpty();
    }

    @Test
    void stopsAtGapButRetainsRecoverableSuffix() throws IOException {
        writePending(1, 36, ".pnd.gz", 1);
        writePending(3, 19, ".pnd.gz", 1);
        assertThat(load(4))
                .extracting(FileBlockItemWriter.OnDiskPendingBlock::number)
                .containsExactly(3L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void duplicateTimestampSelectionIsDeterministic(final boolean currentWrittenFirst) throws IOException {
        if (currentWrittenFirst) {
            writePending(1, 19, ".pnd.zstd", 1);
            writePending(1, 36, ".pnd.gz", 1);
        } else {
            writePending(1, 36, ".pnd.gz", 1);
            writePending(1, 19, ".pnd.zstd", 1);
        }
        final var first = load(2);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().contentsPath())
                .isEqualTo(tempDir.resolve("block-0.0.3/0000000000000000001.pnd.zstd"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void invalidNewestProofDoesNotFallBackToStaleMetadata(final boolean malformedJson) throws Exception {
        writePending(1, 36, ".pnd.gz", 1);
        final var newest = writePending(1, 19, ".pnd.zstd", 2);
        final var proofPath = BlockFileUtils.pendingProofPathFor(newest);
        if (malformedJson) {
            Files.writeString(proofPath, "not json");
        } else {
            final PendingProof mismatched;
            try (final var in = new ReadableStreamingData(proofPath)) {
                mismatched = PendingProof.JSON.parse(in).copyBuilder().block(99).build();
            }
            Files.writeString(proofPath, PendingProof.JSON.toJSON(mismatched));
        }
        assertThat(load(2)).isEmpty();
    }

    @Test
    void upgradeCompletesLegacyPendingBlockInNewFormatAndCleansUpBeforeNextRestart() throws IOException {
        final var legacy = writePending(1, 36, ".pnd.gz", 1);
        final var recovered = load(2).getFirst();
        final var writer = newWriter();
        writer.openBlock(recovered.number());
        recovered.items().forEach(item -> writer.writePbjItemAndBytes(item, BlockItem.PROTOBUF.toBytes(item)));
        final var proof = BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().block(1).build())
                .build();
        writer.writePbjItemAndBytes(proof, BlockItem.PROTOBUF.toBytes(proof));
        writer.closeCompleteBlock();
        FileBlockItemWriter.cleanUpPendingBlock(recovered.contentsPath());

        final var dir = tempDir.resolve("block-0.0.3");
        final var completed = dir.resolve("0000000000000000001.blk.zstd");
        assertThat(dir.resolve("0000000000000000001.mf")).isRegularFile();
        assertThat(BlockStreamAccess.blockFrom(completed).items()).containsExactly(item(1), proof);
        assertThat(legacy).doesNotExist();
        assertThat(recovered.proofJsonPath()).doesNotExist();
        assertThat(load(2)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedBlockIsNotRecoveredAgainFromRetainedLegacyPair(final boolean accountRenamed) throws IOException {
        final var legacy = writePending(1, 36, ".pnd.gz", 1);
        var current = writePending(1, 19, ".pnd.zstd", 2);
        final long account = accountRenamed ? 4 : 3;
        if (accountRenamed) {
            final var accountDir = Files.createDirectory(tempDir.resolve("block-0.0.4"));
            final var moved = accountDir.resolve(current.getFileName());
            Files.move(BlockFileUtils.pendingProofPathFor(current), BlockFileUtils.pendingProofPathFor(moved));
            Files.move(current, moved);
            current = moved;
        }
        final var recovered = load(2).getFirst();
        assertThat(recovered.contentsPath()).isEqualTo(current);
        final var completed = completeBlock(1, account);
        FileBlockItemWriter.cleanUpPendingBlock(recovered.contentsPath());
        final var originalBytes = Files.readAllBytes(completed);

        assertThat(load(2)).isEmpty();
        assertThat(legacy).isRegularFile();
        assertThat(BlockFileUtils.pendingProofPathFor(legacy)).isRegularFile();
        assertThat(Files.readAllBytes(completed)).isEqualTo(originalBytes);
        assertThat(BlockFileUtils.markerPathFor(completed)).isRegularFile();
    }

    @Test
    void recoveryStopsAtCompletedBoundaryInsteadOfReopeningStaleOlderSnapshots() throws IOException {
        writePending(3, 19, ".pnd.zstd", 1);
        writePending(2, 36, ".pnd.gz", 1);
        writePending(1, 36, ".pnd.gz", 1);
        completeBlock(2, 3);

        assertThat(load(4))
                .extracting(FileBlockItemWriter.OnDiskPendingBlock::number)
                .containsExactly(3L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completionBoundaryRequiresBothPayloadAndMarker(final boolean markerOnly) throws IOException {
        final var legacy = writePending(1, 36, ".pnd.gz", 1);
        if (markerOnly) {
            Files.createFile(legacy.resolveSibling("0000000000000000001.mf"));
        } else {
            Files.copy(legacy, legacy.resolveSibling("0000000000000000001.blk.gz"));
        }
        assertThat(load(2))
                .extracting(FileBlockItemWriter.OnDiskPendingBlock::contentsPath)
                .containsExactly(legacy);
    }

    @Test
    void intentionalRewriteInvalidatesOldCompletionMarkerBeforeTruncating() throws IOException {
        final var completed = completeBlock(1, 3);
        final var marker = BlockFileUtils.markerPathFor(completed);
        final var rewriting = newWriter();
        rewriting.openBlock(1);
        try {
            assertThat(Files.size(completed)).isZero();
            assertThat(marker).doesNotExist();
        } finally {
            rewriting.flushIncompleteBlock();
        }
    }

    @Test
    void failedMarkerInvalidationLeavesCompletedPayloadIntact() throws IOException {
        final var completed = completeBlock(1, 3);
        final var originalBytes = Files.readAllBytes(completed);
        final var marker = BlockFileUtils.markerPathFor(completed);
        Files.delete(marker);
        Files.createDirectory(marker);
        Files.writeString(marker.resolve("cannot-delete"), "block marker deletion");
        final var rewriting = newWriter();
        try {
            assertThatThrownBy(() -> rewriting.openBlock(1)).isInstanceOf(UncheckedIOException.class);
            assertThat(Files.readAllBytes(completed)).isEqualTo(originalBytes);
        } finally {
            rewriting.flushIncompleteBlock();
        }
    }

    private Path completeBlock(final long number, final long account) {
        final var writer = newWriter(account);
        writer.openBlock(number);
        final var header = item(number);
        writer.writePbjItemAndBytes(header, BlockItem.PROTOBUF.toBytes(header));
        final var proof = BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().block(number).build())
                .build();
        writer.writePbjItemAndBytes(proof, BlockItem.PROTOBUF.toBytes(proof));
        writer.closeCompleteBlock();
        return tempDir.resolve("block-0.0." + account).resolve(BlockFileUtils.blockFileName(number));
    }

    private FileBlockItemWriter newWriter() {
        return newWriter(3);
    }

    private FileBlockItemWriter newWriter(final long account) {
        final var configProvider = mock(ConfigProvider.class);
        final var configuration = mock(VersionedConfiguration.class);
        final var blockConfig = mock(BlockStreamConfig.class);
        when(configProvider.getConfiguration()).thenReturn(configuration);
        when(configuration.getConfigData(BlockStreamConfig.class)).thenReturn(blockConfig);
        when(blockConfig.blockFileDir()).thenReturn(tempDir.toString());
        when(blockConfig.blockFileBufferInnerSizeKb()).thenReturn(128);
        when(blockConfig.blockFileBufferOuterSizeKb()).thenReturn(512);
        final var selfId = mock(SelfNodeAccountIdManager.class);
        when(selfId.getSelfNodeAccountId())
                .thenReturn(AccountID.newBuilder().accountNum(account).build());
        return new FileBlockItemWriter(configProvider, selfId, FileSystems.getDefault());
    }

    private List<FileBlockItemWriter.OnDiskPendingBlock> load(final long followingBlock) {
        return FileBlockItemWriter.loadContiguousPendingBlocks(
                tempDir, followingBlock, Codec.DEFAULT_MAX_DEPTH, Codec.DEFAULT_MAX_SIZE);
    }

    private Path writePending(final long number, final int width, final String suffix, final long modifiedSeconds)
            throws IOException {
        final var dir = Files.createDirectories(tempDir.resolve("block-0.0.3"));
        final var base = String.format(Locale.ROOT, "%0" + width + "d", number);
        final var contents = dir.resolve(base + suffix);
        final var bytes =
                Block.PROTOBUF.toBytes(new Block(List.of(item(number)))).toByteArray();
        if (suffix.endsWith(".gz")) {
            try (final var out = new GZIPOutputStream(Files.newOutputStream(contents))) {
                out.write(bytes);
            }
        } else if (suffix.endsWith(".zstd")) {
            try (final var out = BlockFileUtils.newZstdOutputStream(Files.newOutputStream(contents))) {
                out.write(bytes);
            }
        } else {
            Files.write(contents, bytes);
        }
        final var proof = dir.resolve(base + ".pnd.json");
        Files.writeString(
                proof,
                PendingProof.JSON.toJSON(PendingProof.newBuilder()
                        .block(number)
                        .blockTimestamp(new Timestamp(1, 0))
                        .siblingHashesFromPrevBlockRoot(List.of(
                                new MerkleSiblingHash(true, Bytes.EMPTY),
                                new MerkleSiblingHash(true, Bytes.EMPTY),
                                new MerkleSiblingHash(true, Bytes.EMPTY),
                                new MerkleSiblingHash(true, Bytes.EMPTY)))
                        .build()));
        Files.setLastModifiedTime(proof, FileTime.fromMillis(modifiedSeconds * 1000));
        return contents;
    }

    private static BlockItem item(final long round) {
        return BlockItem.newBuilder().roundHeader(new RoundHeader(round)).build();
    }
}

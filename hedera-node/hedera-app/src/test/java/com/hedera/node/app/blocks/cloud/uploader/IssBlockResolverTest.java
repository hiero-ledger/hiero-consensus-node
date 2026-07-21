// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockStreamConfig;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssBlockResolverTest {

    @TempDir
    Path tempDir;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfiguration;

    @Mock
    private BlockStreamConfig blockStreamConfig;

    @Mock
    private SelfNodeAccountIdManager selfNodeAccountIdManager;

    private IssBlockResolver subject;

    @BeforeEach
    void setUp() {
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        lenient()
                .when(versionedConfiguration.getConfigData(BlockStreamConfig.class))
                .thenReturn(blockStreamConfig);
        lenient().when(blockStreamConfig.blockFileDir()).thenReturn(tempDir.toString());
        lenient().when(blockStreamConfig.maxReadDepth()).thenReturn(512);
        lenient().when(blockStreamConfig.maxReadBytesSize()).thenReturn(500_000_000);
        lenient()
                .when(selfNodeAccountIdManager.getSelfNodeAccountId())
                .thenReturn(AccountID.newBuilder().accountNum(3).build());
        subject = new IssBlockResolver(configProvider, selfNodeAccountIdManager, FileSystems.getDefault());
    }

    @Test
    void resolvesExactBlockAcrossExtensionsWithSidecar() throws IOException {
        // block 1: rounds [1..4] (complete), block 2: rounds [5..8] (pending), block 3: round 9 (open)
        writeBlock(1, 1, ".blk.gz");
        writeBlock(2, 5, ".pnd.gz");
        writeBlock(3, 9, ".open.gz");

        // round 6 lives in block 2 (first round 5 <= 6 < 9)
        final List<IssBlockRef> refs = subject.resolve(IssType.SELF_ISS, 6, 0);

        assertThat(refs).hasSize(1);
        final IssBlockRef ref = refs.getFirst();
        assertThat(ref.blockNumber()).isEqualTo(2);
        assertThat(ref.issType()).isEqualTo(IssType.SELF_ISS);
        assertThat(ref.round()).isEqualTo(6);
        // pending block includes its .pnd.json proof sidecar
        assertThat(ref.files()).hasSize(2);
        assertThat(ref.files().get(0).getFileName().toString()).endsWith(".pnd.gz");
        assertThat(ref.files().get(1).getFileName().toString()).endsWith(".pnd.json");
    }

    @Test
    void roundInNewestOpenBlockResolvesToOpenArtifact() throws IOException {
        writeBlock(1, 1, ".blk.gz");
        writeBlock(2, 5, ".blk.gz");
        writeBlock(3, 9, 12, ".open.gz"); // the flushed open block actually spans rounds 9..12

        final List<IssBlockRef> refs = subject.resolve(IssType.CATASTROPHIC_ISS, 12, 0);

        assertThat(refs).hasSize(1);
        assertThat(refs.getFirst().blockNumber()).isEqualTo(3);
        assertThat(refs.getFirst().files()).hasSize(1);
        assertThat(refs.getFirst().files().getFirst().getFileName().toString()).endsWith(".open.gz");
    }

    @Test
    void includesPrecedingContextBlocksOldestToNewest() throws IOException {
        writeBlock(1, 1, ".blk.gz");
        writeBlock(2, 5, ".blk.gz");
        writeBlock(3, 9, ".blk.gz");
        writeBlock(4, 13, ".open.gz");

        // round 10 is in block 3; request 2 preceding context blocks → blocks 1,2,3 oldest→newest
        final List<IssBlockRef> refs = subject.resolve(IssType.SELF_ISS, 10, 2);

        assertThat(refs).hasSize(3);
        assertThat(refs.stream().map(IssBlockRef::blockNumber)).containsExactly(1L, 2L, 3L);
    }

    @Test
    void clampsPrecedingBlocksAtEarliestRetained() throws IOException {
        writeBlock(5, 21, ".blk.gz");
        writeBlock(6, 25, 26, ".open.gz"); // block 6 spans rounds 25..26

        // request 10 preceding for the block containing round 26 (block 6) but only block 5 precedes it
        final List<IssBlockRef> refs = subject.resolve(IssType.SELF_ISS, 26, 10);

        assertThat(refs.stream().map(IssBlockRef::blockNumber)).containsExactly(5L, 6L);
    }

    @Test
    void ignoresCurrentlyOpenBlkGzWithoutCompletionMarker() throws IOException {
        writeBlock(1, 1, ".blk.gz");
        writeBlock(2, 5, 6, ".blk.gz"); // block 2 spans rounds 5..6 and is durable (.mf present)
        // The currently-open block: a partial/garbage ".blk.gz" with NO ".mf" marker (as on disk while it is being
        // written). It is the newest file, so a naive gallop would read it first and abort; it must be skipped.
        final Path nodeDir = tempDir.resolve("block-0.0.3");
        Files.write(nodeDir.resolve(FileBlockItemWriter.longToFileName(3L) + ".blk.gz"), new byte[] {1, 2, 3});

        // round 6 genuinely lives in durable block 2; the unmarked open block 3 must not break the search
        final List<IssBlockRef> refs = subject.resolve(IssType.SELF_ISS, 6, 0);

        assertThat(refs).hasSize(1);
        assertThat(refs.getFirst().blockNumber()).isEqualTo(2);
    }

    @Test
    void roundInUnmarkedOpenBlockIsNotSubstitutedByPrecedingBlock() throws IOException {
        writeBlock(1, 1, ".blk.gz"); // round [1]
        writeBlock(2, 5, ".blk.gz"); // round [5], durable — its last round is 5
        // The ISS round 6 lives in the currently-open block 3, still an unmarked ".blk.gz" (no ".mf"): excluded from
        // the search and unreadable. The resolver must NOT fall back to preceding block 2 just because 2's first round
        // (5) is <= 6 — that would upload the wrong block and let the coordinator mark the round done. It returns empty
        // so the detection path keeps polling until block 3 closes (gets its ".mf") or is flushed to ".open.gz".
        final Path nodeDir = tempDir.resolve("block-0.0.3");
        Files.write(nodeDir.resolve(FileBlockItemWriter.longToFileName(3L) + ".blk.gz"), new byte[] {1, 2, 3});

        assertThat(subject.resolve(IssType.SELF_ISS, 6, 0)).isEmpty();
    }

    @Test
    void roundWithinNewestMarkedBlockResolvesToIt() throws IOException {
        writeBlock(1, 1, ".blk.gz");
        writeBlock(2, 5, 8, ".blk.gz"); // newest durable block spans rounds 5..8; no open block on disk

        // round 7 is genuinely within the newest block, so it resolves immediately (no substitution, no waiting)
        final List<IssBlockRef> refs = subject.resolve(IssType.SELF_ISS, 7, 0);

        assertThat(refs).hasSize(1);
        assertThat(refs.getFirst().blockNumber()).isEqualTo(2);
    }

    @Test
    void returnsEmptyWhenRoundPrecedesEarliestRetainedBlock() throws IOException {
        writeBlock(10, 100, ".blk.gz");
        writeBlock(11, 104, ".blk.gz");

        assertThat(subject.resolve(IssType.SELF_ISS, 50, 0)).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoBlockDirExists() {
        assertThat(subject.resolve(IssType.SELF_ISS, 5, 0)).isEmpty();
    }

    @Test
    void resolvesRecentRoundAmongManyBlocks() throws IOException {
        for (int i = 1; i <= 200; i++) {
            writeBlock(i, i, ".blk.gz");
        }
        writeBlock(201, 201, ".open.gz");

        final List<IssBlockRef> refs = subject.resolve(IssType.SELF_ISS, 201, 0);
        assertThat(refs).hasSize(1);
        assertThat(refs.getFirst().blockNumber()).isEqualTo(201);
    }

    @Test
    void headerlessNewestOpenBlockDoesNotAbortResolve() throws IOException {
        // The real ISS block is a completed .blk.gz...
        writeBlock(1, 1, ".blk.gz");
        writeBlock(2, 5, ".blk.gz");
        // ...but the newest artifact is a header-only .open.gz: writes were dropped after notifyFatalEvent, so it has
        // a BlockHeader but no RoundHeader. It must be skipped, not abort the whole resolve and drop the real block.
        writeHeaderlessOpenBlock(3);

        final List<IssBlockRef> refs = subject.resolve(IssType.SELF_ISS, 6, 0);

        assertThat(refs).hasSize(1);
        assertThat(refs.getFirst().blockNumber()).isEqualTo(2);
    }

    @Test
    void corruptGzipCandidateIsSkippedNotFatal() throws IOException {
        writeBlock(1, 1, ".blk.gz");
        writeBlock(2, 5, ".blk.gz");
        // The newest artifact is a corrupt .open.gz whose gzip header is invalid, so GZIPInputStream's constructor
        // throws (this is the file-descriptor-leak site). It must be skipped, not abort the resolve.
        final Path nodeDir = tempDir.resolve("block-0.0.3");
        Files.createDirectories(nodeDir);
        Files.write(nodeDir.resolve(FileBlockItemWriter.longToFileName(3L) + ".open.gz"), new byte[] {0, 1, 2, 3});

        final List<IssBlockRef> refs = subject.resolve(IssType.SELF_ISS, 6, 0);

        assertThat(refs).hasSize(1);
        assertThat(refs.getFirst().blockNumber()).isEqualTo(2);
    }

    private void writeHeaderlessOpenBlock(final long number) throws IOException {
        final var block = Block.newBuilder()
                .items(List.of(BlockItem.newBuilder()
                        .blockHeader(BlockHeader.newBuilder().number(number).build())
                        .build()))
                .build();
        final byte[] raw = Block.PROTOBUF.toBytes(block).toByteArray();
        final Path nodeDir = tempDir.resolve("block-0.0.3");
        Files.createDirectories(nodeDir);
        try (final GZIPOutputStream out = new GZIPOutputStream(
                Files.newOutputStream(nodeDir.resolve(FileBlockItemWriter.longToFileName(number) + ".open.gz")))) {
            out.write(raw);
        }
    }

    private void writeBlock(final long number, final long firstRound, final String ext) throws IOException {
        writeBlock(number, firstRound, firstRound, ext);
    }

    /** Writes a block spanning {@code firstRound..lastRound} (a {@code RoundHeader} per round), so its last round is real. */
    private void writeBlock(final long number, final long firstRound, final long lastRound, final String ext)
            throws IOException {
        final List<BlockItem> items = new ArrayList<>();
        items.add(BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(number).build())
                .build());
        for (long r = firstRound; r <= lastRound; r++) {
            items.add(BlockItem.newBuilder()
                    .roundHeader(RoundHeader.newBuilder().roundNumber(r).build())
                    .build());
        }
        final byte[] raw =
                Block.PROTOBUF.toBytes(Block.newBuilder().items(items).build()).toByteArray();
        final Path nodeDir = tempDir.resolve("block-0.0.3");
        Files.createDirectories(nodeDir);
        final String baseName = FileBlockItemWriter.longToFileName(number);
        try (final GZIPOutputStream out =
                new GZIPOutputStream(Files.newOutputStream(nodeDir.resolve(baseName + ext)))) {
            out.write(raw);
        }
        if (".blk.gz".equals(ext)) {
            // A completed block has its ".mf" marker; the resolver only treats a marked ".blk.gz" as complete.
            Files.createFile(nodeDir.resolve(baseName + ".mf"));
        } else if (".pnd.gz".equals(ext)) {
            Files.writeString(nodeDir.resolve(baseName + ".pnd.json"), "{}");
        }
    }
}

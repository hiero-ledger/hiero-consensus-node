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
        writeBlock(3, 9, ".open.gz");

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
        writeBlock(6, 25, ".open.gz");

        // request 10 preceding for the block containing round 26 (block 6) but only block 5 precedes it
        final List<IssBlockRef> refs = subject.resolve(IssType.SELF_ISS, 26, 10);

        assertThat(refs.stream().map(IssBlockRef::blockNumber)).containsExactly(5L, 6L);
    }

    @Test
    void ignoresCurrentlyOpenBlkGzWithoutCompletionMarker() throws IOException {
        writeBlock(1, 1, ".blk.gz");
        writeBlock(2, 5, ".blk.gz");
        // The currently-open block: a partial/garbage ".blk.gz" with NO ".mf" marker (as on disk while it is being
        // written). It is the newest file, so a naive gallop would read it first and abort; it must be skipped.
        final Path nodeDir = tempDir.resolve("block-0.0.3");
        Files.write(nodeDir.resolve(FileBlockItemWriter.longToFileName(3L) + ".blk.gz"), new byte[] {1, 2, 3});

        // round 6 is in block 2; the unmarked open block 3 must not break the search
        final List<IssBlockRef> refs = subject.resolve(IssType.SELF_ISS, 6, 0);

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
    void gallopResolvesRecentRoundAmongManyBlocks() throws IOException {
        for (int i = 1; i <= 200; i++) {
            writeBlock(i, i, ".blk.gz");
        }
        writeBlock(201, 201, ".open.gz");

        final List<IssBlockRef> refs = subject.resolve(IssType.SELF_ISS, 201, 0);
        assertThat(refs).hasSize(1);
        assertThat(refs.getFirst().blockNumber()).isEqualTo(201);
    }

    private void writeBlock(final long number, final long firstRound, final String ext) throws IOException {
        final var block = Block.newBuilder()
                .items(List.of(
                        BlockItem.newBuilder()
                                .blockHeader(
                                        BlockHeader.newBuilder().number(number).build())
                                .build(),
                        BlockItem.newBuilder()
                                .roundHeader(RoundHeader.newBuilder()
                                        .roundNumber(firstRound)
                                        .build())
                                .build()))
                .build();
        final byte[] raw = Block.PROTOBUF.toBytes(block).toByteArray();
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

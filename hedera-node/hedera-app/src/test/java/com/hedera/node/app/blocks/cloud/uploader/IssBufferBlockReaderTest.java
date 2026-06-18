// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.blocks.impl.streaming.BlockState;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssBufferBlockReaderTest {

    @TempDir
    Path tempDir;

    @Mock
    private BlockBufferService blockBufferService;

    private IssBufferBlockReader subject;

    private void givenBuffer(final long earliest, final long last) {
        when(blockBufferService.getEarliestAvailableBlockNumber()).thenReturn(earliest);
        when(blockBufferService.getLastBlockNumberProduced()).thenReturn(last);
        subject = new IssBufferBlockReader(blockBufferService);
    }

    private void givenBlock(final long blockNumber, final long firstRound) {
        final BlockState state = new BlockState(blockNumber);
        state.addItem(BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(blockNumber).build())
                .build());
        state.addItem(BlockItem.newBuilder()
                .roundHeader(RoundHeader.newBuilder().roundNumber(firstRound).build())
                .build());
        lenient().when(blockBufferService.getBlockState(blockNumber)).thenReturn(state);
    }

    @Test
    void capturesTheBlockContainingTheRoundAsParseableIssGz() throws IOException {
        givenBuffer(1, 3);
        givenBlock(1, 1);
        givenBlock(2, 5);
        givenBlock(3, 9);

        // round 6 lives in block 2 (first round 5 <= 6 < 9)
        final List<Path> written = subject.captureToDir(6, 0, tempDir);

        assertThat(written).hasSize(1);
        final Path file = written.getFirst();
        assertThat(file.getFileName().toString()).isEqualTo(FileBlockItemWriter.longToFileName(2L) + ".iss.gz");
        // The reconstructed artifact re-parses as a Block whose first round header is round 5.
        final Block block = parse(file);
        assertThat(block.items().stream()
                        .filter(BlockItem::hasRoundHeader)
                        .map(i -> i.roundHeaderOrThrow().roundNumber())
                        .findFirst())
                .hasValue(5L);
    }

    @Test
    void capturesPrecedingBlocksOldestToNewest() throws IOException {
        givenBuffer(1, 4);
        givenBlock(1, 1);
        givenBlock(2, 5);
        givenBlock(3, 9);
        givenBlock(4, 13);

        // round 10 is in block 3; request 2 preceding → blocks 1,2,3
        final List<Path> written = subject.captureToDir(10, 2, tempDir);

        assertThat(written.stream().map(p -> p.getFileName().toString()))
                .containsExactly(
                        FileBlockItemWriter.longToFileName(1L) + ".iss.gz",
                        FileBlockItemWriter.longToFileName(2L) + ".iss.gz",
                        FileBlockItemWriter.longToFileName(3L) + ".iss.gz");
    }

    @Test
    void clampsPrecedingBlocksAtEarliestBuffered() throws IOException {
        givenBuffer(5, 6);
        givenBlock(5, 21);
        givenBlock(6, 25);

        // request 10 preceding for the block containing round 26 (block 6) but only block 5 precedes it
        final List<Path> written = subject.captureToDir(26, 10, tempDir);

        assertThat(written.stream().map(p -> p.getFileName().toString()))
                .containsExactly(
                        FileBlockItemWriter.longToFileName(5L) + ".iss.gz",
                        FileBlockItemWriter.longToFileName(6L) + ".iss.gz");
    }

    @Test
    void returnsEmptyWhenRoundPrecedesEarliestBufferedBlock() {
        givenBuffer(10, 11);
        givenBlock(10, 100);
        givenBlock(11, 104);

        assertThat(subject.captureToDir(50, 0, tempDir)).isEmpty();
    }

    @Test
    void returnsEmptyWhenBufferIsEmpty() {
        givenBuffer(-1, -1);

        assertThat(subject.captureToDir(5, 0, tempDir)).isEmpty();
    }

    private static Block parse(final Path file) throws IOException {
        try (final GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            try {
                return Block.PROTOBUF.parse(
                        Bytes.wrap(in.readAllBytes()).toReadableSequentialData(), false, false, 512, 500_000_000);
            } catch (final Exception e) {
                throw new IOException(e);
            }
        }
    }
}

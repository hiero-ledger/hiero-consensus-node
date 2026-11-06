// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.node.app.hapi.utils.blocks.BlockFileUtils;
import com.hedera.statevalidation.blockstream.tools.BlockRoundExtractor;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockFileSelectionTest {
    @Test
    void pcesSelectionUsesCompletedPayloadAndKeepsBlocksContiguous(@TempDir final Path dir) throws Exception {
        final var completed = writeCompleted(dir, 42, 123);
        writeLegacyPending(dir, 42, 122);
        final var next = writeCompleted(dir, 43, 124);

        final var selected = orderedPcesBlocks(dir);
        assertEquals(List.of(completed, next), selected);
        assertArrayEquals(new long[] {123, 123}, BlocksToPcesWorkflow.roundSpan(selected.getFirst()));
        validateRecoveryBlocks(dir);
    }

    @Test
    void roundIndexAndRoundSearchUseCompletedPayloadBesideRetainedPending(@TempDir final Path dir) throws Exception {
        final var completed = writeCompleted(dir, 42, 123);
        writeLegacyPending(dir, 42, 122);

        assertEquals(Map.of(42L, completed), BlockRoundExtractor.indexByBlockNumber(dir));
        assertEquals(completed, BlockRoundExtractor.findBlockContainingRound(dir, 123));
        assertNull(BlockRoundExtractor.findBlockContainingRound(dir, 122));
    }

    @Test
    void missingBlocksStillFailBothWorkflowContinuityChecks(@TempDir final Path dir) throws Exception {
        writeCompleted(dir, 42, 123);
        writeLegacyPending(dir, 42, 122);
        writeCompleted(dir, 44, 125);

        final var pcesFailure = assertThrows(InvocationTargetException.class, () -> orderedPcesBlocks(dir));
        assertTrue(pcesFailure.getCause().getMessage().contains("missing 1 block"));
        final var recoveryFailure = assertThrows(InvocationTargetException.class, () -> validateRecoveryBlocks(dir));
        assertTrue(recoveryFailure.getCause().getMessage().contains("Missing blocks: [43]"));
    }

    private static Path writeCompleted(final Path dir, final long number, final long round) throws IOException {
        final var path = dir.resolve(BlockFileUtils.blockFileName(number));
        writeBlock(path, number, round);
        Files.createFile(BlockFileUtils.markerPathFor(path));
        return path;
    }

    private static void writeLegacyPending(final Path dir, final long number, final long round) throws IOException {
        final var path = dir.resolve(String.format(Locale.ROOT, "%036d.pnd.gz", number));
        writeBlock(path, number, round);
        final var proof = BlockFileUtils.pendingProofPathFor(path);
        Files.writeString(proof, "{}");
        Files.setLastModifiedTime(proof, FileTime.fromMillis(System.currentTimeMillis() + 60_000L));
    }

    private static void writeBlock(final Path path, final long number, final long round) throws IOException {
        final var block = Block.newBuilder()
                .items(
                        BlockItem.newBuilder()
                                .blockHeader(
                                        BlockHeader.newBuilder().number(number).build())
                                .build(),
                        BlockItem.newBuilder()
                                .roundHeader(RoundHeader.newBuilder()
                                        .roundNumber(round)
                                        .build())
                                .build())
                .build();
        try (final var out = path.toString().endsWith(".gz")
                ? new GZIPOutputStream(Files.newOutputStream(path))
                : BlockFileUtils.newZstdOutputStream(Files.newOutputStream(path))) {
            out.write(Block.PROTOBUF.toBytes(block).toByteArray());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Path> orderedPcesBlocks(final Path dir) throws Exception {
        final var method = BlocksToPcesWorkflow.class.getDeclaredMethod("orderedBlockFiles", Path.class);
        method.setAccessible(true);
        return (List<Path>) method.invoke(null, dir);
    }

    private static void validateRecoveryBlocks(final Path dir) throws Exception {
        final var method = BlockStreamRecoveryWorkflow.class.getDeclaredMethod("validateNoMissingBlocks", Path.class);
        method.setAccessible(true);
        method.invoke(null, dir);
    }
}

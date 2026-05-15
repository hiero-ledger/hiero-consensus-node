// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockStreamAccessTest {

    @TempDir
    Path tempDir;

    private static final Block SAMPLE_BLOCK = Block.newBuilder()
            .items(BlockItem.newBuilder()
                    .blockHeader(BlockHeader.newBuilder().number(42L).build())
                    .build())
            .build();

    @Test
    void blockFrom_withPlainBlockFile_returnsBlock() throws IOException {
        final var blockBytes = Block.PROTOBUF.toBytes(SAMPLE_BLOCK).toByteArray();
        final var blockFile = tempDir.resolve("000000000000000000042.blk");
        Files.write(blockFile, blockBytes);

        final var result = BlockStreamAccess.blockFrom(blockFile);

        assertEquals(SAMPLE_BLOCK, result);
    }

    @Test
    void blockFrom_withGzippedBlockFile_returnsBlock() throws IOException {
        final var blockBytes = Block.PROTOBUF.toBytes(SAMPLE_BLOCK).toByteArray();
        final var gzippedFile = tempDir.resolve("000000000000000000042.blk.gz");
        try (var baos = new ByteArrayOutputStream();
                var gzip = new GZIPOutputStream(baos)) {
            gzip.write(blockBytes);
            gzip.finish();
            Files.write(gzippedFile, baos.toByteArray());
        }

        final var result = BlockStreamAccess.blockFrom(gzippedFile);

        assertEquals(SAMPLE_BLOCK, result);
    }

    @Test
    void blockFrom_withInvalidBytes_throwsRuntimeException() throws IOException {
        final var blockFile = tempDir.resolve("000000000000000000001.blk");
        Files.write(blockFile, new byte[] {0x06});

        assertThrows(RuntimeException.class, () -> BlockStreamAccess.blockFrom(blockFile));
    }
}

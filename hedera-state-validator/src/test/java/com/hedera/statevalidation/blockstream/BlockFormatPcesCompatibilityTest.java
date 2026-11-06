// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.node.app.hapi.utils.blocks.BlockFileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockFormatPcesCompatibilityTest {
    @Test
    void bothFormatsProduceIdenticalPces(@TempDir final Path dir) throws IOException {
        final var current = Files.createDirectory(dir.resolve("zstd"));
        final var legacy = Files.createDirectory(dir.resolve("gzip"));
        for (long number = 0; number < 3; number++) {
            final long round = number + 1;
            final var block = Block.newBuilder()
                    .items(
                            BlockItem.newBuilder()
                                    .blockHeader(BlockHeader.newBuilder()
                                            .number(number)
                                            .build())
                                    .build(),
                            BlockItem.newBuilder()
                                    .roundHeader(new RoundHeader(round))
                                    .build(),
                            BlockItem.newBuilder()
                                    .eventHeader(EventHeader.newBuilder()
                                            .eventCore(EventCore.newBuilder()
                                                    .birthRound(round)
                                                    .timeCreated(new Timestamp(round, 0))
                                                    .build())
                                            .build())
                                    .build())
                    .build();
            final var bytes = Block.PROTOBUF.toBytes(block).toByteArray();
            final var currentPath = current.resolve(BlockFileUtils.blockFileName(number));
            try (final var out = BlockFileUtils.newZstdOutputStream(Files.newOutputStream(currentPath))) {
                out.write(bytes);
            }
            Files.createFile(BlockFileUtils.markerPathFor(currentPath));
            final var legacyPath = legacy.resolve(String.format(Locale.ROOT, "%036d.blk.gz", number));
            try (final var out = new GZIPOutputStream(Files.newOutputStream(legacyPath))) {
                out.write(bytes);
            }
            Files.createFile(BlockFileUtils.markerPathFor(legacyPath));
        }
        final var currentOutput = dir.resolve("current-pces");
        final var legacyOutput = dir.resolve("legacy-pces");
        assertEquals(3, BlocksToPcesWorkflow.convert(current, currentOutput, 1, 1, 2));
        assertEquals(3, BlocksToPcesWorkflow.convert(legacy, legacyOutput, 1, 1, 2));

        final var currentFiles = pcesFiles(currentOutput);
        final var legacyFiles = pcesFiles(legacyOutput);
        assertEquals(1, currentFiles.size());
        assertEquals(1, legacyFiles.size());
        assertArrayEquals(Files.readAllBytes(legacyFiles.getFirst()), Files.readAllBytes(currentFiles.getFirst()));
    }

    private static List<Path> pcesFiles(final Path dir) throws IOException {
        try (final var paths = Files.walk(dir)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".pces"))
                    .sorted()
                    .toList();
        }
    }
}

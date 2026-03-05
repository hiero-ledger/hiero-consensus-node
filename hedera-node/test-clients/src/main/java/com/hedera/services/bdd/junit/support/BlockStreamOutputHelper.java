// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import com.hedera.hapi.block.stream.Block;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Shared helper for persisting block streams under the test-client output directory.
 */
public final class BlockStreamOutputHelper {
    private BlockStreamOutputHelper() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static @NonNull Path configuredLogDir() {
        final var configured = System.getProperty("hapi.test.log.dir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.dir"), "build", "hapi-test", "output").toAbsolutePath();
    }

    public static @NonNull Path writeBlocksToConfiguredOutput(
            @NonNull final List<Block> blocks, @Nullable final String metadataFileContents) throws IOException {
        final var blockStreamsDir = configuredLogDir().resolve("blockStreams");
        writeBlocks(blockStreamsDir, blocks);
        if (metadataFileContents != null && !metadataFileContents.isBlank()) {
            Files.writeString(blockStreamsDir.resolve("dumped-on-failure.txt"), metadataFileContents);
        }
        return blockStreamsDir;
    }

    public static void writeBlocks(@NonNull final Path blockStreamsDir, @NonNull final List<Block> blocks)
            throws IOException {
        Files.createDirectories(blockStreamsDir);
        cleanBlockStreamsDir(blockStreamsDir);
        for (final var block : blocks) {
            final long blockNumber = block.items().isEmpty() ? -1 : block.items().getFirst().blockHeaderOrThrow().number();
            if (blockNumber < 0) {
                continue;
            }
            final var baseName = String.format("%036d", blockNumber);
            final var blockPath = blockStreamsDir.resolve(baseName + ".blk.gz");
            try (final var out = new GZIPOutputStream(Files.newOutputStream(blockPath))) {
                out.write(Block.PROTOBUF.toBytes(block).toByteArray());
            }
            Files.writeString(blockStreamsDir.resolve(baseName + ".mf"), "");
        }
    }

    private static void cleanBlockStreamsDir(@NonNull final Path blockStreamsDir) throws IOException {
        try (final var stream = Files.walk(blockStreamsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        final var name = path.getFileName().toString();
                        return name.endsWith(".blk")
                                || name.endsWith(".blk.gz")
                                || name.endsWith(".mf")
                                || name.equals("dumped-on-failure.txt");
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new IllegalStateException("Unable to clean stale block stream file " + path, e);
                        }
                    });
        }
    }
}

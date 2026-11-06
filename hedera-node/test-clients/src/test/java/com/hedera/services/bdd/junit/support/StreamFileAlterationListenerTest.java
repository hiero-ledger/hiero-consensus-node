// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.node.app.hapi.utils.blocks.BlockFileUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamFileAlterationListenerTest {
    @Test
    void exposesBlocksFromZstdAndLegacyMarkerSiblings(@TempDir final Path dir) throws Exception {
        final var block = Block.newBuilder()
                .items(BlockItem.newBuilder()
                        .blockHeader(BlockHeader.newBuilder().number(42).build())
                        .build())
                .build();
        final var marker = Files.createFile(dir.resolve("0000000000000000042.mf"));
        for (final var suffix : new String[] {".blk.zstd", ".blk.gz", ".blk"}) {
            final var path = dir.resolve("0000000000000000042" + suffix);
            try (final var out = suffix.endsWith(".zstd")
                    ? BlockFileUtils.newZstdOutputStream(Files.newOutputStream(path))
                    : suffix.endsWith(".gz")
                            ? new GZIPOutputStream(Files.newOutputStream(path))
                            : Files.newOutputStream(path)) {
                out.write(Block.PROTOBUF.toBytes(block).toByteArray());
            }
            final var observed = new AtomicReference<Block>();
            final var listener = new StreamFileAlterationListener();
            listener.subscribe(new StreamDataListener() {
                @Override
                public void onNewBlock(final Block block) {
                    observed.set(block);
                }
            });
            listener.onFileCreate(marker.toFile());
            assertEquals(block, observed.get(), suffix);
            Files.delete(path);
        }
    }
}

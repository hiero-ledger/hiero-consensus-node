// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static com.hedera.services.bdd.junit.support.StreamFileAccess.STREAM_FILE_ACCESS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamFileAccessTest {
    private static final int REPLAYED_BLOCKS = 200;
    private static final int LIVE_BLOCKS = 5;
    private static final long CALLBACK_MILLIS = 5L;
    private static final long TIMEOUT_MILLIS = 30_000L;

    @TempDir
    private Path blocksDir;

    @Test
    void replayingListenerIsNeverCalledConcurrently() throws Exception {
        for (int i = 0; i < REPLAYED_BLOCKS; i++) {
            writeBlock(i);
        }
        final var inFlight = new AtomicInteger();
        final var maxInFlight = new AtomicInteger();
        final Set<Long> seen = ConcurrentHashMap.newKeySet();
        final var unsubscribe = STREAM_FILE_ACCESS.subscribe(blocksDir, new StreamDataListener() {
            @Override
            public boolean replayExistingFiles() {
                return true;
            }

            @Override
            public void onNewBlock(@NonNull final Block block) {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    // Stay in the callback long enough for the replay and the live monitor to overlap
                    MILLISECONDS.sleep(CALLBACK_MILLIS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                seen.add(block.items().getFirst().blockHeaderOrThrow().number());
                inFlight.decrementAndGet();
            }
        });
        try {
            // Written while the replay is still running, so only the live monitor delivers these
            for (int i = REPLAYED_BLOCKS; i < REPLAYED_BLOCKS + LIVE_BLOCKS; i++) {
                writeBlock(i);
            }
            final var deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while (seen.size() < REPLAYED_BLOCKS + LIVE_BLOCKS && System.currentTimeMillis() < deadline) {
                MILLISECONDS.sleep(50L);
            }
        } finally {
            unsubscribe.run();
        }

        assertEquals(REPLAYED_BLOCKS + LIVE_BLOCKS, seen.size());
        assertEquals(1, maxInFlight.get(), "The replay and the live monitor must not call a listener concurrently");
    }

    private void writeBlock(final long number) throws IOException {
        final var block = Block.newBuilder()
                .items(BlockItem.newBuilder()
                        .blockHeader(BlockHeader.newBuilder().number(number).build())
                        .build())
                .build();
        final var name = String.format("%036d", number);
        try (final var out = new GZIPOutputStream(Files.newOutputStream(blocksDir.resolve(name + ".blk.gz")))) {
            out.write(Block.PROTOBUF.toBytes(block).toByteArray());
        }
        // The marker goes last, as a node writes it only once the block file is complete
        Files.createFile(blocksDir.resolve(name + ".mf"));
    }
}

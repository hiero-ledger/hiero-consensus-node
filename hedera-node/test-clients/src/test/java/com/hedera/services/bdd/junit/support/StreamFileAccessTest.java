// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static com.hedera.services.bdd.junit.support.FullStateValuesTest.FIRST;
import static com.hedera.services.bdd.junit.support.FullStateValuesTest.SECOND;
import static com.hedera.services.bdd.junit.support.FullStateValuesTest.accountIn;
import static com.hedera.services.bdd.junit.support.FullStateValuesTest.block;
import static com.hedera.services.bdd.junit.support.FullStateValuesTest.full;
import static com.hedera.services.bdd.junit.support.FullStateValuesTest.partial;
import static com.hedera.services.bdd.junit.support.StreamFileAccess.STREAM_FILE_ACCESS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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

    @Test
    void replayingListenerReceivesFullStateValues() throws Exception {
        writeBlock(0, block(0, full(FIRST)));
        writeBlock(1, block(1, partial(FIRST, SECOND)));
        final Map<Long, Block> seen = new ConcurrentHashMap<>();
        final var unsubscribe = STREAM_FILE_ACCESS.subscribe(blocksDir, new StreamDataListener() {
            @Override
            public boolean replayExistingFiles() {
                return true;
            }

            @Override
            public boolean wantsFullStateValues() {
                return true;
            }

            @Override
            public void onNewBlock(@NonNull final Block block) {
                seen.put(block.items().getFirst().blockHeaderOrThrow().number(), block);
            }
        });
        try {
            awaitUntil(() -> seen.containsKey(1L));
        } finally {
            unsubscribe.run();
        }

        assertEquals(full(SECOND), accountIn(seen.get(1L)), "A replaying listener must not receive a sparse value");
    }

    @Test
    void replayingListenerReceivesExpansionErrors() throws Exception {
        // Without block 0 there is no baseline for the partial update in block 1
        writeBlock(1, block(1, partial(FIRST, SECOND)));
        final List<Block> delivered = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final var unsubscribe = STREAM_FILE_ACCESS.subscribe(blocksDir, new StreamDataListener() {
            @Override
            public boolean replayExistingFiles() {
                return true;
            }

            @Override
            public boolean wantsFullStateValues() {
                return true;
            }

            @Override
            public void onNewBlock(@NonNull final Block block) {
                delivered.add(block);
            }

            @Override
            public void onBlockError(@NonNull final Throwable error) {
                errors.add(error);
            }
        });
        try {
            awaitUntil(() -> !errors.isEmpty());
        } finally {
            unsubscribe.run();
        }

        assertInstanceOf(IllegalStateException.class, errors.getFirst());
        assertTrue(delivered.isEmpty(), "A sparse block must not be delivered as full");
    }

    private static void awaitUntil(@NonNull final BooleanSupplier condition) throws InterruptedException {
        final var deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            MILLISECONDS.sleep(50L);
        }
    }

    private void writeBlock(final long number) throws IOException {
        writeBlock(
                number,
                Block.newBuilder()
                        .items(BlockItem.newBuilder()
                                .blockHeader(
                                        BlockHeader.newBuilder().number(number).build())
                                .build())
                        .build());
    }

    private void writeBlock(final long number, @NonNull final Block block) throws IOException {
        final var name = String.format("%036d", number);
        try (final var out = new GZIPOutputStream(Files.newOutputStream(blocksDir.resolve(name + ".blk.gz")))) {
            out.write(Block.PROTOBUF.toBytes(block).toByteArray());
        }
        // The marker goes last, as a node writes it only once the block file is complete
        Files.createFile(blocksDir.resolve(name + ".mf"));
    }
}

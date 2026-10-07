// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.virtualmap.MerklePathUtils;
import com.swirlds.virtualmap.TaskPerNodeFullRehasher;
import com.swirlds.virtualmap.VirtualTestBase;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HashChunkCollectorTest extends VirtualTestBase {

    static Stream<Arguments> chunkHeightsAndLeafCounts() {
        // Chunk heights from 1 to the default MerkleDb height (6). Leaf counts cover single leaf
        // and two leaves trees, full trees, and first leaf ranks aligned and not aligned with
        // chunk ranks
        return IntStream.rangeClosed(1, 6).boxed().flatMap(height -> IntStream.of(
                        1, 2, 3, 4, 5, 7, 8, 9, 31, 32, 33, 64, 100, 1000, 5000)
                .mapToObj(leaves -> Arguments.of(height, leaves)));
    }

    @ParameterizedTest(name = "chunkHeight={0}, leaves={1}")
    @MethodSource("chunkHeightsAndLeafCounts")
    @DisplayName("Every chunk is complete when reported, reported once, and matches sequentially built chunks")
    void chunksMatchReference(final int chunkHeight, final int leafCount) {
        final long firstLeafPath = leafCount == 1 ? 1 : leafCount - 1L;
        final long lastLeafPath = leafCount == 1 ? 1 : 2L * leafCount - 2;
        final LongFunction<VirtualLeafBytes<?>> reader = path -> leaf(path, path, path * 7);

        // Expected chunks: all hashes that must be stored in chunks are set sequentially
        final byte[][] hashes = referenceHashes(firstLeafPath, lastLeafPath, reader);
        final Map<Long, VirtualHashChunk> expected = new HashMap<>();
        for (long path = 1; path <= lastLeafPath; path++) {
            if ((path >= firstLeafPath) || (MerklePathUtils.getRank(path) % chunkHeight == 0)) {
                expected.computeIfAbsent(
                                VirtualHashChunk.pathToChunkPath(path, chunkHeight),
                                p -> new VirtualHashChunk(p, chunkHeight))
                        .setHashBytesAtPath(path, hashes[(int) path]);
            }
        }

        final RecordingListener chunkListener = new RecordingListener();
        final HashChunkCollector collector =
                new HashChunkCollector(chunkHeight, firstLeafPath, lastLeafPath, chunkListener);
        final ForkJoinPool pool = new ForkJoinPool(8);
        try {
            new TaskPerNodeFullRehasher(pool).hash(firstLeafPath, lastLeafPath, reader, collector, 60_000);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, chunkListener.started.get(), "Hashing must be started once");
        assertEquals(1, chunkListener.completed.get(), "Hashing must be completed once");
        assertEquals(0, chunkListener.duplicates.get(), "Every chunk must be reported once");
        assertEquals(expected.keySet(), chunkListener.chunks.keySet(), "Chunk paths mismatch");
        for (final VirtualHashChunk expectedChunk : expected.values()) {
            final VirtualHashChunk actualChunk = chunkListener.chunks.get(expectedChunk.path());
            assertEquals(
                    expectedChunk.getSerializedSizeInBytes(),
                    actualChunk.getSerializedSizeInBytes(),
                    "Chunk size mismatch, chunk path = " + expectedChunk.path());
            for (int i = 0; i < expectedChunk.getChunkSize(); i++) {
                assertEquals(
                        expectedChunk.getHashAtIndex(i),
                        actualChunk.getHashAtIndex(i),
                        "Hash mismatch, chunk path = " + expectedChunk.path() + ", index = " + i);
            }
        }
    }

    @Test
    @DisplayName("Completion fails if some chunks are incomplete")
    void incompleteChunksDetected() {
        final RecordingListener chunkListener = new RecordingListener();
        final HashChunkCollector collector = new HashChunkCollector(3, 15, 30, chunkListener);
        final byte[][] hashes = hashes(15, 30);
        // A leaf hash, but its chunk root (path 7) is never hashed
        collector.onHashed(15, hashes[15]);
        // The root hash completes hashing, but the chunk at path 7 is still incomplete
        assertThrows(IllegalStateException.class, () -> collector.onHashed(0, hashes[0]));
        assertEquals(0, chunkListener.completed.get(), "Chunk listener must not be completed");
        assertTrue(chunkListener.chunks.isEmpty(), "No chunks must be reported");
    }

    @Test
    @DisplayName("Wrong chunk heights are rejected")
    void wrongChunkHeight() {
        assertThrows(IllegalArgumentException.class, () -> new HashChunkCollector(0, 7, 14, new RecordingListener()));
    }

    private byte[][] hashes(final long firstLeafPath, final long lastLeafPath) {
        return referenceHashes(firstLeafPath, lastLeafPath, path -> leaf(path, path, path));
    }

    /// Records chunks reported to the listener. Chunks are copied when reported, so any changes
    /// to the chunks after they are reported, i.e. when they are reported too early, are detected.
    private static final class RecordingListener implements VirtualHashListener {

        final Map<Long, VirtualHashChunk> chunks = new ConcurrentHashMap<>();
        final AtomicInteger duplicates = new AtomicInteger();
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();

        @Override
        public void onHashingStarted(final long firstLeafPath, final long lastLeafPath) {
            started.incrementAndGet();
        }

        @Override
        public void onHashChunkHashed(@NonNull final VirtualHashChunk chunk) {
            if (chunks.put(chunk.path(), chunk.copy()) != null) {
                duplicates.incrementAndGet();
            }
        }

        @Override
        public void onHashingCompleted() {
            completed.incrementAndGet();
        }
    }
}

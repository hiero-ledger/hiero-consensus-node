// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.DEFAULT_CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.config.VirtualMapConfig_;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.rehash.FullRehasher;
import com.swirlds.virtualmap.rehash.PathHashListener;
import com.swirlds.virtualmap.rehash.TaskPerNodeFullRehasher;
import com.swirlds.virtualmap.rehash.VirtualHasherFullRehasher;
import com.swirlds.virtualmap.test.fixtures.datasource.InMemoryBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.stream.Stream;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// Compares [VirtualHasherFullRehasher] and [TaskPerNodeFullRehasher] with each
/// other and with a simple reference implementation on trees of different sizes.
class FullRehashComparisonTest extends VirtualTestBase {

    // In-memory data source hash chunk height is 3, so these sizes cover first leaf ranks
    // aligned and not aligned with chunk ranks, as well as single leaf and two leaves trees
    private static final int[] LEAF_COUNTS = {1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 63, 64, 65, 100, 1000, 5000};

    private static final int[] PARALLELISMS = {1, 4};

    private static final Hash WRONG_HASH = wrongHash();

    static Stream<Arguments> leafCountsAndParallelism() {
        final List<Arguments> args = new ArrayList<>();
        for (final int leafCount : LEAF_COUNTS) {
            for (final int parallelism : PARALLELISMS) {
                args.add(Arguments.of(leafCount, parallelism));
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "leaves={0}, parallelism={1}")
    @MethodSource("leafCountsAndParallelism")
    @DisplayName("VirtualHasher based full rehash saves the same hashes as the reference")
    void virtualHasherFullRehash(final int leafCount, final int parallelism) throws IOException {
        verifyRehasher(leafCount, parallelism, VirtualHasherFullRehasher::new);
    }

    @ParameterizedTest(name = "leaves={0}, parallelism={1}")
    @MethodSource("leafCountsAndParallelism")
    @DisplayName("Task based full rehash saves the same hashes as the reference")
    void taskFullRehash(final int leafCount, final int parallelism) throws IOException {
        verifyRehasher(leafCount, parallelism, TaskPerNodeFullRehasher::new);
    }

    @ParameterizedTest(name = "leaves={0}, parallelism={1}")
    @MethodSource("leafCountsAndParallelism")
    @DisplayName("All full rehash implementations produce the same root hash")
    void allRootHashesAreEqual(final int leafCount, final int parallelism) throws IOException {
        final Hash[] expected = referenceHashes(leafCount);
        final ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            final VirtualMap map1 = createMap(leafCount, DEFAULT_CONFIGURATION);
            final VirtualMap map2 = createMap(leafCount, DEFAULT_CONFIGURATION);
            final VirtualMap map3 = createMap(leafCount, DEFAULT_CONFIGURATION);
            try {
                final Hash oldHash = new VirtualHasherFullRehasher(pool).rehash(map1);
                final Hash newHash = new TaskPerNodeFullRehasher(pool).rehash(map2);
                assertEquals(expected[0], oldHash, "VirtualHasher based root hash mismatch");
                assertEquals(expected[0], newHash, "Task based root hash mismatch");
            } finally {
                map1.release();
                map2.release();
                map3.release();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Empty map is not rehashed")
    void emptyMap() {
        final ForkJoinPool pool = new ForkJoinPool(2);
        try {
            final VirtualMap map1 = new VirtualMap(new InMemoryBuilder(), DEFAULT_CONFIGURATION);
            final VirtualMap map2 = new VirtualMap(new InMemoryBuilder(), DEFAULT_CONFIGURATION);
            try {
                assertNull(new VirtualHasherFullRehasher(pool).rehash(map1));
                assertNull(new TaskPerNodeFullRehasher(pool).rehash(map2));
            } finally {
                map1.release();
                map2.release();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Task based full rehash fails with TimeoutException when the configured timeout is exceeded")
    void taskFullRehashConfigTimeout() throws IOException {
        final Configuration configuration = ConfigurationBuilder.create()
                .withConfigDataType(VirtualMapConfig.class)
                .withValue(VirtualMapConfig_.FULL_REHASH_TIMEOUT_MS, "0")
                .build();
        final ForkJoinPool pool = new ForkJoinPool(1);
        try {
            final VirtualMap map = createMap(100_000, configuration);
            try {
                final RuntimeException e =
                        assertThrows(RuntimeException.class, () -> new TaskPerNodeFullRehasher(pool).rehash(map));
                assertInstanceOf(TimeoutException.class, e.getCause());
            } finally {
                map.release();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Task based full rehash times out and stops when leaf reads are stuck")
    void taskFullRehashTimeoutStopsHashing() throws InterruptedException {
        final ForkJoinPool pool = new ForkJoinPool(4);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicBoolean hashedAfterTimeout = new AtomicBoolean(false);
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        try {
            final LongFunction<VirtualLeafBytes<?>> blockingReader = path -> {
                try {
                    release.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return leaf(path, path, path);
            };
            final PathHashListener listener = new PathHashListener() {
                @Override
                public void onHashed(final long path, final byte[] hash) {
                    if (timedOut.get()) {
                        hashedAfterTimeout.set(true);
                    }
                }

                @Override
                public void onHashingCompleted() {
                    completed.set(true);
                }
            };
            final RuntimeException e = assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () -> assertThrows(RuntimeException.class, () -> new TaskPerNodeFullRehasher(pool)
                            .hash(999, 1998, blockingReader, listener, 100)));
            assertInstanceOf(TimeoutException.class, e.getCause());
            timedOut.set(true);
            release.countDown();
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            assertFalse(completed.get(), "onHashingCompleted() must not be called on timeout");
            assertFalse(hashedAfterTimeout.get(), "No nodes must be reported after timeout");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Task based full rehash fails fast if a leaf can't be read")
    void taskFullRehashLeafReadFailure() {
        final ForkJoinPool pool = new ForkJoinPool(4);
        try {
            final UncheckedIOException failure = new UncheckedIOException(new IOException("test"));
            final LongFunction<VirtualLeafBytes<?>> failingReader = path -> {
                if (path == 1500) {
                    throw failure;
                }
                return leaf(path, path, path);
            };
            final RuntimeException e = assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () -> assertThrows(RuntimeException.class, () -> new TaskPerNodeFullRehasher(pool)
                            .hash(999, 1998, failingReader, null, 60_000)));
            assertSame(failure, e);

            final LongFunction<VirtualLeafBytes<?>> nullReader = path -> path == 1500 ? null : leaf(path, path, path);
            assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () -> assertThrows(IllegalStateException.class, () -> new TaskPerNodeFullRehasher(pool)
                            .hash(999, 1998, nullReader, null, 60_000)));
        } finally {
            pool.shutdownNow();
        }
    }

    private void verifyRehasher(
            final int leafCount, final int parallelism, final Function<ForkJoinPool, FullRehasher> rehasherFactory)
            throws IOException {
        final Hash[] expected = referenceHashes(leafCount);
        final ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            final VirtualMap map = createMap(leafCount, DEFAULT_CONFIGURATION);
            try {
                final Hash rootHash = rehasherFactory.apply(pool).rehash(map);
                assertEquals(expected[0], rootHash, "Root hash mismatch");
                // Hashes are read from the data source, as the map cache is empty
                for (long path = 1; path < expected.length; path++) {
                    assertEquals(expected[(int) path], map.getRecords().findHash(path), "Hash mismatch, path=" + path);
                }
                assertEquals(expected[0], map.getRecords().rootHash(), "Stored root hash mismatch");
            } finally {
                map.release();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /// Creates a map with the given number of leaves in its data source. Hashes of all leaves
    /// are stored in the data source, but they are all wrong.
    private VirtualMap createMap(final int leafCount, final Configuration configuration) throws IOException {
        final VirtualMap map = new VirtualMap(new InMemoryBuilder(), configuration);
        final long firstLeafPath = firstLeafPath(leafCount);
        final long lastLeafPath = lastLeafPath(leafCount);
        map.getMetadata().setPaths(firstLeafPath, lastLeafPath);

        final VirtualDataSource dataSource = map.getDataSource();
        final int chunkHeight = dataSource.getHashChunkHeight();
        final Map<Long, VirtualHashChunk> chunks = new HashMap<>();
        final List<VirtualLeafBytes> leaves = new ArrayList<>();
        for (long path = firstLeafPath; path <= lastLeafPath; path++) {
            leaves.add(leaf(path, path, path * 31));
            chunks.computeIfAbsent(
                            VirtualHashChunk.pathToChunkPath(path, chunkHeight),
                            p -> new VirtualHashChunk(p, chunkHeight))
                    .setHashAtPath(path, WRONG_HASH);
        }
        dataSource.saveRecords(
                firstLeafPath, lastLeafPath, chunks.values().stream(), leaves.stream(), Stream.empty(), false);
        return map;
    }

    /// Calculates all node hashes in a tree with the given number of leaves, indexed by path.
    private Hash[] referenceHashes(final int leafCount) {
        final long firstLeafPath = firstLeafPath(leafCount);
        final long lastLeafPath = lastLeafPath(leafCount);
        final MerkleHasher hasher = new MerkleHasher();
        final Hash[] hashes = new Hash[(int) lastLeafPath + 1];
        for (int path = (int) lastLeafPath; path >= 0; path--) {
            if (path >= firstLeafPath) {
                hashes[path] = hasher.leafNodeHash(leaf(path, path, path * 31L));
            } else {
                final int right = 2 * path + 2;
                hashes[path] =
                        hasher.internalNodeHash(hashes[2 * path + 1], right <= lastLeafPath ? hashes[right] : null);
            }
        }
        return hashes;
    }

    private static long firstLeafPath(final int leafCount) {
        return leafCount == 1 ? 1 : leafCount - 1;
    }

    private static long lastLeafPath(final int leafCount) {
        return leafCount == 1 ? 1 : 2L * leafCount - 2;
    }

    private static LongFunction<VirtualLeafBytes<?>> leafReader(final VirtualDataSource dataSource) {
        return path -> {
            try {
                return dataSource.loadLeafRecord(path);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static Hash wrongHash() {
        final byte[] bytes = new byte[Cryptography.DEFAULT_DIGEST_TYPE.digestLength()];
        bytes[0] = 1;
        return new Hash(bytes, Cryptography.DEFAULT_DIGEST_TYPE);
    }
}

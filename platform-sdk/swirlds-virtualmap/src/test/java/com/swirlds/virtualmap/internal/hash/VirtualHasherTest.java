// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.VIRTUAL_MAP_CONFIG;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import com.swirlds.virtualmap.test.fixtures.VirtualTestBase;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the {@link VirtualHasher}.
 */
@SuppressWarnings("rawtypes")
class VirtualHasherTest extends VirtualHasherTestBase {

    /**
     * If chunk preloader is null, an NPE will be raised.
     */
    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Null preloader produces NPE")
    void nullPreloaderProducesNPE() {
        final VirtualHasher hasher = new VirtualHasher();
        final List<VirtualLeafBytes> leaves = new ArrayList<>();
        assertThrows(
                NullPointerException.class,
                () -> hasher.hash(CHUNK_HEIGHT, null, leaves.iterator(), 1, 2, null, VIRTUAL_MAP_CONFIG),
                "Call should have produced an NPE");
    }

    /**
     * If the stream is null, an NPE will be raised.
     */
    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Null stream produces NPE")
    void nullStreamProducesNPE() {
        final TestDataSource ds = new TestDataSource(1, 2, CHUNK_HEIGHT);
        final VirtualHasher hasher = new VirtualHasher();
        assertThrows(
                NullPointerException.class,
                () -> hasher.hash(CHUNK_HEIGHT, ds::loadHashChunk, null, 1, 2, null, VIRTUAL_MAP_CONFIG),
                "Call should have produced an NPE");
    }

    /**
     * If the stream is empty, a null hash is returned.
     */
    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Empty stream results in a null hash")
    @SuppressWarnings({"MismatchedQueryAndUpdateOfCollection", "RedundantOperationOnEmptyContainer"})
    void emptyStreamProducesNull() {
        final TestDataSource ds = new TestDataSource(1, 2, CHUNK_HEIGHT);
        final VirtualHasher hasher = new VirtualHasher();
        final List<VirtualLeafBytes> leaves = new ArrayList<>();
        assertNull(
                hasher.hash(CHUNK_HEIGHT, ds::loadHashChunk, leaves.iterator(), 1, 2, null, VIRTUAL_MAP_CONFIG),
                "Call should have returned a null hash");
    }

    /**
     * If either the firstLeafPath or lastLeafPath is &lt; 1, then a null hash is returned.
     */
    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Invalid leaf paths")
    void invalidLeafPaths() {
        final TestDataSource ds = new TestDataSource(Path.INVALID_PATH, Path.INVALID_PATH, CHUNK_HEIGHT);
        final VirtualHasher hasher = new VirtualHasher();
        final List<VirtualLeafBytes> emptyLeaves = new ArrayList<>();
        // Empty dirty leaves stream -> null hash
        assertNull(
                hasher.hash(
                        CHUNK_HEIGHT,
                        ds::loadHashChunk,
                        emptyLeaves.iterator(),
                        Path.INVALID_PATH,
                        Path.INVALID_PATH,
                        null,
                        VIRTUAL_MAP_CONFIG),
                "Call should have produced null");
        assertNull(
                hasher.hash(
                        CHUNK_HEIGHT,
                        ds::loadHashChunk,
                        emptyLeaves.iterator(),
                        Path.INVALID_PATH,
                        2,
                        null,
                        VIRTUAL_MAP_CONFIG),
                "Call should have produced null");
        assertNull(
                hasher.hash(
                        CHUNK_HEIGHT,
                        ds::loadHashChunk,
                        emptyLeaves.iterator(),
                        1,
                        Path.INVALID_PATH,
                        null,
                        VIRTUAL_MAP_CONFIG),
                "Call should have produced null");
        assertNull(
                hasher.hash(CHUNK_HEIGHT, ds::loadHashChunk, emptyLeaves.iterator(), 0, 2, null, VIRTUAL_MAP_CONFIG),
                "Call should have produced null");
        assertNull(
                hasher.hash(CHUNK_HEIGHT, ds::loadHashChunk, emptyLeaves.iterator(), 1, 0, null, VIRTUAL_MAP_CONFIG),
                "Call should have produced null");
        // Non-empty dirty leaves stream + empty leaf path range -> IllegalStateException
        final List<VirtualLeafBytes> nonEmptyLeaves = new ArrayList<>();
        nonEmptyLeaves.add(appleLeaf(VirtualTestBase.A_PATH));
        assertThrows(
                IllegalArgumentException.class,
                () -> hasher.hash(
                        CHUNK_HEIGHT,
                        ds::loadHashChunk,
                        nonEmptyLeaves.iterator(),
                        Path.INVALID_PATH,
                        Path.INVALID_PATH,
                        null,
                        VIRTUAL_MAP_CONFIG),
                "Non-null leaves iterator + invalid paths should throw an exception");
        assertThrows(
                IllegalArgumentException.class,
                () -> hasher.hash(
                        CHUNK_HEIGHT, ds::loadHashChunk, nonEmptyLeaves.iterator(), 0, 2, null, VIRTUAL_MAP_CONFIG),
                "Non-null leaves iterator + invalid paths should throw an exception");
        assertThrows(
                IllegalArgumentException.class,
                () -> hasher.hash(
                        CHUNK_HEIGHT, ds::loadHashChunk, nonEmptyLeaves.iterator(), 1, 0, null, VIRTUAL_MAP_CONFIG),
                "Non-null leaves iterator + invalid paths should throw an exception");
    }

    /**
     * Test a large variety of different tree shapes and sizes and a variety of different dirty leaves
     * in those trees.
     *
     * @param firstLeafPath
     * 		The first leaf path.
     * @param lastLeafPath
     * 		The last leaf path.
     * @param dirtyPaths
     * 		The leaf paths that are dirty in this tree.
     */
    @ParameterizedTest
    @MethodSource("hashingPermutationsParams")
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Test various dirty nodes in a tree")
    void hashingPermutations(final long firstLeafPath, final long lastLeafPath, final List<Long> dirtyPaths)
            throws Exception {
        final TestDataSource ds = new TestDataSource(firstLeafPath, lastLeafPath, CHUNK_HEIGHT);
        final HashingListener listener = new HashingListener();
        final VirtualHasher hasher = new VirtualHasher();
        final Hash expected = hashTree(ds);
        final List<VirtualLeafBytes> leaves = invalidateNodes(ds, dirtyPaths.stream());
        final Hash rootHash = hasher.hash(
                CHUNK_HEIGHT,
                ds::loadHashChunk,
                leaves.iterator(),
                firstLeafPath,
                lastLeafPath,
                listener,
                VIRTUAL_MAP_CONFIG);
        assertEquals(expected, rootHash, "Hash value does not match expected");

        // Make sure the saver saw each dirty node exactly once.
        final Set<Long> savedChunks =
                listener.unsortedChunks().stream().map(VirtualHashChunk::path).collect(Collectors.toSet());
        final Set<VirtualHashChunk> seenChunks = new HashSet<>();
        for (final Long dirtyPath : dirtyPaths) {
            final long chunkPath = VirtualHashChunk.pathToChunkPath(dirtyPath, CHUNK_HEIGHT);
            VirtualHashChunk chunk = ds.loadHashChunk(chunkPath);
            assertNotNull(chunk);
            while (chunk.path() >= 0) {
                assertTrue(savedChunks.contains(chunk.path()), "Expected true");
                seenChunks.add(chunk);
                if (chunk.path() == 0) {
                    break;
                }
                chunk = ds.loadHashChunk(Path.getGrandParentPath(chunk.path(), CHUNK_HEIGHT));
                assertNotNull(chunk);
            }
        }
        assertEquals(savedChunks.size(), seenChunks.size(), "Expected equals");
        assertCallsAreBalanced(listener);

        final Hash hashItAgain = hasher.hash(
                CHUNK_HEIGHT,
                ds::loadHashChunk,
                leaves.iterator(),
                firstLeafPath,
                lastLeafPath,
                listener,
                VIRTUAL_MAP_CONFIG);
        assertEquals(expected, hashItAgain, "Hash value does not match expected");

        for (int i = 0; i < leaves.size(); i++) {
            final long leafPath = leaves.get(i).path();
            final long siblingPath = Path.getSiblingPath(leafPath);
            if ((siblingPath >= firstLeafPath) && (siblingPath <= lastLeafPath)) {
                final VirtualLeafBytes sibling = ds.getLeaf(siblingPath);
                leaves.set(i, sibling);
            }
        }
        leaves.sort(Comparator.comparing(VirtualLeafBytes::path));
        final Hash hashItOnceMore = hasher.hash(
                CHUNK_HEIGHT,
                ds::loadHashChunk,
                leaves.iterator(),
                firstLeafPath,
                lastLeafPath,
                listener,
                VIRTUAL_MAP_CONFIG);
        assertEquals(expected, hashItOnceMore, "Hash value does not match expected");
    }

    /**
     * Generate permutations of trees for testing hashing.
     *
     * @return
     * 		The stream of args.
     */
    private static Stream<Arguments> hashingPermutationsParams() {
        // Generate every permutation for the number of leaves 1-4. 1 leaf, 2 leaves, 3 leaves, 4 leaves,
        // and every permutation of those with leaves dirty and clean.
        final List<Arguments> args = new ArrayList<>();
        // 1 leaf
        args.add(Arguments.of(1L, 1L, List.of(1L)));
        // 2 leaves
        args.add(Arguments.of(1L, 2L, List.of(1L)));
        args.add(Arguments.of(1L, 2L, List.of(2L)));
        args.add(Arguments.of(1L, 2L, List.of(1L, 2L)));
        // 3 leaves
        args.add(Arguments.of(2L, 4L, List.of(2L)));
        args.add(Arguments.of(2L, 4L, List.of(3L)));
        args.add(Arguments.of(2L, 4L, List.of(2L, 3L)));
        args.add(Arguments.of(2L, 4L, List.of(4L)));
        args.add(Arguments.of(2L, 4L, List.of(2L, 4L)));
        args.add(Arguments.of(2L, 4L, List.of(3L, 4L)));
        args.add(Arguments.of(2L, 4L, List.of(2L, 3L, 4L)));
        // 4 leaves
        args.add(Arguments.of(3L, 6L, List.of(3L)));
        args.add(Arguments.of(3L, 6L, List.of(4L)));
        args.add(Arguments.of(3L, 6L, List.of(3L, 4L)));
        args.add(Arguments.of(3L, 6L, List.of(5L)));
        args.add(Arguments.of(3L, 6L, List.of(3L, 5L)));
        args.add(Arguments.of(3L, 6L, List.of(4L, 5L)));
        args.add(Arguments.of(3L, 6L, List.of(3L, 4L, 5L)));
        args.add(Arguments.of(3L, 6L, List.of(6L)));
        args.add(Arguments.of(3L, 6L, List.of(3L, 6L)));
        args.add(Arguments.of(3L, 6L, List.of(4L, 6L)));
        args.add(Arguments.of(3L, 6L, List.of(3L, 4L, 6L)));
        args.add(Arguments.of(3L, 6L, List.of(5L, 6L)));
        args.add(Arguments.of(3L, 6L, List.of(3L, 5L, 6L)));
        args.add(Arguments.of(3L, 6L, List.of(4L, 5L, 6L)));
        args.add(Arguments.of(3L, 6L, List.of(3L, 4L, 5L, 6L)));

        // Generate every permutation of which leaves are clean and dirty. This method assumes
        // that the set of leaves is path 6-12 only.
        //
        // Imagine a bitset where each bit position represents whether a given leaf
        // should be clean or dirty, and we generate an Arguments instance for each
        // permutation of that bitset.
        for (int i = 1; i < Math.pow(2, 7); i++) {
            final List<Long> dirtyIndexes = new ArrayList<>();
            for (long j = 6, bits = i; j < 13; j++, bits >>= 1) {
                if ((bits & 0x1) == 1) {
                    dirtyIndexes.add(j);
                }
            }
            args.add(Arguments.of(6L, 12L, dirtyIndexes));
        }

        // Generate a useful set of scenarios for the medium tree case, where the "medium" tree is still
        // quite small (32 or 53 leaves), but still large enough that we cannot test every permutation.
        // We will test edge cases (1 dirty, all 53 permutations; all dirty; 1 dirty at start and end; etc.).
        // We will also test a few thousand pseudo-random varieties for a little fuzz testing.
        //
        // Setup tests for a 32 leaf tree -- such that all leaves form a single rank in the tree.
        // First test all permutations of 1 dirty leaf
        long firstLeafPath = 31;
        long lastLeafPath = 62;
        for (long i = firstLeafPath; i <= lastLeafPath; i++) {
            args.add(Arguments.of(firstLeafPath, lastLeafPath, List.of(i)));
        }
        // Then test the first and last both being dirty
        args.add(Arguments.of(firstLeafPath, lastLeafPath, List.of(firstLeafPath, lastLeafPath)));
        // Then test some random assortment
        args.addAll(randomDirtyLeaves(1000, firstLeafPath, lastLeafPath));

        // Setup tests for the 32 leaf tree -- such that leaves spread across two ranks,
        // with almost all leaves on the lower rank and only 2 leaves on the bottom rank.
        // All 32 permutations of a single leaf being dirty
        firstLeafPath = 32;
        lastLeafPath = 64;
        for (long i = firstLeafPath; i <= lastLeafPath; i++) {
            args.add(Arguments.of(firstLeafPath, lastLeafPath, List.of(i)));
        }
        // Then test the first and last both being dirty
        args.add(Arguments.of(firstLeafPath, lastLeafPath, List.of(firstLeafPath, lastLeafPath)));
        // Then test some random assortment
        args.addAll(randomDirtyLeaves(2000, firstLeafPath, lastLeafPath));

        // Setup tests for the 53 leaf tree -- such that leaves spread across two ranks.
        // All 53 permutations of a single leaf being dirty
        firstLeafPath = 52;
        lastLeafPath = 104;
        for (long i = firstLeafPath; i <= lastLeafPath; i++) {
            args.add(Arguments.of(firstLeafPath, lastLeafPath, List.of(i)));
        }
        // Then test the first and last both being dirty
        args.add(Arguments.of(firstLeafPath, lastLeafPath, List.of(firstLeafPath, lastLeafPath)));
        // Then test some random assortment
        args.addAll(randomDirtyLeaves(1000, firstLeafPath, lastLeafPath));

        // Generate a useful sample of tree configurations between 4 and 6 ranks deep, and a random sampling
        // of dirty nodes within those trees.
        for (firstLeafPath = 7L, lastLeafPath = 14L; firstLeafPath < 31L; firstLeafPath++, lastLeafPath += 2) {
            for (long i = firstLeafPath; i <= lastLeafPath; i++) {
                args.add(Arguments.of(firstLeafPath, lastLeafPath, List.of(i)));
            }
            args.add(Arguments.of(firstLeafPath, lastLeafPath, List.of(firstLeafPath, lastLeafPath)));
            args.addAll(randomDirtyLeaves(100, firstLeafPath, lastLeafPath));
        }

        return args.stream();
    }

    /**
     * Given our "canonical" 53-leaf dirty list (as used during the design phase when diagramming),
     * run the test repeatedly to make sure it always works. Early on I found some threading bugs
     * this way, and I want to make sure that given the same input we get the same result every time.
     * <p>
     * If this test fails, then there is a legitimate issue. If it passes, it only means that we
     * *may* not have a problem. So if this is intermittent, then we have a problem.
     */
    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6})
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Test the same situation over and over and over")
    void repeatedTest(final int chunkHeight) throws Exception {
        final long firstLeafPath = 52L;
        final long lastLeafPath = firstLeafPath * 2;
        final TestDataSource ds = new TestDataSource(firstLeafPath, lastLeafPath, chunkHeight);
        final VirtualHasher hasher = new VirtualHasher();
        final Hash expected = hashTree(ds);
        final List<Long> dirtyLeafPaths = List.of(
                53L, 56L, 59L, 63L, 66L, 72L, 76L, 77L, 80L, 81L, 82L, 83L, 85L, 87L, 88L, 94L, 96L, 100L, 104L);

        // Iterate a few times, doing the same thing. If there are race conditions among the hashing threads,
        // this will *likely* find it.
        for (int i = 0; i < 500; i++) {
            final List<VirtualLeafBytes> leaves = invalidateNodes(ds, dirtyLeafPaths.stream());
            final Hash rootHash = hasher.hash(
                    chunkHeight,
                    ds::loadHashChunk,
                    leaves.iterator(),
                    firstLeafPath,
                    lastLeafPath,
                    null,
                    VIRTUAL_MAP_CONFIG);
            assertEquals(expected, rootHash, "Expected equals");
        }

        // Now rehash with a hashing listener, which updates hash chunks in the data source
        final VirtualHashListener listener = new VirtualHashListener() {
            @Override
            public synchronized void onHashChunkHashed(final VirtualHashChunk chunk) {
                ds.updateHashChunk(chunk);
            }
        };
        for (int i = 0; i < 500; i++) {
            final List<VirtualLeafBytes> leaves = invalidateNodes(ds, dirtyLeafPaths.stream());
            final Hash rootHash = hasher.hash(
                    chunkHeight,
                    ds::loadHashChunk,
                    leaves.iterator(),
                    firstLeafPath,
                    lastLeafPath,
                    listener,
                    VIRTUAL_MAP_CONFIG);
            assertEquals(expected, rootHash, "Expected equals");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6})
    void testAllDirtyLeaves(final int chunkHeight) throws Exception {
        final long firstLeafPath = 801;
        final long lastLeafPath = firstLeafPath * 2;
        final TestDataSource ds = new TestDataSource(firstLeafPath, lastLeafPath, chunkHeight);
        final VirtualHasher hasher = new VirtualHasher();
        final Hash expected = hashTree(ds);
        final List<Long> dirtyLeafPaths =
                LongStream.range(firstLeafPath, lastLeafPath + 1).boxed().toList();

        final VirtualHashListener listener = new VirtualHashListener() {
            @Override
            public synchronized void onHashChunkHashed(final VirtualHashChunk chunk) {
                ds.updateHashChunk(chunk);
            }
        };
        final List<VirtualLeafBytes> leaves = invalidateNodes(ds, dirtyLeafPaths.stream());
        final Hash rootHash = hasher.hash(
                chunkHeight,
                ds::loadHashChunk,
                leaves.iterator(),
                firstLeafPath,
                lastLeafPath,
                listener,
                VIRTUAL_MAP_CONFIG);
        assertEquals(expected, rootHash, "Expected equals");

        final List<VirtualLeafBytes> oneDirtyLeaf = List.of(ds.getLeaf((firstLeafPath + lastLeafPath) / 2));
        final Hash hashItAgain = hasher.hash(
                chunkHeight,
                ds::loadHashChunk,
                oneDirtyLeaf.iterator(),
                firstLeafPath,
                lastLeafPath,
                null,
                VIRTUAL_MAP_CONFIG);
        assertEquals(expected, hashItAgain, "Expected equals");
    }

    /**
     * Test that the various callbacks on the listener are called the expected number of times.
     * For this test, I'm using our "canonical" example. I wish I could post the image directly
     * in these javadocs. Instead, look for the images in the design docs.
     */
    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Verify methods on the listener are called at the right frequency")
    void listenerCallCounts() throws Exception {
        // This test relies on hash chunk height to be 5
        final int hashChunkHeight = 5;
        final Configuration config = ConfigurationBuilder.create()
                .withConfigDataType(VirtualMapConfig.class)
                .withValue("virtualMap.hashChunkHeight", "" + hashChunkHeight)
                .build();
        final VirtualMapConfig virtualMapConfig = config.getConfigData(VirtualMapConfig.class);

        final long firstLeafPath = 52L;
        final long lastLeafPath = firstLeafPath * 2;
        final TestDataSource ds = new TestDataSource(firstLeafPath, lastLeafPath, hashChunkHeight);
        final HashingListener listener = new HashingListener();
        final VirtualHasher hasher = new VirtualHasher();
        hashTree(ds);
        final List<Long> dirtyLeafPaths = List.of(
                53L, 56L, 59L, 63L, 66L, 72L, 76L, 77L, 80L, 81L, 82L, 83L, 85L, 87L, 88L, 94L, 96L, 100L, 104L);

        final List<VirtualLeafBytes> leaves = invalidateNodes(ds, dirtyLeafPaths.stream());
        hasher.hash(
                hashChunkHeight,
                ds::loadHashChunk,
                leaves.iterator(),
                firstLeafPath,
                lastLeafPath,
                listener,
                virtualMapConfig);

        // Check the different callbacks were called the correct number of times
        assertEquals(1, listener.onHashingStartedCallCount, "Unexpected count");
        // The number of onHashChunkHashed() calls varies with implementation, the check below may
        // need to be updated in the future. At the moment the number is 15: 14 chunks (63L to 104L
        // paths above, 81/82 and 87/88 sharing the same chunk) plus the root chunk
        assertEquals(15, listener.onHashChunkHashedCount, "Unexpected count");
        assertEquals(1, listener.onHashingCompletedCallCount, "Unexpected count");

        // Validate the calls were all balanced
        assertCallsAreBalanced(listener);
    }

    /**
     * We found a bug while doing large reconnect tests where the VirtualHasher was asking for
     * hash records before they had been written (#4251). In reality, the hasher should have
     * never been asking for those records in the first place (this was a needless performance
     * problem). This test covers that specific case.
     */
    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Verify the hasher does not ask for internal records it will recreate")
    void hasherDoesNotAskForInternalsItWillRecreate() {
        final HashingListener listener = new HashingListener();
        final VirtualHasher hasher = new VirtualHasher();

        // We will simulate growing the tree from 53 leaves to 106 leaves (doubling the size) and providing
        // all new leaves. This will guarantee that some internal nodes live at the paths that leaves used
        // to. We'll then hash in several batches. If there are no exceptions, then we're OK.
        final long firstLeafPath = 105L;
        final long lastLeafPath = 210L;
        final Iterator<VirtualLeafBytes> dirtyLeaves = LongStream.range(firstLeafPath, lastLeafPath + 1)
                .mapToObj(path -> new VirtualLeafBytes(
                        path, TestKey.longToKey(path), new TestValue(path), TestValueCodec.INSTANCE))
                .iterator();

        final LongFunction<VirtualHashChunk> hashChunkReader = path -> {
            throw new AssertionError("Hashes not be queried");
        };

        assertDoesNotThrow(
                () -> {
                    hasher.hash(
                            CHUNK_HEIGHT,
                            hashChunkReader,
                            dirtyLeaves,
                            firstLeafPath,
                            lastLeafPath,
                            listener,
                            VIRTUAL_MAP_CONFIG);
                },
                "Hashing should not throw an exception");
    }

    private static void assertCallsAreBalanced(final HashingListener listener) {
        // Check the call order was correct. Something like:
        final Deque<Character> tokenStack = new ArrayDeque<>();
        final String tokens = listener.callHistory.toString();
        for (int i = 0; i < tokens.length(); i++) {
            final char token = tokens.charAt(i);
            switch (token) {
                case HashingListener.ON_HASHING_STARTED_SYMBOL:
                    tokenStack.push(token);
                    break;
                case HashingListener.ON_CHUNK_HASHED_SYMBOL:
                    break;
                case HashingListener.ON_HASHING_COMPLETED_SYMBOL:
                    assertEquals(
                            HashingListener.ON_HASHING_STARTED_SYMBOL,
                            tokenStack.pop(),
                            "Unbalanced calls: expected onHashingCompleted to be called");
                    break;
                default:
                    fail("Unexpected token " + token);
            }
        }
    }

    /**
     * An implementation of {@link VirtualHashListener} used during testing. Specifically, we need to capture
     * the set of internal and leaf records that are sent to the listener during hashing to validate
     * that hashing visited everything we expect.
     */
    private static final class HashingListener implements VirtualHashListener {

        static final char ON_HASHING_STARTED_SYMBOL = '{';
        static final char ON_HASHING_COMPLETED_SYMBOL = '}';
        static final char ON_CHUNK_HASHED_SYMBOL = 'I';

        private int onHashingStartedCallCount = 0;
        private int onHashChunkHashedCount = 0;
        private int onHashingCompletedCallCount = 0;
        private final StringBuilder callHistory = new StringBuilder();

        private final List<VirtualHashChunk> chunks = new ArrayList<>();

        synchronized List<VirtualHashChunk> unsortedChunks() {
            return new ArrayList<>(chunks);
        }

        @Override
        public synchronized void onHashingStarted(final long firstLeafPath, final long lastLeafPath) {
            onHashingStartedCallCount++;
            callHistory.append(ON_HASHING_STARTED_SYMBOL);
            chunks.clear();
        }

        @Override
        public synchronized void onHashChunkHashed(final VirtualHashChunk chunk) {
            onHashChunkHashedCount++;
            callHistory.append(ON_CHUNK_HASHED_SYMBOL);
            chunks.add(chunk);
        }

        @Override
        public synchronized void onHashingCompleted() {
            onHashingCompletedCallCount++;
            callHistory.append(ON_HASHING_COMPLETED_SYMBOL);
        }
    }
}

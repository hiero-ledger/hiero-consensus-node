// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.cache;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyDoesNotThrow;
import static com.swirlds.virtualmap.internal.cache.VirtualNodeCache.DELETED_LEAF_RECORD;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.*;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.function.CheckedFunction;
import com.swirlds.base.state.MutabilityException;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import com.swirlds.virtualmap.test.fixtures.VirtualTestBase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.base.crypto.Hash;
import org.hiero.base.exceptions.ReferenceCountException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

class VirtualNodeCacheTest extends VirtualTestBase {

    private static final int HASH_CHUNK_HEIGHT = 2;

    private static final Hash NO_HASH = new Hash(new byte[Cryptography.DEFAULT_DIGEST_TYPE.digestLength()]);

    private static final long BOGUS_KEY_ID = -7000;

    private VirtualNodeCache cache;

    private TrackingHashChunkLoader chunkLoader;

    @BeforeEach
    public void setup() {
        final VirtualMapConfig virtualMapConfig = CONFIGURATION.getConfigData(VirtualMapConfig.class);
        // Hash chunk loader always returns null, since this test doesn't flush any data to data source
        chunkLoader = new TrackingHashChunkLoader();
        cache = new VirtualNodeCache(virtualMapConfig, HASH_CHUNK_HEIGHT, chunkLoader);
    }

    // NOTE: If nextRound automatically causes hashing, some tests in VirtualNodeCacheTest will fail or be invalid.
    protected void nextRound() {
        cache = cache.copy();
    }

    // ----------------------------------------------------------------------
    // Fast copy and life-cycle tests, including releasing and merging.
    // ----------------------------------------------------------------------

    /**
     * This is perhaps the most crucial of all the tests here. We are going to build our
     * test tree, step by step. Initially, there are no nodes. Then we add A, B, C, etc.
     * We do this in a way that mimics what happens when {@link VirtualMap}
     * makes the calls. This should be a faithful reproduction of what we will actually see.
     * <p>
     * To complicate matters, once we build the tree, we start to tear it down again. We
     * also do this in order to try to replicate what will actually happen. We make this
     * even more rich by adding and removing nodes in different orders, so they end up
     * in different positions.
     * <p>
     * We create new caches along the way. We don't drop any of them until the end.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache")})
    @DisplayName("Build a tree step by step")
    void buildATree() {
        // ROUND 0: Add A, B, and C. First add A, then B, then C. When we add C, we have to move A.
        // This will all happen in a single round. Then create the Root and Left internals after
        // creating the next round.

        // Tree structure
        //                           root (0)
        //          int (1)                             banana (2)
        // apple (3)       cherry (4)

        // Add apple at path 1
        final VirtualNodeCache cache0 = cache;
        VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(1);
        cache0.putLeaf(appleLeaf0);
        validateLeaves(cache0, 1, Collections.singletonList(appleLeaf0));

        // Add banana at path 2
        final VirtualLeafBytes<TestValue> bananaLeaf0 = bananaLeaf(2);
        cache0.putLeaf(bananaLeaf0);
        validateLeaves(cache0, 1, asList(appleLeaf0, bananaLeaf0));

        // Move apple to path 3
        appleLeaf0 = appleLeaf0.withPath(3);
        cache0.clearLeafPath(1);
        cache0.putLeaf(appleLeaf0);
        assertEquals(DELETED_LEAF_RECORD, cache0.lookupLeafByPath(1), "leaf should have been deleted");
        validateLeaves(cache0, 2, asList(bananaLeaf0, appleLeaf0));

        // Add cherry to path 4
        final VirtualLeafBytes<TestValue> cherryLeaf0 = cherryLeaf(4);
        cache0.putLeaf(cherryLeaf0);
        validateLeaves(cache0, 2, asList(bananaLeaf0, appleLeaf0, cherryLeaf0));

        // End the round and create the next round
        nextRound();
        cache0.prepareForHashing();
        validateDirtyLeaves(asList(bananaLeaf0, appleLeaf0, cherryLeaf0), cache0.dirtyLeavesForHash(2, 4));

        final VirtualHashChunk path0Chunk0 = cache0.preloadHashChunk(0);
        // Hash at path 2: banana0
        final Hash bananaLeaf0intHash = hash(bananaLeaf0);
        path0Chunk0.setHashAtPath(bananaLeaf0.path(), bananaLeaf0intHash);
        // Hash at path 3: apple0
        final Hash appleLeaf0intHash = hash(appleLeaf0);
        path0Chunk0.setHashAtPath(appleLeaf0.path(), appleLeaf0intHash);
        // Hash at path 4: cherry0
        final Hash cherryLeaf0intHash = hash(cherryLeaf0);
        path0Chunk0.setHashAtPath(cherryLeaf0.path(), cherryLeaf0intHash);
        cache0.putHashChunk(path0Chunk0);

        cache0.seal();

        validateLeaves(cache0, asList(bananaLeaf0, appleLeaf0, cherryLeaf0));

        assertTrue(chunkLoader.getChunkIds().contains(0L)); // chunk path==0
        assertFalse(chunkLoader.getChunkIds().contains(1L)); // chunk path==3
        chunkLoader.reset();

        final Set<VirtualHashChunk> dirtyHashChunks0 =
                cache0.dirtyHashesForFlush(4).collect(Collectors.toSet());
        validateDirtyHashChunkPaths(Set.of(0L), dirtyHashChunks0);
        validateDirtyHash(appleLeaf0.path(), appleLeaf0intHash, dirtyHashChunks0);
        validateDirtyHash(cherryLeaf0.path(), cherryLeaf0intHash, dirtyHashChunks0);
        validateDirtyHash(bananaLeaf0.path(), bananaLeaf0intHash, dirtyHashChunks0);
        validateNoDirtyHash(6, dirtyHashChunks0);

        // ROUND 1: Add D and E

        // Tree structure
        //                                                    root (0)
        //                            int (1)                                        int (2)
        //           int (3)                      cherry (4)              banana (5)         date (6)
        // apple (7)         eggplant (8)

        final VirtualNodeCache cache1 = cache;

        // Move B to index 5
        final VirtualLeafBytes<TestValue> bananaLeaf1 = bananaLeaf(5);
        cache1.clearLeafPath(2);
        cache1.putLeaf(bananaLeaf1);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache1.lookupLeafByPath(2),
                "value that was looked up should match original value");
        validateLeaves(cache1, 3, asList(appleLeaf0, cherryLeaf0, bananaLeaf1));

        // Add D at index 6
        final VirtualLeafBytes<TestValue> dateLeaf1 = dateLeaf(6);
        cache1.putLeaf(dateLeaf1);
        validateLeaves(cache1, 3, asList(appleLeaf0, cherryLeaf0, bananaLeaf1, dateLeaf1));

        // Move A to index 7
        final VirtualLeafBytes<TestValue> appleLeaf1 = appleLeaf(7);
        cache1.clearLeafPath(3);
        cache1.putLeaf(appleLeaf1);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache1.lookupLeafByPath(3),
                "value that was looked up should match original value");
        validateLeaves(cache1, 4, asList(cherryLeaf0, bananaLeaf1, dateLeaf1, appleLeaf1));

        // Add E at index 8
        final VirtualLeafBytes<TestValue> eggplantLeaf1 = eggplantLeaf(8);
        cache1.putLeaf(eggplantLeaf1);
        validateLeaves(cache1, 4, asList(cherryLeaf0, bananaLeaf1, dateLeaf1, appleLeaf1, eggplantLeaf1));

        // End the round and create the next round
        nextRound();
        cache1.prepareForHashing();
        validateDirtyLeaves(asList(bananaLeaf1, dateLeaf1, appleLeaf1, eggplantLeaf1), cache1.dirtyLeavesForHash(4, 8));

        final VirtualHashChunk path0Chunk1 = cache1.preloadHashChunk(0);
        // Hash at path 4: cherry0 - not changed
        // Hash at path 5: banana1
        final Hash bananaLeaf1intHash = hash(bananaLeaf1);
        path0Chunk1.setHashAtPath(bananaLeaf1.path(), bananaLeaf1intHash);
        // Hash at path 6: date1
        final Hash dateLeaf1intHash = hash(dateLeaf1);
        path0Chunk1.setHashAtPath(dateLeaf1.path(), dateLeaf1intHash);
        cache1.putHashChunk(path0Chunk1);

        final VirtualHashChunk path3Chunk1 = cache1.preloadHashChunk(3);
        // Hash at path 7: apple1
        final Hash appleLeaf1intHash = hash(appleLeaf1);
        path3Chunk1.setHashAtPath(appleLeaf1.path(), appleLeaf1intHash);
        // Hash at path 8: eggplant1
        final Hash eggplantLeaf1intHash = hash(eggplantLeaf1);
        path3Chunk1.setHashAtPath(eggplantLeaf1.path(), eggplantLeaf1intHash);
        cache1.putHashChunk(path3Chunk1);

        cache1.seal();

        validateLeaves(cache1, asList(cherryLeaf0, bananaLeaf1, dateLeaf1, appleLeaf1, eggplantLeaf1));

        // Chunk 0 is already in the cache, no need to load it from disk
        assertFalse(chunkLoader.getChunkIds().contains(0L)); // chunk path==0
        assertTrue(chunkLoader.getChunkIds().contains(1L)); // chunk path==3
        assertFalse(chunkLoader.getChunkIds().contains(2L)); // chunk path==4
        chunkLoader.reset();

        final Set<VirtualHashChunk> dirtyHashChunks1 =
                cache1.dirtyHashesForFlush(8).collect(Collectors.toSet());
        validateDirtyHashChunkPaths(Set.of(0L, 3L), dirtyHashChunks1);
        validateDirtyHash(cherryLeaf0.path(), cherryLeaf0intHash, dirtyHashChunks1); // 4
        validateDirtyHash(bananaLeaf1.path(), bananaLeaf1intHash, dirtyHashChunks1); // 5
        validateDirtyHash(dateLeaf1.path(), dateLeaf1intHash, dirtyHashChunks1); // 6
        validateDirtyHash(appleLeaf1.path(), appleLeaf1intHash, dirtyHashChunks1); // 7
        validateDirtyHash(eggplantLeaf1.path(), eggplantLeaf1intHash, dirtyHashChunks1); // 8
        validateNoDirtyHash(16, dirtyHashChunks1);
        validateNoDirtyHash(18, dirtyHashChunks1);

        // ROUND 2: Add F and G

        // Tree structure
        //                                                    root (0)
        //                       int (1)                                          int (2)
        //      int (3)                      int (4)                  int (5)               date (6)
        // apple (7)  eggplant (8)     cherry (9)  fig (10)   banana (11)  grape (12)

        final VirtualNodeCache cache2 = cache;

        // Move C to index 9
        final VirtualLeafBytes<TestValue> cherryLeaf2 = cherryLeaf(9);
        cache2.clearLeafPath(4);
        cache2.putLeaf(cherryLeaf2);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache2.lookupLeafByPath(4),
                "value that was looked up should match original value");
        validateLeaves(cache2, 5, asList(bananaLeaf1, dateLeaf1, appleLeaf1, eggplantLeaf1, cherryLeaf2));

        // Add F at index 10
        final VirtualLeafBytes<TestValue> figLeaf2 = figLeaf(10);
        cache2.putLeaf(figLeaf2);
        validateLeaves(cache2, 5, asList(bananaLeaf1, dateLeaf1, appleLeaf1, eggplantLeaf1, cherryLeaf2, figLeaf2));

        // Move B to index 11
        final VirtualLeafBytes<TestValue> bananaLeaf2 = bananaLeaf(11);
        cache2.clearLeafPath(5);
        cache2.putLeaf(bananaLeaf2);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache2.lookupLeafByPath(5),
                "value that was looked up should match original value");
        validateLeaves(cache2, 6, asList(dateLeaf1, appleLeaf1, eggplantLeaf1, cherryLeaf2, figLeaf2, bananaLeaf2));

        // Add G at index 12
        final VirtualLeafBytes<TestValue> grapeLeaf2 = grapeLeaf(12);
        cache2.putLeaf(grapeLeaf2);
        validateLeaves(
                cache2,
                6,
                asList(dateLeaf1, appleLeaf1, eggplantLeaf1, cherryLeaf2, figLeaf2, bananaLeaf2, grapeLeaf2));

        // End the round and create the next round
        nextRound();
        cache2.prepareForHashing();
        validateDirtyLeaves(asList(cherryLeaf2, figLeaf2, bananaLeaf2, grapeLeaf2), cache2.dirtyLeavesForHash(6, 12));

        // Chunk at path 0
        // Hash at path 6: date1 - not changed

        // Chunk at path 3
        // Hash at path 7: apple1 - not changed
        // Hash at path 8: eggplant1 - not changed

        final VirtualHashChunk path4Chunk2 = cache2.preloadHashChunk(4);
        // Hash at path 9: cherry2
        final Hash cherryLeaf2intHash = hash(cherryLeaf2);
        path4Chunk2.setHashAtPath(cherryLeaf2.path(), cherryLeaf2intHash);
        // Hash at path 10: fig2
        final Hash figLeaf2intHash = hash(figLeaf2);
        path4Chunk2.setHashAtPath(figLeaf2.path(), figLeaf2intHash);
        cache2.putHashChunk(path4Chunk2);

        final VirtualHashChunk path5Chunk2 = cache2.preloadHashChunk(5);
        // Hash at path 11: banana2
        final Hash bananaLeaf2intHash = hash(bananaLeaf2);
        path5Chunk2.setHashAtPath(bananaLeaf2.path(), bananaLeaf2intHash);
        // Hash at path 10: grape2
        final Hash grapeLeaf2intHash = hash(grapeLeaf2);
        path5Chunk2.setHashAtPath(grapeLeaf2.path(), grapeLeaf2intHash);
        cache2.putHashChunk(path5Chunk2);

        cache2.seal();

        validateLeaves(
                cache2, asList(dateLeaf1, appleLeaf1, eggplantLeaf1, cherryLeaf2, figLeaf2, bananaLeaf2, grapeLeaf2));

        assertFalse(chunkLoader.getChunkIds().contains(0L)); // chunk path==0
        assertFalse(chunkLoader.getChunkIds().contains(1L)); // chunk path==3
        assertTrue(chunkLoader.getChunkIds().contains(2L)); // chunk path==4
        assertTrue(chunkLoader.getChunkIds().contains(3L)); // chunk path==5
        assertFalse(chunkLoader.getChunkIds().contains(4L)); // chunk path==6
        chunkLoader.reset();

        final Set<VirtualHashChunk> dirtyHashChunks2 =
                cache2.dirtyHashesForFlush(12).collect(Collectors.toSet());
        validateDirtyHashChunkPaths(Set.of(4L, 5L), dirtyHashChunks2);
        validateDirtyHash(cherryLeaf2.path(), cherryLeaf2intHash, dirtyHashChunks2); // 9
        validateDirtyHash(figLeaf2.path(), figLeaf2intHash, dirtyHashChunks2); // 10
        validateDirtyHash(bananaLeaf2.path(), bananaLeaf2intHash, dirtyHashChunks2); // 11
        validateDirtyHash(grapeLeaf2.path(), grapeLeaf2intHash, dirtyHashChunks2); // 12
        validateNoDirtyHash(20, dirtyHashChunks2);
        validateNoDirtyHash(22, dirtyHashChunks2);
        validateNoDirtyHash(24, dirtyHashChunks2);
        validateNoDirtyHash(26, dirtyHashChunks2);

        // Now it is time to start mutating the tree. Some leaves will be removed and re-added, some
        // will be removed and replaced with a new value (same key).

        // Tree structure
        //                                                    root (0)
        //                       int (1)                                          int (2)
        //      int (3)                      int (4)                  int (5)               dog (6)
        // grape (7)  eggplant (8)     cherry (9)  fox (10)    banana (11) apple (12)

        // Remove A and move G to take its place. Move B to path 5
        final VirtualNodeCache cache3 = cache;
        final VirtualLeafBytes<TestValue> appleLeaf3 = appleLeaf(7);
        cache3.deleteLeaf(appleLeaf3);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache3.lookupLeafByPath(7),
                "value that was looked up should match original value");

        final VirtualLeafBytes<TestValue> grapeLeaf3 = grapeLeaf(7);
        cache3.clearLeafPath(12);
        cache3.putLeaf(grapeLeaf3);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache3.lookupLeafByPath(12),
                "value that was looked up should match original value");

        final VirtualLeafBytes<TestValue> bananaLeaf3 = bananaLeaf(5);
        cache3.clearLeafPath(11);
        cache3.putLeaf(bananaLeaf3);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache3.lookupLeafByPath(11),
                "value that was looked up should match original value");

        validateLeaves(cache3, 5, asList(bananaLeaf3, dateLeaf1, grapeLeaf3, eggplantLeaf1, cherryLeaf2, figLeaf2));

        // Add A back. Banana is moved to position 11, Apple goes to position 12
        VirtualLeafBytes<TestValue> appleLeaf3updated = appleLeaf3.withPath(12);
        cache3.putLeaf(appleLeaf3updated);
        VirtualLeafBytes<TestValue> bananaLeaf3updated = bananaLeaf3.withPath(11);
        cache3.putLeaf(bananaLeaf3updated);
        cache3.clearLeafPath(5);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache3.lookupLeafByPath(5),
                "value that was looked up should match original value");

        validateLeaves(
                cache3,
                6,
                asList(
                        dateLeaf1,
                        grapeLeaf3,
                        eggplantLeaf1,
                        cherryLeaf2,
                        figLeaf2,
                        bananaLeaf3updated,
                        appleLeaf3updated));

        // Update D
        final VirtualLeafBytes<TestValue> dogLeaf3 = dogLeaf(dateLeaf1.path());
        cache3.putLeaf(dogLeaf3);

        // Update F
        final VirtualLeafBytes<TestValue> foxLeaf3 = foxLeaf(figLeaf2.path());
        cache3.putLeaf(foxLeaf3);

        validateLeaves(
                cache3,
                6,
                asList(
                        dogLeaf3,
                        grapeLeaf3,
                        eggplantLeaf1,
                        cherryLeaf2,
                        foxLeaf3,
                        bananaLeaf3updated,
                        appleLeaf3updated));

        // End the round and create the next round
        nextRound();
        cache3.prepareForHashing();
        validateDirtyLeaves(
                asList(dogLeaf3, grapeLeaf3, foxLeaf3, bananaLeaf3updated, appleLeaf3updated),
                cache3.dirtyLeavesForHash(6, 12));

        final VirtualHashChunk path0Chunk3 = cache3.preloadHashChunk(0);
        // Hash at path 6: dog3
        final Hash dogLeaf3intHash = hash(dogLeaf3);
        path0Chunk3.setHashAtPath(dogLeaf3.path(), dogLeaf3intHash);
        cache3.putHashChunk(path0Chunk3);

        final VirtualHashChunk path3Chunk3 = cache3.preloadHashChunk(3);
        // Hash at path 7: grape3
        final Hash grapeLeaf3intHash = hash(grapeLeaf3);
        path3Chunk3.setHashAtPath(grapeLeaf3.path(), grapeLeaf3intHash);
        // Hash at path 8: eggplant1 - not changed
        cache3.putHashChunk(path3Chunk3);

        final VirtualHashChunk path3Chunk4 = cache3.preloadHashChunk(4);
        // Hash at path 9: cherry2 - not changed
        // Hash at path 10: fox3
        final Hash foxLeaf3intHash = hash(foxLeaf3);
        path3Chunk4.setHashAtPath(foxLeaf3.path(), foxLeaf3intHash);
        cache3.putHashChunk(path3Chunk4);

        final VirtualHashChunk path3Chunk5 = cache3.preloadHashChunk(5);
        // Hash at path 11: banana3
        final Hash bananaLeaf3intHash = hash(bananaLeaf3updated);
        path3Chunk5.setHashAtPath(bananaLeaf3updated.path(), bananaLeaf3intHash);
        // Hash at path 12: apple3
        final Hash appleLeaf3intHash = hash(appleLeaf3updated);
        path3Chunk5.setHashAtPath(appleLeaf3updated.path(), appleLeaf3intHash);
        cache3.putHashChunk(path3Chunk5);

        cache3.seal();

        assertFalse(chunkLoader.getChunkIds().contains(0L)); // chunk path==0
        assertFalse(chunkLoader.getChunkIds().contains(1L)); // chunk path==3
        assertFalse(chunkLoader.getChunkIds().contains(2L)); // chunk path==4
        assertFalse(chunkLoader.getChunkIds().contains(3L)); // chunk path==5
        assertFalse(chunkLoader.getChunkIds().contains(4L)); // chunk path==6
        chunkLoader.reset();

        final Set<VirtualHashChunk> dirtyHashChunks3 =
                cache3.dirtyHashesForFlush(12).collect(Collectors.toSet());
        validateDirtyHashChunkPaths(Set.of(0L, 3L, 4L, 5L), dirtyHashChunks3);
        validateDirtyHash(dogLeaf3.path(), dogLeaf3intHash, dirtyHashChunks3); // 6
        validateDirtyHash(grapeLeaf3.path(), grapeLeaf3intHash, dirtyHashChunks3); // 7
        validateDirtyHash(eggplantLeaf1.path(), eggplantLeaf1intHash, dirtyHashChunks3); // 8
        validateDirtyHash(cherryLeaf2.path(), cherryLeaf2intHash, dirtyHashChunks3); // 9
        validateDirtyHash(foxLeaf3.path(), foxLeaf3intHash, dirtyHashChunks3); // 10
        validateDirtyHash(bananaLeaf3updated.path(), bananaLeaf3intHash, dirtyHashChunks3); // 11
        validateDirtyHash(appleLeaf3updated.path(), appleLeaf3intHash, dirtyHashChunks3); // 12
        validateNoDirtyHash(16, dirtyHashChunks3);
        validateNoDirtyHash(18, dirtyHashChunks3);
        validateNoDirtyHash(20, dirtyHashChunks3);
        validateNoDirtyHash(22, dirtyHashChunks3);
        validateNoDirtyHash(24, dirtyHashChunks3);
        validateNoDirtyHash(26, dirtyHashChunks3);

        // At this point, we have built the tree successfully. Verify one more time that each version of
        // the cache still sees things the same way it did at the time the copy was made.
        final VirtualNodeCache cache4 = cache;

        validateLeaves(cache0, asList(bananaLeaf0, appleLeaf0, cherryLeaf0));
        validateLeaves(cache1, asList(cherryLeaf0, bananaLeaf1, dateLeaf1, appleLeaf1, eggplantLeaf1));
        validateLeaves(
                cache2, asList(dateLeaf1, appleLeaf1, eggplantLeaf1, cherryLeaf2, figLeaf2, bananaLeaf2, grapeLeaf2));
        validateLeaves(
                cache3,
                asList(
                        dogLeaf3,
                        grapeLeaf3,
                        eggplantLeaf1,
                        cherryLeaf2,
                        foxLeaf3,
                        bananaLeaf3updated,
                        appleLeaf3updated));
        validateLeaves(
                cache4,
                asList(
                        dogLeaf3,
                        grapeLeaf3,
                        eggplantLeaf1,
                        cherryLeaf2,
                        foxLeaf3,
                        bananaLeaf3updated,
                        appleLeaf3updated));

        cache4.prepareForHashing();
        cache4.seal();

        // Now, we will release the oldest, cache0
        cache0.release();
        assertEventuallyDoesNotThrow(
                () -> {
                    validateLeaves(cache1, asList(null, bananaLeaf1, dateLeaf1, appleLeaf1, eggplantLeaf1));
                },
                Duration.ofSeconds(1),
                "expected cache1 to eventually become clean");
        assertEventuallyDoesNotThrow(
                () -> {
                    validateLeaves(
                            cache2,
                            asList(
                                    dateLeaf1,
                                    appleLeaf1,
                                    eggplantLeaf1,
                                    cherryLeaf2,
                                    figLeaf2,
                                    bananaLeaf2,
                                    grapeLeaf2));
                },
                Duration.ofSeconds(1),
                "expected cache2 to eventually become clean");
        assertEventuallyDoesNotThrow(
                () -> {
                    validateLeaves(
                            cache3,
                            asList(
                                    dogLeaf3,
                                    grapeLeaf3,
                                    eggplantLeaf1,
                                    cherryLeaf2,
                                    foxLeaf3,
                                    bananaLeaf3updated,
                                    appleLeaf3updated));
                },
                Duration.ofSeconds(1),
                "expected cache3 to eventually become clean");
        assertEventuallyDoesNotThrow(
                () -> {
                    validateLeaves(
                            cache4,
                            asList(
                                    dogLeaf3,
                                    grapeLeaf3,
                                    eggplantLeaf1,
                                    cherryLeaf2,
                                    foxLeaf3,
                                    bananaLeaf3updated,
                                    appleLeaf3updated));
                },
                Duration.ofSeconds(1),
                "expected cache4 to eventually become clean");

        // Now we will release the next oldest, cache 1
        cache1.release();
        assertEventuallyDoesNotThrow(
                () -> {
                    validateLeaves(cache2, asList(null, null, null, cherryLeaf2, figLeaf2, bananaLeaf2, grapeLeaf2));
                },
                Duration.ofSeconds(1),
                "expected cache2 to eventually become clean");
        assertEventuallyDoesNotThrow(
                () -> {
                    validateLeaves(
                            cache3,
                            asList(
                                    dogLeaf3,
                                    grapeLeaf3,
                                    null,
                                    cherryLeaf2,
                                    foxLeaf3,
                                    bananaLeaf3updated,
                                    appleLeaf3updated));
                },
                Duration.ofSeconds(1),
                "expected cache3 to eventually become clean");
        assertEventuallyDoesNotThrow(
                () -> {
                    validateLeaves(
                            cache4,
                            asList(
                                    dogLeaf3,
                                    grapeLeaf3,
                                    null,
                                    cherryLeaf2,
                                    foxLeaf3,
                                    bananaLeaf3updated,
                                    appleLeaf3updated));
                },
                Duration.ofSeconds(1),
                "expected cache to eventually become clean");
    }

    /**
     * Test the public state of a fresh cache. We will test putting, deleting, and clearing
     * leaf records on a fresh cache later. A fresh cache should not be immutable or destroyed,
     * and should prohibit internal records being set. (NOTE: If in the future we want to
     * relax that and allow internals to be set on a fresh cache, we can. We just have no
     * need for it with the current design and would rather raise an exception to help catch
     * bugs than let unexpected usages cause subtle bugs).
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("A fresh cache is mutable for leaves but immutable for hashes")
    void freshCacheIsMutableForLeaves() {
        assertFalse(cache.isImmutable(), "Cache was just instantiated");
        assertFalse(cache.isDestroyed(), "Cache was just instantiated");
        final VirtualHashChunk virtualHashChunk = new VirtualHashChunk(0, HASH_CHUNK_HEIGHT);
        assertThrows(
                MutabilityException.class,
                () -> cache.putHashChunk(virtualHashChunk),
                "A fresh cache is immutable for hashes");
    }

    /**
     * When we make a fast copy, the original should be immutable for leaf modifications,
     * while the copy should be mutable.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Copied caches are immutable for all leaf modifications")
    void copiedCacheIsImmutableForLeaves() {
        final VirtualNodeCache original = cache;
        nextRound();

        final VirtualNodeCache latest = cache;
        assertTrue(original.isImmutable(), "After a round, a copy is created");
        assertFalse(latest.isImmutable(), "The latest cache is mutable");
        assertThrows(
                MutabilityException.class,
                () -> original.putLeaf(appleLeaf(A_PATH)),
                "immutable copy shouldn't be updatable");
        assertThrows(
                MutabilityException.class,
                () -> original.clearLeafPath(A_PATH),
                "immutable copy shouldn't be updatable");
        final VirtualHashChunk virtualHashChunk = new VirtualHashChunk(0, HASH_CHUNK_HEIGHT);
        assertThrows(
                MutabilityException.class,
                () -> latest.putHashChunk(virtualHashChunk),
                "immutable copy shouldn't be updatable");
    }

    /**
     * Just checking the state to make sure destroyed is not impacted by copying
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Copied caches are not destroyed")
    void copiedCacheIsNotDestroyed() {
        final VirtualNodeCache original = cache;
        nextRound();

        final VirtualNodeCache latest = cache;
        assertFalse(original.isDestroyed(), "copy should be destroyed");
        assertFalse(latest.isDestroyed(), "copy should be destroyed");
    }

    /**
     * After we make a fast copy of a cache, the original should be able to still query
     * the leaf data.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Copied caches are still queryable for leaves")
    void copiedCacheIsQueryableForLeaves() {
        final VirtualNodeCache original = cache;
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(A_PATH);
        original.putLeaf(appleLeaf0);
        nextRound();

        assertEquals(appleLeaf0, original.lookupLeafByKey(A_KEY), "value that was found should equal original");
        assertEquals(appleLeaf0, original.lookupLeafByPath(A_PATH), "value that was found should equal original");
    }

    /**
     * If we make a fast copy, the original (which was copied) should now be available
     * for internal records to be set on it.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("First copies are mutable for internal node modifications")
    void firstCopyIsMutableForInternals() {
        final VirtualNodeCache original = cache;
        nextRound();

        final VirtualNodeCache latest = cache;
        final Hash leftInternalHash = hash(LEFT_PATH);
        final VirtualHashChunk chunk0 = new VirtualHashChunk(0, HASH_CHUNK_HEIGHT);
        chunk0.setHashAtPath(LEFT_PATH, leftInternalHash);
        original.putHashChunk(chunk0);
        assertEquals(leftInternalHash, lookupHash(original, LEFT_PATH), "value that was found should equal original");
        assertEquals(leftInternalHash, lookupHash(latest, LEFT_PATH), "value that was found should equal original");
    }

    /**
     * When we make a fast copy, the latest copy should be immutable for internal records
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Latest version is immutable for internal node modifications")
    void latestIsMutableForInternals() {
        nextRound();
        final VirtualNodeCache latest = cache;
        final VirtualHashChunk virtualHashChunk = new VirtualHashChunk(0, HASH_CHUNK_HEIGHT);
        assertThrows(
                MutabilityException.class,
                () -> latest.putHashChunk(virtualHashChunk),
                "Latest is immutable for hash modifications");
    }

    /**
     * Any copy older than the latest should be available for hash modification.
     * We used to think it was only the latest - 1 copy that should accept hash
     * chunks, but we learned that during state signing it is possible to be processing more
     * than one round at a time, and thus we need to allow older copies than N-1 to also
     * allow for hash data.
     */
    @Test(/* no exception expected */ )
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Older copies are mutable for all hash modifications")
    void secondCopyIsImmutable() {
        final VirtualNodeCache original = cache;
        nextRound();
        original.prepareForHashing();

        VirtualNodeCache old = original;
        VirtualNodeCache latest = cache;
        for (int i = 1; i < 100; i++) {
            // As long as this doesn't throw an exception, the test passes.
            final VirtualNodeCache oldFinal = old;
            final VirtualHashChunk chunk =
                    new VirtualHashChunk(VirtualHashChunk.pathToChunkPath(i, HASH_CHUNK_HEIGHT), HASH_CHUNK_HEIGHT);
            assertDoesNotThrow(() -> oldFinal.putHashChunk(chunk), "Should not throw exception");
            old.seal();

            nextRound();
            latest.prepareForHashing();
            old = latest;
            latest = cache;
        }
        latest.seal();
    }

    /**
     * Setup and run a more complex scenario to verify that fast copy works correctly. Specifically,
     * we want to make sure that given some caches, if a mutation is in an older cache, it is
     * visible from a newer cache, unless the newer cache has a newer mutation for the same key or path.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Fast copy correctness tests")
    void fastCopyCorrectness() throws InterruptedException {
        // put A->APPLE into the oldest cache
        final VirtualNodeCache cache0 = cache;
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(1);
        cache0.putLeaf(appleLeaf0);
        final List<TestValue> expected0 = new ArrayList<>(asList(APPLE, null, null, null, null, null, null));
        validateCache(cache0, expected0);
        nextRound();

        // put A->AARDVARK into the next cache (overriding the value from the oldest).
        // put B->BANANA into the next cache
        final VirtualNodeCache cache1 = cache;
        final VirtualLeafBytes<TestValue> aardvarkLeaf1 = aardvarkLeaf(1);
        final VirtualLeafBytes<TestValue> bananaLeaf1 = bananaLeaf(2);
        cache1.putLeaf(aardvarkLeaf1); // Update the value of A
        cache1.putLeaf(bananaLeaf1); // add B
        final List<TestValue> expected1 = new ArrayList<>(asList(AARDVARK, BANANA, null, null, null, null, null));
        validateCache(cache0, expected0);
        validateCache(cache1, expected1);
        nextRound();

        // In the next cache, put C->CHERRY but inherit the values for A and B from the previous cache
        final VirtualNodeCache cache2 = cache;
        final VirtualLeafBytes<TestValue> cherryLeaf2 = cherryLeaf(3);
        cache2.putLeaf(cherryLeaf2);
        final List<TestValue> expected2 = new ArrayList<>(asList(AARDVARK, BANANA, CHERRY, null, null, null, null));
        validateCache(cache0, expected0);
        validateCache(cache1, expected1);
        validateCache(cache2, expected2);
        nextRound();

        // In this cache:
        // put B->BEAR overriding the value from two caches ago
        // put D->Date, E->EGGPLANT, F->FIG, and G->GRAPE into the cache
        final VirtualNodeCache cache3 = cache;
        final VirtualLeafBytes<TestValue> bearLeaf3 = bearLeaf(2);
        final VirtualLeafBytes<TestValue> dateLeaf3 = dateLeaf(4);
        final VirtualLeafBytes<TestValue> eggplantLeaf3 = eggplantLeaf(5);
        final VirtualLeafBytes<TestValue> figLeaf3 = figLeaf(6);
        final VirtualLeafBytes<TestValue> grapeLeaf3 = grapeLeaf(7);
        cache3.putLeaf(bearLeaf3);
        cache3.putLeaf(dateLeaf3);
        cache3.putLeaf(eggplantLeaf3);
        cache3.putLeaf(figLeaf3);
        cache3.putLeaf(grapeLeaf3);
        final List<TestValue> expected3 = new ArrayList<>(asList(AARDVARK, BEAR, CHERRY, DATE, EGGPLANT, FIG, GRAPE));
        validateCache(cache0, expected0);
        validateCache(cache1, expected1);
        validateCache(cache2, expected2);
        validateCache(cache3, expected3);
        nextRound();

        // In this cache override C, D, E, F, and G with new values
        final VirtualNodeCache cache4 = cache;
        final VirtualLeafBytes<TestValue> cuttlefishLeaf4 = cuttlefishLeaf(3);
        final VirtualLeafBytes<TestValue> dogLeaf4 = dogLeaf(4);
        final VirtualLeafBytes<TestValue> emuLeaf4 = emuLeaf(5);
        final VirtualLeafBytes<TestValue> foxLeaf4 = foxLeaf(6);
        final VirtualLeafBytes<TestValue> gooseLeaf4 = gooseLeaf(7);
        cache4.putLeaf(cuttlefishLeaf4);
        cache4.putLeaf(dogLeaf4);
        cache4.putLeaf(emuLeaf4);
        cache4.putLeaf(foxLeaf4);
        cache4.putLeaf(gooseLeaf4);
        final List<TestValue> expected4 = new ArrayList<>(asList(AARDVARK, BEAR, CUTTLEFISH, DOG, EMU, FOX, GOOSE));
        validateCache(cache0, expected0);
        validateCache(cache1, expected1);
        validateCache(cache2, expected2);
        validateCache(cache3, expected3);
        validateCache(cache4, expected4);

        // Releasing a middle version is not permissible (technically tested elsewhere, but what the heck)
        assertThrows(IllegalStateException.class, cache2::release, "cache should not be able to be released");

        // Release the oldest. "APPLE" was "covered up" by "AARDVARK" in v1
        cache0.release();
        validateCache(cache1, expected1);
        validateCache(cache2, expected2);
        validateCache(cache3, expected3);
        validateCache(cache4, expected4);

        // Release the now oldest. "AARDVARK" was in v1 but is gone now, so nobody has "A".
        cache1.release();

        expected2.set(expected2.indexOf(AARDVARK), null);
        expected2.set(expected2.indexOf(BANANA), null);
        expected3.set(expected3.indexOf(AARDVARK), null);
        expected4.set(expected4.indexOf(AARDVARK), null);

        assertEventuallyDoesNotThrow(
                () -> {
                    validateCache(cache2, expected2);
                    validateCache(cache3, expected3);
                    validateCache(cache4, expected4);
                },
                Duration.ofSeconds(1),
                "expected cache to eventually become clean");
    }

    /**
     * If I have just a single cache, and never make a copy of it, I should still be able
     * to release it. As far as I know, this would never happen in a working system, but
     * it seems like a reasonable expectation.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Can release the only version")
    void canReleaseOnlyCacheEvenIfNeverCopied() {
        cache.release();
        assertTrue(cache.isDestroyed(), "cache should be destroyed");
        assertTrue(cache.isImmutable(), "cache should be immutable");
    }

    /**
     * If I *have* made a copy, I cannot release the latest version. I have to release the oldest
     * copy first.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Cannot release the most recent version")
    void cannotReleaseLatest() {
        nextRound();
        assertThrows(IllegalStateException.class, cache::release, "cache should not be able to be released");
    }

    /**
     * I should only be able to release the very oldest version. So what I'm going to do is
     * create a long chain of copies and walk down the chain trying and failing to release
     * each one until I get to the oldest. Then I'll walk backwards along the chain, releasing
     * each one until none are left. This should work.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Can release the oldest version")
    void canReleaseOldest() {
        // Build the list with 100 caches (indexes 0-99)
        final List<VirtualNodeCache> caches = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            caches.add(cache);
            nextRound();
        }
        // Try and fail to release caches 99-0 (reverse iteration order -- the oldest caches are first)
        for (int i = 99; i > 0; i--) {
            final VirtualNodeCache copy = caches.get(i);
            assertThrows(IllegalStateException.class, copy::release, "cache should not be able to be released");
        }
        // Try (and hopefully succeed!) in releasing caches 0-99 (the oldest first!)
        for (int i = 0; i < 99; i++) {
            final VirtualNodeCache copy = caches.get(i);
            copy.release();
        }
    }

    /**
     * You can't release the same thing twice.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Release cannot be called twice")
    void releaseCannotBeCalledTwice() {
        cache.release();
        assertThrows(ReferenceCountException.class, cache::release, "second release should fail");
    }

    /**
     * Verify that when we release, the mutations for that release were dropped. Technically we
     * also test the case in {@link #fastCopyCorrectness()}, but in this test we are more
     * explicit about it.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Release drops the state")
    void releaseDropsState() {
        final VirtualNodeCache original = cache;
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(A_PATH);
        original.putLeaf(appleLeaf0);
        nextRound();
        original.prepareForHashing();
        original.seal();

        final VirtualNodeCache oneLess = cache;
        final VirtualLeafBytes<TestValue> bananaLeaf1 = bananaLeaf(B_PATH);
        oneLess.putLeaf(bananaLeaf1);
        nextRound();
        oneLess.prepareForHashing();

        final VirtualNodeCache latest = cache;
        final VirtualHashChunk rootChunk = new VirtualHashChunk(0, HASH_CHUNK_HEIGHT);
        final Hash leftInternalHash = hash(LEFT_PATH);
        rootChunk.setHashAtPath(LEFT_PATH, leftInternalHash);
        final VirtualLeafBytes<TestValue> cherryLeaf2 = cherryLeaf(C_PATH);
        oneLess.putHashChunk(rootChunk);
        latest.putLeaf(cherryLeaf2);
        oneLess.seal();
        latest.prepareForHashing();
        latest.seal();

        // I should be able to see everything from all three versions
        assertEquals(appleLeaf0, latest.lookupLeafByKey(A_KEY), "value that was looked up should match original value");
        assertEquals(
                appleLeaf0, latest.lookupLeafByPath(A_PATH), "value that was looked up should match original value");
        assertEquals(
                bananaLeaf1, latest.lookupLeafByKey(B_KEY), "value that was looked up should match original value");
        assertEquals(
                bananaLeaf1, latest.lookupLeafByPath(B_PATH), "value that was looked up should match original value");
        assertEquals(
                cherryLeaf2, latest.lookupLeafByKey(C_KEY), "value that was looked up should match original value");
        assertEquals(
                cherryLeaf2, latest.lookupLeafByPath(C_PATH), "value that was looked up should match original value");
        assertEquals(
                leftInternalHash,
                lookupHash(latest, LEFT_PATH),
                "value that was looked up should match original value");

        // After releasing the original, I should only see what was available in the latest two
        original.release();

        assertEventuallyDoesNotThrow(
                () -> {
                    assertNull(latest.lookupLeafByKey(A_KEY), "no leaf should be found");
                    assertNull(latest.lookupLeafByPath(A_PATH), "no leaf should be found");
                    assertEquals(
                            bananaLeaf1,
                            latest.lookupLeafByKey(B_KEY),
                            "value that was looked up should match original value");
                    assertEquals(
                            bananaLeaf1,
                            latest.lookupLeafByPath(B_PATH),
                            "value that was looked up should match original value");
                    assertEquals(
                            cherryLeaf2,
                            latest.lookupLeafByKey(C_KEY),
                            "value that was looked up should match original value");
                    assertEquals(
                            cherryLeaf2,
                            latest.lookupLeafByPath(C_PATH),
                            "value that was looked up should match original value");
                    assertEquals(
                            leftInternalHash,
                            lookupHash(latest, LEFT_PATH),
                            "value that was looked up should match original value");
                },
                Duration.ofSeconds(2),
                "expected cache to eventually become clean");
    }

    /**
     * Merging takes some copy C and merges it into the one-newer C+1 copy.
     * If there is no newer C+1 copy, then this will clearly not work.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Cannot merge the most recent copy")
    void cannotMergeMostRecent() {
        cache.seal();
        assertThrows(IllegalStateException.class, cache::merge, "merge should fail after cache is sealed");
    }

    /**
     * Merging requires both the cache being merged and the one being merged into
     * to both be sealed.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Cannot merge unsealed caches")
    void cannotMergeUnsealedCaches() {
        final VirtualNodeCache cache0 = cache;
        nextRound();
        final VirtualNodeCache cache1 = cache;
        nextRound();
        final VirtualNodeCache cache2 = cache;
        nextRound();
        nextRound();
        final VirtualNodeCache cache4 = cache;
        nextRound();
        final VirtualNodeCache cache5 = cache;
        nextRound();

        cache2.seal();
        cache4.seal();
        cache5.seal();

        // both cache0 and cache1 are unsealed. Must fail.
        assertThrows(IllegalStateException.class, cache0::merge, "merge should fail");
        // cache1 is unsealed but cache2 is sealed. Must fail.
        assertThrows(IllegalStateException.class, cache1::merge, "merge should fail");
        // cache2 is sealed but cache3 is unsealed. Must fail.
        assertThrows(IllegalStateException.class, cache2::merge, "merge should fail");
        // cache4 is sealed and cache5 is sealed. Should work.
        cache4.merge();
    }

    /**
     * Given two caches, check the following conditions:
     * - If the both caches have a "put" style mutation, only the most recent should be kept
     * - If the older cache has a "put" mutation and the newer one has "delete", "delete" should take precedence
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Merging the same key retains the most recent mutation")
    void mergingTheSameKeyRetainsTheMostRecentMutation() {
        final VirtualNodeCache cache0 = cache;
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(1);
        final VirtualLeafBytes<TestValue> bananaLeaf0 = bananaLeaf(2);
        cache0.putLeaf(appleLeaf0);
        cache0.putLeaf(bananaLeaf0);

        nextRound();

        // Change A from APPLE -> AARDVARK
        // Delete B (BANANA)
        final VirtualNodeCache cache1 = cache;
        final VirtualLeafBytes<TestValue> aardvarkLeaf1 = aardvarkLeaf(1);
        cache1.putLeaf(aardvarkLeaf1);
        cache1.deleteLeaf(bananaLeaf(2));

        cache0.seal();
        cache1.seal();

        // Merge cache 0 into cache 1
        cache0.merge();

        // Aardvark should be in cache 1
        assertEquals(aardvarkLeaf1, cache1.lookupLeafByPath(1), "value that was looked up should match original value");
        assertEquals(
                aardvarkLeaf1, cache1.lookupLeafByKey(A_KEY), "value that was looked up should match original value");
        // And Banana should be deleted
        assertEquals(
                DELETED_LEAF_RECORD,
                cache1.lookupLeafByPath(2),
                "value that was looked up should match original value");
        assertEquals(
                DELETED_LEAF_RECORD,
                cache1.lookupLeafByKey(B_KEY),
                "value that was looked up should match original value");

        final List<VirtualLeafBytes> dirtyLeaves =
                cache1.dirtyLeavesForFlush(1, 1).toList();
        assertEquals(1, dirtyLeaves.size(), "incorrect number of dirty leaves");
        assertEquals(aardvarkLeaf1, dirtyLeaves.get(0), "there should be no dirty leaves");
    }

    /**
     * If the older cache does NOT have a mutation for a given key, but the new cache does,
     * then the newer mutation should be kept.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Merging an older cache with no mutation into a newer one with the mutation keeps the mutation")
    void mergeNoMutationIntoCacheWithMutation() {
        final VirtualNodeCache cache0 = cache;
        nextRound();
        final VirtualNodeCache cache1 = cache;
        final VirtualLeafBytes<TestValue> appleLeaf1 = appleLeaf(1);
        cache1.putLeaf(appleLeaf1);

        cache0.seal();
        cache1.seal();

        // Merge cache 0 into cache 1
        cache0.merge();

        // Apple should be in cache 1
        assertEquals(appleLeaf1, cache1.lookupLeafByPath(1), "value that was looked up should match original value");
        assertEquals(appleLeaf1, cache1.lookupLeafByKey(A_KEY), "value that was looked up should match original value");
    }

    /**
     * If the old cache had a mutation but the new cache did not, then the mutation should be
     * available as part of the new cache.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Merging a mutation into a cache without one retains the mutation")
    void mergeOldMutationIntoNewerCacheWithNoMutation() {
        final VirtualNodeCache cache0 = cache;
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(1);
        cache0.putLeaf(appleLeaf0);

        nextRound();
        final VirtualNodeCache cache1 = cache;

        cache0.seal();
        cache1.seal();

        // Merge cache 0 into cache 1
        cache0.merge();

        // Apple should be in cache 1
        assertEquals(appleLeaf0, cache1.lookupLeafByPath(1), "value that was looked up should match original value");
        assertEquals(appleLeaf0, cache1.lookupLeafByKey(A_KEY), "value that was looked up should match original value");
    }

    /**
     * A somewhat redundant test that includes 4 caches instead of 2, but only merges
     * the two oldest, and validates that the values in each remaining cache are as expected.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Merging middle copies keeps correctness")
    void mergeCorrectness() {
        // Add some items
        final VirtualNodeCache cache0 = cache;
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(3);
        final VirtualLeafBytes<TestValue> bananaLeaf0 = bananaLeaf(2);
        final VirtualLeafBytes<TestValue> cherryLeaf0 = cherryLeaf(4);
        cache0.putLeaf(appleLeaf0); // new leaf
        cache0.putLeaf(bananaLeaf0); // new leaf
        cache0.putLeaf(cherryLeaf0); // new leaf
        final List<TestValue> expected0 = new ArrayList<>(asList(APPLE, BANANA, CHERRY, null, null, null, null));
        validateCache(cache0, expected0);
        nextRound();
        cache0.prepareForHashing();
        cache0.seal();

        // Update, Remove, and add some items
        final VirtualNodeCache cache1 = cache;
        final VirtualLeafBytes<TestValue> aardvarkLeaf1 = aardvarkLeaf(3);
        final VirtualLeafBytes<TestValue> bananaLeaf1 = bananaLeaf(5);
        final VirtualLeafBytes<TestValue> dateLeaf1 = dateLeaf(6);
        cache1.putLeaf(aardvarkLeaf1); // updated leaf
        cache1.putLeaf(bananaLeaf1); // moved leaf
        cache1.putLeaf(dateLeaf1); // new leaf
        final List<TestValue> expected1 = new ArrayList<>(asList(AARDVARK, BANANA, CHERRY, DATE, null, null, null));
        validateCache(cache0, expected0);
        validateCache(cache1, expected1);
        nextRound();
        cache1.prepareForHashing();
        cache1.seal();

        // Update, Remove, and add some of the same items and some new ones
        final VirtualNodeCache cache2 = cache;
        final VirtualLeafBytes<TestValue> cuttlefishLeaf2 = cuttlefishLeaf(4);
        final VirtualLeafBytes<TestValue> aardvarkLeaf2 = aardvarkLeaf(7);
        final VirtualLeafBytes<TestValue> eggplantLeaf2 = eggplantLeaf(8);
        cache2.putLeaf(aardvarkLeaf2); // moved leaf
        cache2.putLeaf(cuttlefishLeaf2); // updated leaf
        cache2.putLeaf(eggplantLeaf2); // new leaf
        final List<TestValue> expected2 =
                new ArrayList<>(asList(AARDVARK, BANANA, CUTTLEFISH, DATE, EGGPLANT, null, null));
        validateCache(cache0, expected0);
        validateCache(cache1, expected1);
        validateCache(cache2, expected2);
        nextRound();
        cache2.prepareForHashing();
        cache2.seal();

        // And a cache 3 just for fun
        final VirtualNodeCache cache3 = cache;
        final VirtualLeafBytes<TestValue> cuttlefishLeaf3 = cuttlefishLeaf(9);
        final VirtualLeafBytes<TestValue> figLeaf3 = figLeaf(10);
        final VirtualLeafBytes<TestValue> emuLeaf3 = emuLeaf(8);
        cache3.putLeaf(cuttlefishLeaf3); // moved leaf
        cache3.putLeaf(figLeaf3); // new leaf
        cache3.putLeaf(emuLeaf3); // updated leaf
        final List<TestValue> expected3 = new ArrayList<>(asList(AARDVARK, BANANA, CUTTLEFISH, DATE, EMU, FIG, null));
        validateCache(cache0, expected0);
        validateCache(cache1, expected1);
        validateCache(cache2, expected2);
        validateCache(cache3, expected3);
        cache3.prepareForHashing();
        cache3.seal();

        // Now merge cache0 into cache1.
        cache0.merge();
        validateCache(cache1, expected1); // Cache 1 should still have everything, even though cache 0 is gone.
        validateCache(cache2, expected2);
        validateCache(cache3, expected3);
    }

    /**
     * This test is a little stress test that merges two caches which are both large.
     * The intention is to test merging when the internal sets ({@link ConcurrentArray}s)
     * had to be resized a few times to compensate for the size of the elements. It is
     * also the only test that specifically checks the merging correctness for internal
     * data!
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Merge two very large caches with many items")
    void mergeStressTest() {
        // Add all the leaves in range [totalMutationCount, totalMutationCount * 2 - 1]
        final int totalMutationCount = 100_000;
        final VirtualNodeCache cache0 = cache;
        for (int i = 0; i < totalMutationCount; i++) {
            cache0.putLeaf(new VirtualLeafBytes<>(
                    totalMutationCount + i, TestKey.longToKey(i), new TestValue("Value" + i), TestValueCodec.INSTANCE));
        }

        nextRound();
        cache0.prepareForHashing();

        // Now add the internal nodes for round 0 in range [1, totalMutationCount - 1]. Note
        // that hash chunks store hashes at the last chunk rank only, so some paths will
        // be skipped in the loop below
        for (int i = 1; i < totalMutationCount; i++) {
            final byte[] internalBytes = ("Internal" + i).getBytes(StandardCharsets.UTF_8);
            final long hashChunkPath = VirtualHashChunk.pathToChunkPath(i, HASH_CHUNK_HEIGHT);
            if (VirtualHashChunk.containsPath(i, hashChunkPath, HASH_CHUNK_HEIGHT)) {
                final VirtualHashChunk chunk = cache0.preloadHashChunk(hashChunkPath);
                chunk.setHashAtPath(i, CRYPTO.digestSync(internalBytes));
                cache0.putHashChunk(chunk);
            }
        }
        cache0.seal();

        // Replace the first 60,000 leaves with newer versions, range is
        // [totalMutationCount, totalMutationCount + nextMutationCount - 1]
        final VirtualNodeCache cache1 = cache;
        final int nextMutationCount = 60_000;
        for (int i = 0; i < nextMutationCount; i++) {
            cache1.putLeaf(new VirtualLeafBytes<>(
                    totalMutationCount + i,
                    TestKey.longToKey(i),
                    new TestValue("OverriddenValue" + i),
                    TestValueCodec.INSTANCE));
        }

        nextRound();
        cache1.prepareForHashing();

        // Now override the first 60,000 internal nodes with newer versions. Range is
        // [1, nextMutationCount - 1]
        for (int i = 1; i < nextMutationCount; i++) {
            final byte[] internalBytes = ("OverriddenInternal" + i).getBytes(StandardCharsets.UTF_8);
            final long hashChunkPath = VirtualHashChunk.pathToChunkPath(i, HASH_CHUNK_HEIGHT);
            if (VirtualHashChunk.containsPath(i, hashChunkPath, HASH_CHUNK_HEIGHT)) {
                final VirtualHashChunk chunk = cache1.preloadHashChunk(hashChunkPath);
                chunk.setHashAtPath(i, CRYPTO.digestSync(internalBytes));
                cache1.putHashChunk(chunk);
            }
        }
        cache1.seal();

        nextRound();

        // Merge cache 0 into cache 1
        cache0.merge();

        // Verify everything
        final long firstLeafPath = totalMutationCount;
        final long lastLeafPath = totalMutationCount * 2 - 1;
        final AtomicInteger pathIndex = new AtomicInteger(0);
        cache1.dirtyLeavesForFlush(firstLeafPath, lastLeafPath)
                .sorted(Comparator.comparingLong(VirtualLeafBytes::path))
                .forEach(rec -> {
                    final int i = pathIndex.getAndIncrement();
                    assertEquals(totalMutationCount + i, rec.path(), "path should be one greater than mutation count");
                    assertEquals(TestKey.longToKey(i), rec.keyBytes(), "key should match expected");
                    if (i < nextMutationCount) {
                        assertEquals(
                                new TestValue("OverriddenValue" + i).toBytes(),
                                rec.valueBytes(),
                                "value should have the expected data");
                    } else {
                        assertEquals(
                                new TestValue("Value" + i).toBytes(),
                                rec.valueBytes(),
                                "value should have the expected data");
                    }
                });

        final AtomicInteger chunkIdIndex = new AtomicInteger(0);
        final Hash noHash = new Hash();
        cache1.dirtyHashesForFlush(lastLeafPath).forEach(chunk -> {
            final long chunkId = chunk.getChunkId();
            final int t = chunkIdIndex.getAndIncrement();
            assertEquals(t, chunkId, "Chunk ID should match index");
            final int chunkSize = VirtualHashChunk.getChunkSize(HASH_CHUNK_HEIGHT);
            for (int i = 0; i < chunkSize; i++) {
                final long path = chunk.getPath(i);
                // Since no hashes were put for leaves (in range [totalMutationsCount,
                // totalMutationsCount * 2 - 1]), there should be no checks for them. The original
                // version of the test checked that these hashes are null. I'm replacing these
                // checks with checks against a null hash (48 zeroes)
                final Hash hash = chunk.getHashAtIndex(i);
                if (path < nextMutationCount) { // mutated internal nodes
                    final byte[] internalBytes = ("OverriddenInternal" + path).getBytes(StandardCharsets.UTF_8);
                    assertEquals(CRYPTO.digestSync(internalBytes), hash, "hashes should match");
                } else if (path < totalMutationCount) { // original internal nodes
                    final byte[] internalBytes = ("Internal" + path).getBytes(StandardCharsets.UTF_8);
                    assertEquals(CRYPTO.digestSync(internalBytes), hash, "hashes should match");
                } else { // leaf nodes, not hashed
                    assertEquals(noHash, hash, "hashes should be null");
                }
            }
        });
    }

    /**
     * This test attempts to perform merges and releases in parallel. In the implementation we have
     * to be careful of this situation (which can happen in the real world) because we do some
     * bookkeeping of "next" and "previous" references, and both merging and releasing will play
     * havoc on that if they are concurrent.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Concurrently merge and release different caches")
    void concurrentReleasesAndMerges() {
        // This pseudo-random is used to generate some percent chance of put vs. delete mutations.
        // This isn't really necessary, just adds a little more complexity to the test.
        final Random random = new Random(1234);
        // Used by all three threads to know when to stop
        final AtomicBoolean stop = new AtomicBoolean(false);
        // Keeps track of which round we're on. I use this for generating the values for leaves, so that
        // each round has a unique value. Might not be needed, but is helpful in debugging.
        final AtomicInteger round = new AtomicInteger(0);
        // Keeps track of all caches, so I know which one to release and which to merge.
        final ConcurrentLinkedDeque<VirtualNodeCache> caches = new ConcurrentLinkedDeque<>();

        // I will have one thread that produces new caches as quickly as possible.
        // It will randomly put and delete leaves.
        final AtomicReference<Throwable> creatorThreadException = new AtomicReference<>();
        final Thread creatorThread = new Thread(() -> {
            while (!stop.get()) {
                final int r = round.getAndIncrement();
                // Create 100 mutations
                for (int i = 0; i < 100; i++) {
                    final int id = random.nextInt(10000);
                    final int chance = random.nextInt(100);
                    // Give a 90% chance of a put
                    if (chance <= 90) {
                        cache.putLeaf(new VirtualLeafBytes<>(
                                id, TestKey.longToKey(id), new TestValue(r + ":" + id), TestValueCodec.INSTANCE));
                    } else {
                        cache.deleteLeaf(new VirtualLeafBytes<>(id, TestKey.longToKey(id), null, null));
                    }
                }
                final VirtualNodeCache done = cache;
                nextRound();
                done.seal();
                caches.addLast(done);
            }
        });
        creatorThread.setDaemon(true);
        creatorThread.setUncaughtExceptionHandler((t, e) -> creatorThreadException.set(e));
        creatorThread.start();

        // I will have another thread that performs releases. Every 100us it will attempt to release a cache
        final AtomicReference<VirtualNodeCache> toRelease = new AtomicReference<>();
        final AtomicReference<Throwable> releaseThreadException = new AtomicReference<>();
        final Thread releaseThread = new Thread(() -> {
            long startNanos = System.nanoTime();
            while (!stop.get()) {
                final long currentNanos = System.nanoTime();
                if (currentNanos - startNanos >= 100_000) {
                    final VirtualNodeCache cache = toRelease.getAndSet(null);
                    if (cache != null) {
                        cache.release();
                    }
                    startNanos = currentNanos;
                }
            }
        });
        releaseThread.setDaemon(true);
        releaseThread.setUncaughtExceptionHandler((t, e) -> releaseThreadException.set(e));
        releaseThread.start();

        // I will have a final thread that performs merges as fast as it can. This increases the likelihood
        // of a race with the release thread.
        final AtomicReference<Throwable> mergingThreadException = new AtomicReference<>();
        final Thread mergingThread = new Thread(() -> {
            while (!stop.get()) {
                final Iterator<VirtualNodeCache> itr = caches.iterator();
                if (itr.hasNext()) {
                    final VirtualNodeCache toMerge = itr.next();
                    if (itr.hasNext()) {
                        itr.remove(); // get rid of "toMerge". It is to be merged into the next.
                        toMerge.merge();
                        final VirtualNodeCache merged = itr.next();
                        if (toRelease.compareAndSet(null, merged)) {
                            itr.remove();
                        }
                    }
                }
            }
        });
        mergingThread.setDaemon(true);
        mergingThread.setUncaughtExceptionHandler((t, e) -> mergingThreadException.set(e));
        mergingThread.start();

        // We'll run the test for 1 second. That should have produced 100,000 releases. A pretty good
        // chance of a race condition happening.
        final long start = System.currentTimeMillis();
        long time = start;
        while (time < start + 1000) {
            try {
                MILLISECONDS.sleep(20);
            } catch (final InterruptedException ignored) {
            }
            time = System.currentTimeMillis();
        }

        stop.set(true);

        if (creatorThreadException.get() != null) {
            fail("exception in creator thread", creatorThreadException.get());
        }

        if (releaseThreadException.get() != null) {
            fail("exception in release thread", releaseThreadException.get());
        }

        if (mergingThreadException.get() != null) {
            fail("exception in merging thread", mergingThreadException.get());
        }
    }

    // ----------------------------------------------------------------------
    // Tests for hashes
    // ----------------------------------------------------------------------

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("NPE when putting a null hash chunk")
    void puttingANullInternalLeadsToNPE() {
        final VirtualNodeCache cache0 = cache;
        nextRound();
        assertThrows(
                NullPointerException.class, () -> cache0.putHashChunk(null), "null shouldn't be accepted into cache");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Put a hash")
    void putAnInternal() {
        final VirtualNodeCache cache0 = cache;
        nextRound();

        final VirtualHashChunk rootChunk = new VirtualHashChunk(ROOT_PATH, HASH_CHUNK_HEIGHT);
        final Hash leftHash = hash(LEFT_PATH);
        rootChunk.setHashAtPath(LEFT_PATH, leftHash);
        cache0.putHashChunk(rootChunk);
        assertEquals(leftHash, lookupHash(cache0, LEFT_PATH), "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Put the same hash twice")
    void putAnInternalTwice() {
        final VirtualNodeCache cache0 = cache;
        nextRound();

        final VirtualHashChunk rootChunk = new VirtualHashChunk(ROOT_PATH, HASH_CHUNK_HEIGHT);
        final Hash leftHash = hash(LEFT_PATH);
        rootChunk.setHashAtPath(LEFT_PATH, leftHash);
        cache0.putHashChunk(rootChunk);
        assertEquals(leftHash, lookupHash(cache0, LEFT_PATH), "value that was looked up should match original value");
        cache0.putHashChunk(rootChunk);
        assertEquals(leftHash, lookupHash(cache0, LEFT_PATH), "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Put the same internal twice with different hashes")
    void putAnInternalTwiceWithDifferentHashes() {
        final VirtualNodeCache cache0 = cache;
        nextRound();

        final VirtualHashChunk chunk1 = new VirtualHashChunk(ROOT_PATH, HASH_CHUNK_HEIGHT);
        final Hash leftHash1 = CRYPTO.digestSync("Left 1".getBytes(StandardCharsets.UTF_8));
        chunk1.setHashAtPath(LEFT_PATH, leftHash1);
        cache0.putHashChunk(chunk1);

        final VirtualHashChunk chunk2 = new VirtualHashChunk(ROOT_PATH, HASH_CHUNK_HEIGHT);
        final Hash leftHash2 = CRYPTO.digestSync("Left 2".getBytes(StandardCharsets.UTF_8));
        chunk2.setHashAtPath(LEFT_PATH, leftHash2);
        cache0.putHashChunk(chunk2);

        assertEquals(leftHash2, lookupHash(cache0, LEFT_PATH), "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Exception when getting an internal on a released cache")
    void gettingAnInternalOnReleasedCacheThrows() {
        final VirtualNodeCache cache0 = cache;
        nextRound();

        final VirtualHashChunk chunk = new VirtualHashChunk(ROOT_PATH, HASH_CHUNK_HEIGHT);
        final Hash rightHash = hash(RIGHT_PATH);
        chunk.setHashAtPath(RIGHT_PATH, rightHash);
        cache0.putHashChunk(chunk);

        cache0.seal();
        cache0.release();

        assertNull(cache0.lookupLeafByPath(ROOT_PATH), "should not be able to look up value on destroyed cache");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Getting non-existent internals returns null")
    void gettingANonExistentInternalGivesNull() {
        final VirtualNodeCache cache0 = cache;
        nextRound();
        assertNull(lookupHash(cache0, 100), "value should not be found");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Hash lookup works across versions")
    void getHashWorksAcrossVersions() {
        final VirtualNodeCache cache0 = cache;
        nextRound();

        final VirtualHashChunk chunk = new VirtualHashChunk(ROOT_PATH, HASH_CHUNK_HEIGHT);
        final Hash rightHash = CRYPTO.digestSync("Right 0".getBytes(StandardCharsets.UTF_8));
        chunk.setHashAtPath(RIGHT_PATH, rightHash);
        cache0.putHashChunk(chunk);

        final VirtualNodeCache cache1 = cache;
        nextRound();

        final VirtualNodeCache cache2 = cache;
        nextRound();

        assertEquals(rightHash, lookupHash(cache1, RIGHT_PATH), "value that was looked up should match original value");
        assertEquals(rightHash, lookupHash(cache2, RIGHT_PATH), "value that was looked up should match original value");
        assertEquals(
                lookupHash(cache1, RIGHT_PATH),
                lookupHash(cache2, RIGHT_PATH),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("dirtyInternals() cannot be called if the dirtyInternals are still mutable")
    void dirtyInternalsMustButImmutableToCreateASortedStream() {
        final VirtualNodeCache cache0 = cache;
        nextRound();

        assertThrows(
                MutabilityException.class,
                () -> cache0.dirtyHashesForFlush(RIGHT_PATH),
                "shouldn't be able to call method on immutable cache");

        final VirtualHashChunk chunk = new VirtualHashChunk(ROOT_PATH, HASH_CHUNK_HEIGHT);
        cache0.putHashChunk(chunk);
        nextRound(); // seals dirtyLeaves, but not dirtyInternals

        assertThrows(
                MutabilityException.class,
                () -> cache0.dirtyHashesForFlush(RIGHT_PATH),
                "shouldn't be able to call method on immutable cache");
    }

    // ----------------------------------------------------------------------
    // Tests for leaves
    // ----------------------------------------------------------------------

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("NPE when putting a null leaf")
    void puttingANullLeafLeadsToNPE() {
        assertThrows(NullPointerException.class, () -> cache.putLeaf(null), "cache should not accept null leaf");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Put a leaf")
    void putALeaf() {
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        assertEquals(appleLeaf0, cache.lookupLeafByKey(A_KEY), "value that was looked up should match original value");
        assertEquals(
                appleLeaf0, cache.lookupLeafByPath(A_PATH), "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Put the same leaf twice")
    void putALeafTwice() {
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        cache.putLeaf(appleLeaf0);
        assertEquals(appleLeaf0, cache.lookupLeafByKey(A_KEY), "value that was looked up should match original value");
        assertEquals(
                appleLeaf0, cache.lookupLeafByPath(A_PATH), "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Put the same leaf twice with different values")
    void putALeafTwiceWithDifferentValues() {
        final VirtualLeafBytes<TestValue> appleLeaf1 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf1);
        VirtualLeafBytes<TestValue> appleLeaf2 = appleLeaf(A_PATH);
        appleLeaf2 = appleLeaf2.withValue(new TestValue("second"), TestValueCodec.INSTANCE);
        cache.putLeaf(appleLeaf2);
        assertEquals(appleLeaf2, cache.lookupLeafByKey(A_KEY), "value that was looked up should match original value");
        assertEquals(
                appleLeaf2, cache.lookupLeafByPath(A_PATH), "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Put the same leaf twice with different paths")
    void putALeafTwiceWithDifferentPaths() {
        VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        cache.clearLeafPath(A_PATH);
        appleLeaf0 = appleLeaf0.withPath(100);
        cache.putLeaf(appleLeaf0);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByPath(A_PATH),
                "value that was looked up should match original value");
        assertEquals(appleLeaf0, cache.lookupLeafByKey(A_KEY), "value that was looked up should match original value");
        assertEquals(appleLeaf0, cache.lookupLeafByPath(100), "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Delete a leaf with null key leads to NPE")
    void deletingALeafWithANullKeyLeadsToNPE() {
        assertThrows(NullPointerException.class, () -> cache.deleteLeaf(null), "should not be able to delete null");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Immutable cache cannot delete leaves")
    void deletingALeafWithImmutableCacheThrows() {
        cache.seal();
        final VirtualLeafBytes<TestValue> virtualRecord = appleLeaf(1);
        assertThrows(
                MutabilityException.class,
                () -> cache.deleteLeaf(virtualRecord),
                "delete should not be possible on immutable cache");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Deleting a non-existent node is OK")
    void deletingALeafThatDoesNotExistIsOK() {
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(1);
        assertNull(cache.lookupLeafByKey(appleLeaf0.keyBytes()), "no value should be found");
        cache.deleteLeaf(appleLeaf0);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByKey(appleLeaf0.keyBytes()),
                "value that was looked up should match original value");
        assertEquals(
                DELETED_LEAF_RECORD, cache.lookupLeafByPath(1), "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Deleting an existent node in the same cache version is OK")
    void deletingALeafThatDoesExistIsOK() {
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        assertEquals(
                appleLeaf0,
                cache.lookupLeafByKey(appleLeaf0.keyBytes()),
                "value that was looked up should match original value");
        assertEquals(
                appleLeaf0, cache.lookupLeafByPath(A_PATH), "value that was looked up should match original value");
        cache.deleteLeaf(appleLeaf0);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByKey(appleLeaf0.keyBytes()),
                "value that was looked up should match original value");
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByPath(A_PATH),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Deleting an existent node across cache versions is OK")
    void deletingALeafThatDoesExistInOlderCacheIsOK() {
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        final VirtualNodeCache original = cache;
        nextRound();
        original.seal();

        final VirtualLeafBytes<TestValue> appleLeaf1 = appleLeaf(A_PATH);
        cache.deleteLeaf(appleLeaf1);

        assertEquals(
                appleLeaf0,
                original.lookupLeafByKey(appleLeaf0.keyBytes()),
                "value that was looked up should match original value");
        assertEquals(
                appleLeaf0, original.lookupLeafByPath(A_PATH), "value that was looked up should match original value");
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByKey(appleLeaf0.keyBytes()),
                "value that was looked up should match original value");
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByPath(A_PATH),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Clearing a leaf path results in a deletion tombstone for that path")
    void clearingALeafPath() {
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        final VirtualNodeCache original = cache;
        nextRound();
        original.seal();

        cache.clearLeafPath(A_PATH);

        assertEquals(
                appleLeaf0,
                original.lookupLeafByKey(appleLeaf0.keyBytes()),
                "value that was looked up should match original value");
        assertEquals(
                appleLeaf0, original.lookupLeafByPath(A_PATH), "value that was looked up should match original value");
        assertEquals(
                appleLeaf0,
                cache.lookupLeafByKey(appleLeaf0.keyBytes()),
                "value that was looked up should match original value");
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByPath(A_PATH),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("NPE When getting a leaf with a null key")
    void gettingALeafWithANullKeyLeadsToNPE() {
        assertThrows(
                NullPointerException.class,
                () -> cache.lookupLeafByKey(null),
                "should not be able to look up a null key");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Exception when getting a leaf on a destroyed cache")
    void gettingALeafOnDestroyedCacheThrows() {
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        cache.seal();
        cache.release();

        assertNull(cache.lookupLeafByKey(A_KEY), "shouldn't be able to key on destroyed cache");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Getting non-existent leaves returns null")
    void gettingANonExistentLeafGivesNull() {
        assertNull(cache.lookupLeafByKey(TestKey.longToKey(BOGUS_KEY_ID)), "no value should be found");
        assertNull(cache.lookupLeafByPath(100), "no value should be found");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Lookup by leaf key with forModify works across versions")
    void getLeafByKeyWithForModify() {
        // Add APPLE to the original cache
        final VirtualNodeCache cache0 = cache;
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache0.putLeaf(appleLeaf0);
        nextRound();

        // Create a new cache and use lookupLeafByKey with forModify true
        final VirtualNodeCache cache1 = cache0.copy();
        VirtualLeafBytes<TestValue> aardvarkLeaf1 = cache1.lookupLeafByKey(A_KEY);
        assertNotNull(aardvarkLeaf1, "value should have been found");
        aardvarkLeaf1 = aardvarkLeaf1.withValue(AARDVARK, TestValueCodec.INSTANCE);
        cache1.putLeaf(aardvarkLeaf1);
        assertEquals(appleLeaf0, cache0.lookupLeafByKey(A_KEY), "value should match original");
        assertEquals(appleLeaf0, cache0.lookupLeafByPath(A_PATH), "lookup by path should work");
        assertEquals(aardvarkLeaf1, cache1.lookupLeafByKey(A_KEY), "value should match original");
        assertEquals(aardvarkLeaf1, cache1.lookupLeafByPath(A_PATH), "lookup by path should work");

        // Create a new cache. Release the original cache, and then lookup APPLE by A_PATH.
        final VirtualNodeCache cache2 = cache1.copy();
        cache0.release();
        assertEquals(aardvarkLeaf1, cache2.lookupLeafByKey(A_KEY), "value should match aardvark");
        assertEquals(aardvarkLeaf1, cache2.lookupLeafByPath(A_PATH), "lookup by path should work");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Lookup by leaf path with forModify works across versions")
    void getLeafByPathWithForModify() {
        // Add APPLE to the original cache
        final VirtualNodeCache cache0 = cache;
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache0.putLeaf(appleLeaf0);
        nextRound();

        // Create a new cache and use lookupLeafByKey with forModify true
        final VirtualNodeCache cache1 = cache0.copy();
        VirtualLeafBytes<TestValue> aardvarkLeaf1 = cache1.lookupLeafByPath(A_PATH);
        assertNotNull(aardvarkLeaf1, "value should have been found");
        aardvarkLeaf1 = aardvarkLeaf1.withValue(AARDVARK, TestValueCodec.INSTANCE);
        cache1.putLeaf(aardvarkLeaf1);
        assertEquals(appleLeaf0, cache0.lookupLeafByKey(A_KEY), "value should match original");
        assertEquals(appleLeaf0, cache0.lookupLeafByPath(A_PATH), "lookup by path should work");
        assertEquals(aardvarkLeaf1, cache1.lookupLeafByKey(A_KEY), "value should match original");
        assertEquals(aardvarkLeaf1, cache1.lookupLeafByPath(A_PATH), "lookup by path should work");

        // Create a new cache. Release the original cache, and then lookup APPLE by A_PATH.
        final VirtualNodeCache cache2 = cache1.copy();
        cache0.release();
        assertEquals(aardvarkLeaf1, cache2.lookupLeafByKey(A_KEY), "value should match aardvark");
        assertEquals(aardvarkLeaf1, cache2.lookupLeafByPath(A_PATH), "lookup by path should work");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("dirtyLeaves() cannot be called if the dirtyLeaves are still mutable")
    void dirtyLeavesMustButImmutableToCreateASortedStream() {
        final VirtualNodeCache cache0 = cache;
        final VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache0.putLeaf(appleLeaf0);
        assertThrows(
                MutabilityException.class,
                () -> cache0.dirtyLeavesForHash(1, 1),
                "method shouldn't work on immutable cache");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("deletedLeaves()")
    void deletedLeaves() {
        // CREATED followed by UPDATED, UPDATED+DELETED, DELETED
        // CREATED+UPDATED followed by UPDATED, UPDATED+DELETED, DELETED
        // UPDATED followed by UPDATED, UPDATED+DELETED, DELETED
        // DELETED followed by CREATED, CREATED+UPDATED, CREATED+DELETED, CREATED+UPDATED+DELETED, DELETED (nop)

        // Create the following chain of mutations:
        // A: [D, v2] -> [U+D (AARDVARK), v1] -> [C (APPLE), v0]
        // B: [D, v3] -> [C+U (BEAR, BLASTOFF), v2] -> [D, v1] -> [C (BANANA), v0]
        // C: [C+U+D (CHEMISTRY, CHAD), v3] -> [D, v2] -> [U (COMET), v1] -> [C+U (CHERRY, CUTTLEFISH), v0]
        // D: [C+U (DISCIPLINE, DENMARK), v2] -> [U+D (DRACO), v1] -> [C+U (DATE, DOG), v0]
        // E: [C+U (EXOPLANET, ECOLOGY), v3] -> [D, v2] -> [C+U (EGGPLANT, EMU), v0]
        // F: [C (FORCE), v3] -> [D, v2] -> [U (FOX), v1] -> [C (FIG), v0]
        // G: [U (GRAVITY), v3] -> [U (GOOSE), v2] -> [C (GRAPE), v1]

        final VirtualMap map0 = createMap();
        final VirtualNodeCache cache0 = map0.getCache();
        // A: [C (APPLE), v0]
        // B: [C (BANANA), v0]
        // C: [C+U (CHERRY, CUTTLEFISH), v0]
        // D: [C+U (DATE, DOG), v0]
        // E: [C+U (EGGPLANT, EMU), v0]
        // F: [C (FIG), v0]
        map0.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        map0.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        map0.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        map0.put(C_KEY, CUTTLEFISH, TestValueCodec.INSTANCE);
        map0.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        map0.put(D_KEY, DOG, TestValueCodec.INSTANCE);
        map0.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        map0.put(E_KEY, EMU, TestValueCodec.INSTANCE);
        map0.put(F_KEY, FIG, TestValueCodec.INSTANCE);

        final VirtualMap map1 = map0.copy();
        final VirtualNodeCache cache1 = map1.getCache();

        // A: [U+D (AARDVARK), v1]
        // B: [D, v1]
        // C: [U (COMET), v1]
        // D: [U+D (DRACO), v1]
        // F: [U (FOX), v1]
        // G: [C (GRAPE), v1]
        map1.put(A_KEY, AARDVARK, TestValueCodec.INSTANCE);
        map1.remove(A_KEY);
        map1.remove(B_KEY);
        map1.put(C_KEY, COMET, TestValueCodec.INSTANCE);
        map1.put(D_KEY, DRACO, TestValueCodec.INSTANCE);
        map1.remove(D_KEY);
        map1.put(F_KEY, FOX, TestValueCodec.INSTANCE);
        map1.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        final VirtualMap map2 = map1.copy();
        final VirtualNodeCache cache2 = map2.getCache();

        // A: [D, v2]
        // B: [C+U (BEAR, BLASTOFF), v2]
        // C: [D, v2]
        // D: [C+U (DISCIPLINE, DENMARK), v2]
        // E: [D, v2]
        // F: [D, v2]
        // G: [U (GOOSE), v2]
        map2.remove(A_KEY, TestValueCodec.INSTANCE);
        map2.put(B_KEY, BEAR, TestValueCodec.INSTANCE);
        map2.put(B_KEY, BLASTOFF, TestValueCodec.INSTANCE);
        map2.remove(C_KEY);
        map2.put(D_KEY, DISCIPLINE, TestValueCodec.INSTANCE);
        map2.put(D_KEY, DENMARK, TestValueCodec.INSTANCE);
        map2.remove(E_KEY);
        map2.remove(F_KEY);
        map2.put(G_KEY, GOOSE, TestValueCodec.INSTANCE);

        final VirtualMap map3 = map2.copy();
        final VirtualNodeCache cache3 = map3.getCache();

        // B: [D, v3]
        // C: [C+U+D (CHEMISTRY, CHAD), v3]
        // E: [C+U (EXOPLANET, ECOLOGY), v3]
        // F: [C (FORCE), v3]
        // G: [U (GRAVITY), v3]
        map3.remove(B_KEY);
        map3.put(C_KEY, CHEMISTRY, TestValueCodec.INSTANCE);
        map3.put(C_KEY, CHAD, TestValueCodec.INSTANCE);
        map3.remove(C_KEY);
        map3.put(E_KEY, EXOPLANET, TestValueCodec.INSTANCE);
        map3.put(E_KEY, ECOLOGY, TestValueCodec.INSTANCE);
        map3.put(F_KEY, FORCE, TestValueCodec.INSTANCE);
        map3.put(G_KEY, GRAVITY, TestValueCodec.INSTANCE);

        // One last copy, so we can get the dirty leaves without an exception
        final VirtualMap map4 = map3.copy();

        final List<VirtualLeafBytes> deletedLeaves0 = cache0.deletedLeaves().collect(Collectors.toList());
        assertEquals(0, deletedLeaves0.size(), "No deleted leaves in cache0");

        cache0.seal();
        cache1.seal();
        cache0.merge();
        validateDeletedLeaves(
                cache1.deletedLeaves().collect(Collectors.toList()), Set.of(A_KEY, B_KEY, D_KEY), "cache1");

        cache2.seal();
        cache1.merge();
        validateDeletedLeaves(
                cache2.deletedLeaves().collect(Collectors.toList()), Set.of(A_KEY, C_KEY, E_KEY, F_KEY), "cache2");

        cache3.seal();
        cache2.merge();
        validateDeletedLeaves(
                cache3.deletedLeaves().collect(Collectors.toList()), Set.of(A_KEY, B_KEY, C_KEY), "cache3");

        map0.release();
        map1.release();
        map2.release();
        map3.release();
        map4.release();
    }

    /**
     * Tests that snapshots contain all the right mutations, and none of the wrong ones.
     * This test will create a series of caches (cache0, cache1, cache2). Each cache will
     * have a series of add / delete / modify operations. It will then take snapshots of
     * each cache. It then releases each cache. It then validates that the snapshots
     * contain exactly what they should, and nothing else.
     */
    @Test
    @DisplayName("Snapshots contain all expected leaves and internal nodes")
    void snapshot() {
        // Create the caches (which are pre-validated to have the right stuff inside).
        final List<CacheInfo> caches = createCaches();
        final List<CacheInfo> snapshots = caches.stream()
                .map(original ->
                        new CacheInfo(original.cache.snapshot(), original.firstLeafPath, original.lastLeafPath))
                .collect(Collectors.toList());

        // Release the older caches
        caches.forEach(cacheInfo -> {
            if (cacheInfo.cache.isImmutable()) {
                cacheInfo.cache.release();
            }
        });

        // Create a *new* set of caches that look the same as the original ones did. Then I can compare
        // whether the snapshots match them.
        final List<CacheInfo> expectedCaches = createCaches();
        for (int i = 0; i < snapshots.size(); i++) {
            final CacheInfo snapshot = snapshots.get(i);
            final CacheInfo expected = expectedCaches.get(i);
            validateSnapshot(expected, snapshot, i);
        }
    }

    @Test
    @DisplayName("snapshot of snapshot is valid")
    void snapshotOfSnapshot() {
        cache.putLeaf(appleLeaf(1));
        cache.putLeaf(bananaLeaf(2));
        cache.copy();
        final VirtualNodeCache snapshot = cache.snapshot().snapshot().snapshot();
        assertEquals(appleLeaf(1), snapshot.lookupLeafByKey(A_KEY), "value should match expected");
        assertEquals(appleLeaf(1), snapshot.lookupLeafByPath(1), "value should match expected");
        assertEquals(bananaLeaf(2), snapshot.lookupLeafByKey(B_KEY), "value should match expected");
        assertEquals(bananaLeaf(2), snapshot.lookupLeafByPath(2), "value should match expected");
    }

    @Test
    @DisplayName("cache can produce multiple snapshots")
    void multipleSnapshotsFromOneCache() {
        cache.putLeaf(appleLeaf(1));
        cache.putLeaf(bananaLeaf(2));
        cache.copy();
        VirtualNodeCache snapshot;
        for (int i = 0; i < 10; i++) {
            snapshot = cache.snapshot();
            assertEquals(appleLeaf(1), snapshot.lookupLeafByKey(A_KEY), "value should match expected");
            assertEquals(appleLeaf(1), snapshot.lookupLeafByPath(1), "value should match expected");
            assertEquals(bananaLeaf(2), snapshot.lookupLeafByKey(B_KEY), "value should match expected");
            assertEquals(bananaLeaf(2), snapshot.lookupLeafByPath(2), "value should match expected");
        }
    }

    /**
     * Creates a chain of 3 copies. The most recent copy is leaf-mutable. Each copy has some combination
     * of creates, updates, and/or deletes. To help with understanding this test, all three trees are produced
     * below. The leaves are described as <pre>({Letter}{+|' for add or update relative to the previous copy}</pre>.
     * See the code where the diagrams are inline.
     *
     * @return The list of copies.
     */
    private List<CacheInfo> createCaches() {
        // Copy 0: Build the full tree
        //     Add A, B, C, D, E, F, G
        // 	   firstLeafPath=6; lastLeafPath=12
        //                                              (root)
        //                                                |
        //                         (left)==================================(right)
        //                           |                                        |
        //             (leftLeft)========(leftRight)           (rightLeft)=========(D+)
        //                 |                  |                     |
        //          (A+)======(E+)     (C+)=======(F+)       (B+)========(G+)
        //
        // Add A and B as leaf 1 and 2
        final VirtualNodeCache cache0 = new VirtualNodeCache(VIRTUAL_MAP_CONFIG, HASH_CHUNK_HEIGHT, chunkLoader);
        VirtualLeafBytes<TestValue> appleLeaf0 = appleLeaf(1);
        VirtualLeafBytes<TestValue> bananaLeaf0 = bananaLeaf(2);
        cache0.putLeaf(appleLeaf0);
        cache0.putLeaf(bananaLeaf0);
        // Move A to path 3 and add D at 4.
        cache0.clearLeafPath(appleLeaf0.path());
        appleLeaf0 = appleLeaf0.withPath(3);
        cache0.putLeaf(appleLeaf0);
        VirtualLeafBytes<TestValue> cherryLeaf0 = cherryLeaf(4);
        cache0.putLeaf(cherryLeaf0);
        // Move B to 5 and put D at 6
        cache0.clearLeafPath(bananaLeaf0.path());
        bananaLeaf0 = bananaLeaf0.withPath(5);
        cache0.putLeaf(bananaLeaf0);
        final VirtualLeafBytes<TestValue> dateLeaf0 = dateLeaf(6);
        cache0.putLeaf(dateLeaf0);
        // Move A to 7 and put E at 8
        cache0.clearLeafPath(appleLeaf0.path());
        appleLeaf0 = appleLeaf0.withPath(7);
        cache0.putLeaf(appleLeaf0);
        final VirtualLeafBytes<TestValue> eggplantLeaf0 = eggplantLeaf(8);
        cache0.putLeaf(eggplantLeaf0);
        // Move C to 9 and put F at 10
        cache0.clearLeafPath(cherryLeaf0.path());
        cherryLeaf0 = cherryLeaf0.withPath(9);
        cache0.putLeaf(cherryLeaf0);
        final VirtualLeafBytes<TestValue> figLeaf0 = figLeaf(10);
        cache0.putLeaf(figLeaf0);
        // Move B to 11 and put G at 12
        cache0.clearLeafPath(bananaLeaf0.path());
        bananaLeaf0 = bananaLeaf0.withPath(11);
        cache0.putLeaf(bananaLeaf0);
        final VirtualLeafBytes<TestValue> grapeLeaf0 = grapeLeaf(12);
        cache0.putLeaf(grapeLeaf0);

        // Create the copy and add hash everything
        final VirtualNodeCache cache1 = cache0.copy();
        cache0.prepareForHashing();

        final Hash appleLeaf0Hash = hash(appleLeaf0);
        final Hash bananaLeaf0Hash = hash(bananaLeaf0);
        final Hash cherryLeaf0Hash = hash(cherryLeaf0);
        final Hash dateLeaf0Hash = hash(dateLeaf0);
        final Hash eggplantLeaf0Hash = hash(eggplantLeaf0);
        final Hash figLeaf0Hash = hash(figLeaf0);
        final Hash grapeLeaf0Hash = hash(grapeLeaf0);

        final Hash leftRight0Hash = digest(cherryLeaf0Hash, figLeaf0Hash);
        final Hash leftLeft0Hash = digest(appleLeaf0Hash, eggplantLeaf0Hash);
        final Hash rightLeft0Hash = digest(bananaLeaf0Hash, grapeLeaf0Hash);

        final VirtualHashChunk path0Chunk0 = cache0.preloadHashChunk(0);
        path0Chunk0.setHashAtPath(3, leftLeft0Hash);
        path0Chunk0.setHashAtPath(4, leftRight0Hash);
        path0Chunk0.setHashAtPath(5, rightLeft0Hash);
        path0Chunk0.setHashAtPath(6, dateLeaf0Hash);
        cache0.putHashChunk(path0Chunk0);

        final VirtualHashChunk path3Chunk0 = cache0.preloadHashChunk(3);
        path3Chunk0.setHashAtPath(7, appleLeaf0Hash);
        path3Chunk0.setHashAtPath(8, eggplantLeaf0Hash);
        cache0.putHashChunk(path3Chunk0);

        final VirtualHashChunk path4Chunk0 = cache0.preloadHashChunk(4);
        path4Chunk0.setHashAtPath(9, cherryLeaf0Hash);
        path4Chunk0.setHashAtPath(10, figLeaf0Hash);
        cache0.putHashChunk(path4Chunk0);

        final VirtualHashChunk path5Chunk0 = cache0.preloadHashChunk(5);
        path5Chunk0.setHashAtPath(11, bananaLeaf0Hash);
        path5Chunk0.setHashAtPath(12, grapeLeaf0Hash);
        cache0.putHashChunk(path5Chunk0);

        cache0.seal();

        // Copy 1
        //     Delete B, Change C
        // 	   firstLeafPath=5; lastLeafPath=10
        //                                              (root)
        //                                                |
        //                         (left)==================================(right)
        //                           |                                        |
        //             (leftLeft)========(leftRight)                  (G')=========(D)
        //                 |                  |
        //           (A)======(E)      (C')=======(F)
        //
        // Update C
        VirtualLeafBytes<TestValue> cherryLeaf1 = cache1.lookupLeafByKey(C_KEY);
        assert cherryLeaf1 != null;
        cherryLeaf1 = cherryLeaf1.withValue(CUTTLEFISH, TestValueCodec.INSTANCE);
        cache1.putLeaf(cherryLeaf1);
        // Delete B and move G
        cache1.deleteLeaf(bananaLeaf0);
        VirtualLeafBytes<TestValue> grapeLeaf1 = cache1.lookupLeafByKey(G_KEY);
        assert grapeLeaf1 != null;
        grapeLeaf1 = grapeLeaf1.withPath(5);
        cache1.putLeaf(grapeLeaf1);
        cache1.clearLeafPath(12);

        // Create the copy and add hash everything
        final VirtualNodeCache cache2 = cache1.copy();
        cache1.prepareForHashing();

        final Hash cherryLeaf1Hash = hash(cherryLeaf1);
        final Hash leftRight1Hash = digest(cherryLeaf1Hash, figLeaf0Hash);
        final Hash grapeLeaf1Hash = hash(grapeLeaf1);

        final VirtualHashChunk path0Chunk1 = cache1.preloadHashChunk(0);
        path0Chunk1.setHashAtPath(4, leftRight1Hash);
        path0Chunk1.setHashAtPath(5, grapeLeaf1Hash);
        cache1.putHashChunk(path0Chunk1);

        final VirtualHashChunk path4Chunk1 = cache1.preloadHashChunk(4);
        path4Chunk1.setHashAtPath(9, cherryLeaf1Hash);
        cache1.putHashChunk(path4Chunk1);

        cache1.seal();

        // Copy 2
        //     Change D, Delete A, Delete E, Add B
        // 	   firstLeafPath=4; lastLeafPath=8
        //                                              (root)
        //                                                |
        //                         (left)==================================(right)
        //                           |                                        |
        //             (leftLeft)========(C')                          (G)=========(D')
        //                 |
        //           (F')======(B+)
        //
        // Update D
        VirtualLeafBytes<TestValue> dateLeaf2 = cache2.lookupLeafByKey(D_KEY);
        assert dateLeaf2 != null;
        dateLeaf2 = dateLeaf2.withValue(DOG, TestValueCodec.INSTANCE);
        cache2.putLeaf(dateLeaf2);
        // Delete A and move F into A's place and C into leftRight's place
        cache2.deleteLeaf(appleLeaf0);
        VirtualLeafBytes<TestValue> figLeaf2 = cache2.lookupLeafByKey(F_KEY);
        assert figLeaf2 != null;
        figLeaf2 = figLeaf2.withPath(appleLeaf0.path());
        cache2.putLeaf(figLeaf2);
        VirtualLeafBytes<TestValue> cherryLeaf2 = cache2.lookupLeafByKey(C_KEY);
        assert cherryLeaf2 != null;
        cherryLeaf2 = cherryLeaf2.withPath(4);
        cache2.putLeaf(cherryLeaf2);
        // Delete E and move F into leftLeft's place
        cache2.deleteLeaf(eggplantLeaf0);
        figLeaf2 = figLeaf2.withPath(3);
        cache2.putLeaf(figLeaf2);
        // Finally, add B and move F back down to where it was
        final VirtualLeafBytes<TestValue> bananaLeaf2 = bananaLeaf(8);
        cache2.putLeaf(bananaLeaf2);
        figLeaf2 = figLeaf2.withPath(7);
        cache2.putLeaf(figLeaf2);

        // And we don't hash this one or make a copy.
        cache2.prepareForHashing();
        cache2.seal();

        // Verify the contents of cache0 are as expected
        assertEquals(dateLeaf0, cache0.lookupLeafByKey(D_KEY), "value should match original");
        assertEquals(dateLeaf0, cache0.lookupLeafByPath(6), "value should match original");
        assertEquals(appleLeaf0, cache0.lookupLeafByKey(A_KEY), "value should match original");
        assertEquals(appleLeaf0, cache0.lookupLeafByPath(7), "value should match original");
        assertEquals(eggplantLeaf0, cache0.lookupLeafByKey(E_KEY), "value should match original");
        assertEquals(eggplantLeaf0, cache0.lookupLeafByPath(8), "value should match original");
        assertEquals(cherryLeaf0, cache0.lookupLeafByKey(C_KEY), "value should match original");
        assertEquals(cherryLeaf0, cache0.lookupLeafByPath(9), "value should match original");
        assertEquals(figLeaf0, cache0.lookupLeafByKey(F_KEY), "value should match original");
        assertEquals(figLeaf0, cache0.lookupLeafByPath(10), "value should match original");
        assertEquals(leftLeft0Hash, lookupHash(cache0, 3), "value should match original");
        assertEquals(leftRight0Hash, lookupHash(cache0, 4), "value should match original");
        assertEquals(rightLeft0Hash, lookupHash(cache0, 5), "value should match original");
        assertEquals(dateLeaf0Hash, lookupHash(cache0, 6), "value should match original");

        // Verify the contents of cache1 are as expected
        assertEquals(grapeLeaf1, cache1.lookupLeafByKey(G_KEY), "value should match original");
        assertEquals(grapeLeaf1, cache1.lookupLeafByPath(5), "value should match original");
        assertEquals(dateLeaf0, cache1.lookupLeafByKey(D_KEY), "value should match original");
        assertEquals(dateLeaf0, cache1.lookupLeafByPath(6), "value should match original");
        assertEquals(appleLeaf0, cache1.lookupLeafByKey(A_KEY), "value should match original");
        assertEquals(appleLeaf0, cache1.lookupLeafByPath(7), "value should match original");
        assertEquals(eggplantLeaf0, cache1.lookupLeafByKey(E_KEY), "value should match original");
        assertEquals(eggplantLeaf0, cache1.lookupLeafByPath(8), "value should match original");
        assertEquals(cherryLeaf1, cache1.lookupLeafByKey(C_KEY), "value should match original");
        assertEquals(cherryLeaf1, cache1.lookupLeafByPath(9), "value should match original");
        assertEquals(figLeaf0, cache1.lookupLeafByKey(F_KEY), "value should match original");
        assertEquals(figLeaf0, cache1.lookupLeafByPath(10), "value should match original");
        assertEquals(DELETED_LEAF_RECORD, cache1.lookupLeafByKey(B_KEY), "value should be deleted");
        assertEquals(DELETED_LEAF_RECORD, cache1.lookupLeafByPath(11), "value should be deleted");
        assertEquals(leftLeft0Hash, lookupHash(cache1, 3), "value should match original");
        assertEquals(leftRight1Hash, lookupHash(cache1, 4), "value should match original");
        assertEquals(grapeLeaf1Hash, lookupHash(cache1, 5), "value should match original");
        //        assertEquals(dateLeaf0Hash, lookupHash(cache0, 6), "value should match original");
        assertEquals(dateLeaf0Hash, lookupHash(cache1, 6), "value should match original");

        // Verify the contents of cache2 are as expected
        assertEquals(cherryLeaf2, cache2.lookupLeafByKey(C_KEY), "value should match original");
        assertEquals(cherryLeaf2, cache2.lookupLeafByPath(4), "value should match original");
        assertEquals(grapeLeaf1, cache2.lookupLeafByKey(G_KEY), "value should match original");
        assertEquals(grapeLeaf1, cache2.lookupLeafByPath(5), "value should match original");
        assertEquals(dateLeaf2, cache2.lookupLeafByKey(D_KEY), "value should match original");
        assertEquals(dateLeaf2, cache2.lookupLeafByPath(6), "value should match original");
        assertEquals(figLeaf2, cache2.lookupLeafByKey(F_KEY), "value should match original");
        assertEquals(figLeaf2, cache2.lookupLeafByPath(7), "value should match original");
        assertEquals(bananaLeaf2, cache2.lookupLeafByKey(B_KEY), "value should match original");
        assertEquals(bananaLeaf2, cache2.lookupLeafByPath(8), "value should match original");

        assertEquals(DELETED_LEAF_RECORD, cache2.lookupLeafByKey(A_KEY), "value should be deleted");
        assertEquals(DELETED_LEAF_RECORD, cache2.lookupLeafByKey(E_KEY), "value should be deleted");

        return List.of(new CacheInfo(cache0, 6, 12), new CacheInfo(cache1, 5, 10), new CacheInfo(cache2, 4, 8));
    }

    private void validateSnapshot(final CacheInfo expected, final CacheInfo snapshot, final int iteration) {
        assertEquals(expected.firstLeafPath, snapshot.firstLeafPath, "Should have the same firstLeafPath");
        assertEquals(expected.lastLeafPath, snapshot.lastLeafPath, "Should have the same lastLeafPath");

        assertEquals(
                expected.cache.lookupLeafByKey(A_KEY),
                snapshot.cache.lookupLeafByKey(A_KEY),
                "Expected the leaf for A_KEY to match for snapshot on iteration " + iteration);
        assertEquals(
                expected.cache.lookupLeafByKey(B_KEY),
                snapshot.cache.lookupLeafByKey(B_KEY),
                "Expected the leaf for B_KEY to match for snapshot on iteration " + iteration);
        assertEquals(
                expected.cache.lookupLeafByKey(C_KEY),
                snapshot.cache.lookupLeafByKey(C_KEY),
                "Expected the leaf for C_KEY to match for snapshot on iteration " + iteration);
        assertEquals(
                expected.cache.lookupLeafByKey(D_KEY),
                snapshot.cache.lookupLeafByKey(D_KEY),
                "Expected the leaf for D_KEY to match for snapshot on iteration " + iteration);

        // Test looking up leaves by paths, including paths we have never used
        for (long j = expected.firstLeafPath; j <= expected.lastLeafPath; j++) {
            assertEquals(
                    expected.cache.lookupLeafByPath(j),
                    snapshot.cache.lookupLeafByPath(j),
                    "Unexpected leaf for path " + j + " in snapshot on iteration " + iteration);
        }

        // Test looking up internals by paths, including paths we have never used
        for (int j = 1; j < expected.firstLeafPath; j++) {
            assertEquals(
                    lookupHash(expected.cache, j),
                    lookupHash(expected.cache, j),
                    "Unexpected internal for path " + j + " in snapshot on iteration " + iteration);
        }
    }

    // FUTURE WORK Write a test that verifies that a snapshot cannot be mutated by either leaf or internal changes... ?
    // Is
    //  this right? Maybe not?

    // ----------------------------------------------------------------------
    // Bigger Test Scenarios
    // ----------------------------------------------------------------------

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyLeaves")})
    @DisplayName("dirtyLeaves where all mutations are in the same version and none are deleted")
    void dirtyLeaves_allInSameVersionNoneDeleted() {
        final VirtualNodeCache cache =
                new VirtualNodeCache(VIRTUAL_MAP_CONFIG, VIRTUAL_MAP_CONFIG.hashChunkHeight(), chunkLoader);
        cache.putLeaf(appleLeaf(7));
        cache.putLeaf(bananaLeaf(5));
        cache.putLeaf(cherryLeaf(4));
        cache.putLeaf(dateLeaf(6));
        cache.putLeaf(eggplantLeaf(8));
        cache.seal();

        final List<VirtualLeafBytes> leaves = cache.dirtyLeavesForHash(4, 8).toList();
        assertEquals(5, leaves.size(), "All leaves should be dirty");
        assertEquals(cherryLeaf(4), leaves.get(0), "Unexpected leaf");
        assertEquals(bananaLeaf(5), leaves.get(1), "Unexpected leaf");
        assertEquals(dateLeaf(6), leaves.get(2), "Unexpected leaf");
        assertEquals(appleLeaf(7), leaves.get(3), "Unexpected leaf");
        assertEquals(eggplantLeaf(8), leaves.get(4), "Unexpected leaf");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyLeaves")})
    @DisplayName("dirtyLeaves where all mutations are in the same version and some are deleted")
    void dirtyLeaves_allInSameVersionSomeDeleted() {
        final VirtualNodeCache cache =
                new VirtualNodeCache(VIRTUAL_MAP_CONFIG, VIRTUAL_MAP_CONFIG.hashChunkHeight(), chunkLoader);
        cache.putLeaf(appleLeaf(7));
        cache.putLeaf(bananaLeaf(5));
        cache.putLeaf(cherryLeaf(4));
        cache.putLeaf(dateLeaf(6));
        cache.putLeaf(eggplantLeaf(8));

        cache.deleteLeaf(eggplantLeaf(8));
        cache.putLeaf(appleLeaf(3));
        cache.seal();

        final List<VirtualLeafBytes> leaves = cache.dirtyLeavesForHash(3, 6).toList();
        assertEquals(4, leaves.size(), "Some leaves should be dirty");
        assertEquals(appleLeaf(3), leaves.get(0), "Unexpected leaf");
        assertEquals(cherryLeaf(4), leaves.get(1), "Unexpected leaf");
        assertEquals(bananaLeaf(5), leaves.get(2), "Unexpected leaf");
        assertEquals(dateLeaf(6), leaves.get(3), "Unexpected leaf");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyLeaves")})
    @DisplayName("dirtyLeaves where all mutations are in the same version and all are deleted")
    void dirtyLeaves_allInSameVersionAllDeleted() {
        final VirtualNodeCache cache =
                new VirtualNodeCache(VIRTUAL_MAP_CONFIG, VIRTUAL_MAP_CONFIG.hashChunkHeight(), chunkLoader);
        cache.putLeaf(appleLeaf(7));
        cache.putLeaf(bananaLeaf(5));
        cache.putLeaf(cherryLeaf(4));
        cache.putLeaf(dateLeaf(6));
        cache.putLeaf(eggplantLeaf(8));

        // I will delete them in random order, and when I delete one I need to rearrange things accordingly

        // Delete Banana
        cache.deleteLeaf(bananaLeaf(5));
        cache.putLeaf(eggplantLeaf(5));
        cache.putLeaf(appleLeaf(3));

        // Delete Date
        cache.deleteLeaf(dateLeaf(6));
        cache.putLeaf(eggplantLeaf(2));

        // Delete Eggplant
        cache.deleteLeaf(eggplantLeaf(2));
        cache.putLeaf(cherryLeaf(2));
        cache.putLeaf(appleLeaf(1));

        // Delete apple
        cache.deleteLeaf(appleLeaf(1));
        cache.putLeaf(cherryLeaf(1));

        // Delete cherry
        cache.deleteLeaf(cherryLeaf(1));
        cache.seal();

        final List<VirtualLeafBytes> leaves = cache.dirtyLeavesForFlush(-1, -1).toList();
        assertEquals(0, leaves.size(), "All leaves should be missing");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyLeaves")})
    @DisplayName("dirtyLeaves where all mutations are in the same version and some paths have hosted multiple leaves")
    void dirtyLeaves_allInSameVersionSomeDeletedPathConflict() {
        final VirtualNodeCache cache =
                new VirtualNodeCache(VIRTUAL_MAP_CONFIG, VIRTUAL_MAP_CONFIG.hashChunkHeight(), chunkLoader);
        cache.putLeaf(appleLeaf(7));
        cache.putLeaf(bananaLeaf(5));
        cache.putLeaf(cherryLeaf(4));
        cache.putLeaf(dateLeaf(6));
        cache.putLeaf(eggplantLeaf(8));

        // This is actually a tricky scenario where we get two mutation with the same
        // path and the same version, but different keys and different "deleted" status.
        // This scenario was failing when the test was written.

        // Delete Eggplant
        cache.deleteLeaf(eggplantLeaf(8));
        cache.putLeaf(appleLeaf(3));

        // Delete Cherry
        cache.deleteLeaf(cherryLeaf(4));
        cache.putLeaf(dateLeaf(4));
        cache.putLeaf(bananaLeaf(2));
        cache.seal();

        final List<VirtualLeafBytes> leaves = cache.dirtyLeavesForHash(2, 4).toList();
        assertEquals(3, leaves.size(), "Should only have three leaves");
        assertEquals(bananaLeaf(2), leaves.get(0), "Unexpected leaf");
        assertEquals(appleLeaf(3), leaves.get(1), "Unexpected leaf");
        assertEquals(dateLeaf(4), leaves.get(2), "Unexpected leaf");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyLeaves")})
    @DisplayName("dirtyLeaves where mutations are across versions and none are deleted")
    void dirtyLeaves_differentVersionsNoneDeleted() {
        // NOTE: In all these tests I don't bother with clearLeafPath since I'm not getting leave paths
        final VirtualNodeCache cache0 =
                new VirtualNodeCache(VIRTUAL_MAP_CONFIG, VIRTUAL_MAP_CONFIG.hashChunkHeight(), chunkLoader);
        cache0.putLeaf(appleLeaf(1));

        final VirtualNodeCache cache1 = cache0.copy();
        cache1.putLeaf(bananaLeaf(2));
        cache1.putLeaf(appleLeaf(3));
        cache1.putLeaf(cherryLeaf(4));

        final VirtualNodeCache cache2 = cache1.copy();
        cache2.putLeaf(bananaLeaf(5));
        cache2.putLeaf(dateLeaf(6));
        cache2.putLeaf(appleLeaf(7));
        cache2.putLeaf(eggplantLeaf(8));

        cache0.seal();
        cache1.seal();
        cache2.seal();

        cache0.merge();
        cache1.merge();

        final Set<VirtualLeafBytes> leaves = cache2.dirtyLeavesForFlush(4, 8).collect(Collectors.toSet());
        assertEquals(5, leaves.size(), "All leaves should be dirty");
        assertEquals(Set.of(cherryLeaf(4), bananaLeaf(5), dateLeaf(6), appleLeaf(7), eggplantLeaf(8)), leaves);
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyLeaves")})
    @DisplayName("dirtyLeaves where mutations are across versions and some are deleted")
    void dirtyLeaves_differentVersionsSomeDeleted() {
        final VirtualNodeCache cache0 =
                new VirtualNodeCache(VIRTUAL_MAP_CONFIG, VIRTUAL_MAP_CONFIG.hashChunkHeight(), chunkLoader);
        cache0.putLeaf(appleLeaf(1));

        final VirtualNodeCache cache1 = cache0.copy();
        cache1.putLeaf(bananaLeaf(2));
        cache1.putLeaf(appleLeaf(3));
        cache1.deleteLeaf(appleLeaf(3));
        cache1.putLeaf(figLeaf(3));
        cache1.putLeaf(cherryLeaf(4));

        final VirtualNodeCache cache2 = cache1.copy();
        cache2.putLeaf(bananaLeaf(5));
        cache2.putLeaf(dateLeaf(6));
        cache2.deleteLeaf(bananaLeaf(5));
        cache2.putLeaf(dateLeaf(5));
        cache2.putLeaf(grapeLeaf(6));
        cache2.putLeaf(figLeaf(7));
        cache2.putLeaf(eggplantLeaf(8));
        cache2.deleteLeaf(cherryLeaf(4));
        cache2.putLeaf(eggplantLeaf(4));
        cache2.putLeaf(figLeaf(3));

        cache0.seal();
        cache1.seal();
        cache2.seal();

        cache0.merge();
        cache1.merge();

        final Set<VirtualLeafBytes> leaves = cache2.dirtyLeavesForFlush(3, 6).collect(Collectors.toSet());
        assertEquals(4, leaves.size(), "Some leaves should be dirty");
        assertEquals(Set.of(figLeaf(3), eggplantLeaf(4), dateLeaf(5), grapeLeaf(6)), leaves);
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyLeaves")})
    @DisplayName("dirtyLeaves where mutations are across versions and all are deleted")
    void dirtyLeaves_differentVersionsAllDeleted() {
        final VirtualNodeCache cache0 =
                new VirtualNodeCache(VIRTUAL_MAP_CONFIG, VIRTUAL_MAP_CONFIG.hashChunkHeight(), chunkLoader);
        cache0.putLeaf(appleLeaf(1));
        cache0.putLeaf(bananaLeaf(2));
        cache0.putLeaf(appleLeaf(3));
        cache0.putLeaf(cherryLeaf(4));
        cache0.deleteLeaf(appleLeaf(3));
        cache0.putLeaf(cherryLeaf(1));

        final VirtualNodeCache cache1 = cache0.copy();
        cache1.putLeaf(cherryLeaf(3));
        cache1.putLeaf(dateLeaf(4));
        cache1.deleteLeaf(bananaLeaf(2));
        cache1.putLeaf(dateLeaf(2));
        cache1.putLeaf(cherryLeaf(1));

        final VirtualNodeCache cache2 = cache1.copy();
        cache2.putLeaf(cherryLeaf(3));
        cache2.putLeaf(eggplantLeaf(4));
        cache2.deleteLeaf(cherryLeaf(3));
        cache2.deleteLeaf(eggplantLeaf(1));
        cache2.deleteLeaf(dateLeaf(2));
        cache2.deleteLeaf(eggplantLeaf(1));

        cache0.seal();
        cache1.seal();
        cache2.seal();

        cache0.merge();
        cache1.merge();

        final List<VirtualLeafBytes> leaves = cache2.dirtyLeavesForFlush(-1, -1).toList();
        assertEquals(0, leaves.size(), "All leaves should be deleted");
    }

    @Test
    @DisplayName("dirtyHashes where all mutations are in the same version")
    void dirtyHashes_allInSameVersion() {
        final VirtualNodeCache cache0 =
                new VirtualNodeCache(VIRTUAL_MAP_CONFIG, VIRTUAL_MAP_CONFIG.hashChunkHeight(), chunkLoader);
        cache0.copy();
        cache0.prepareForHashing();

        final VirtualHashChunk path0Chunk = cache0.preloadHashChunk(0);
        final Hash leftLeftHash = hash(LEFT_LEFT_PATH);
        path0Chunk.setHashAtPath(LEFT_LEFT_PATH, leftLeftHash);
        final Hash leftRightHash = hash(LEFT_RIGHT_PATH);
        path0Chunk.setHashAtPath(LEFT_RIGHT_PATH, leftRightHash);
        final Hash rightLeftHash = hash(RIGHT_LEFT_PATH);
        path0Chunk.setHashAtPath(RIGHT_LEFT_PATH, rightLeftHash);
        final Hash rightRightHash = hash(RIGHT_RIGHT_PATH);
        path0Chunk.setHashAtPath(RIGHT_RIGHT_PATH, rightRightHash);
        cache0.putHashChunk(path0Chunk);

        final VirtualHashChunk path3Chunk = cache0.preloadHashChunk(3);
        final Hash path7Hash = hash(7);
        path3Chunk.setHashAtPath(7, path7Hash);
        final Hash path8Hash = hash(8);
        path3Chunk.setHashAtPath(8, path8Hash);
        cache0.putHashChunk(path3Chunk);

        cache0.seal();

        final Set<VirtualHashChunk> dirtyChunks = cache0.dirtyHashesForFlush(8).collect(Collectors.toSet());
        validateDirtyHash(LEFT_LEFT_PATH, leftLeftHash, dirtyChunks);
        validateDirtyHash(LEFT_RIGHT_PATH, leftRightHash, dirtyChunks);
        validateDirtyHash(RIGHT_LEFT_PATH, rightLeftHash, dirtyChunks);
        validateDirtyHash(RIGHT_RIGHT_PATH, rightRightHash, dirtyChunks);
        validateDirtyHash(7, path7Hash, dirtyChunks);
        validateDirtyHash(8, path8Hash, dirtyChunks);

        chunkLoader.reset(); // not sure if this is needed
    }

    @Test
    @DisplayName("dirtyHashes where mutations are across versions")
    void dirtyHashes_differentVersions() {
        final VirtualNodeCache cache0 =
                new VirtualNodeCache(VIRTUAL_MAP_CONFIG, VIRTUAL_MAP_CONFIG.hashChunkHeight(), chunkLoader);
        final VirtualNodeCache cache1 = cache0.copy();

        cache0.prepareForHashing();

        final VirtualHashChunk path0Chunk0 = cache0.preloadHashChunk(0);
        final Hash leftLeftHash = hash(LEFT_LEFT_PATH);
        path0Chunk0.setHashAtPath(LEFT_LEFT_PATH, leftLeftHash);
        final Hash leftRightHash = hash(LEFT_RIGHT_PATH);
        path0Chunk0.setHashAtPath(LEFT_RIGHT_PATH, leftRightHash);
        final Hash rightHash = hash(RIGHT_PATH);
        path0Chunk0.setHashAtPath(RIGHT_PATH, rightHash);
        cache0.putHashChunk(path0Chunk0);

        cache0.seal();

        cache1.prepareForHashing();

        final VirtualHashChunk path0Chunk1 = cache1.preloadHashChunk(0);
        final Hash rightLeftHash = hash(RIGHT_LEFT_PATH);
        path0Chunk1.setHashAtPath(RIGHT_LEFT_PATH, rightLeftHash);
        final Hash rightRightHash = hash(RIGHT_RIGHT_PATH);
        path0Chunk1.setHashAtPath(RIGHT_RIGHT_PATH, rightRightHash);
        cache1.putHashChunk(path0Chunk1);

        final VirtualHashChunk path3Chunk = cache1.preloadHashChunk(3);
        final Hash path7Hash = hash(7);
        path3Chunk.setHashAtPath(7, path7Hash);
        final Hash path8Hash = hash(8);
        path3Chunk.setHashAtPath(8, path8Hash);
        cache1.putHashChunk(path3Chunk);

        cache1.copy();
        cache1.seal();
        cache0.merge();

        final Set<VirtualHashChunk> dirtyChunks = cache1.dirtyHashesForFlush(8).collect(Collectors.toSet());
        validateDirtyHash(LEFT_LEFT_PATH, leftLeftHash, dirtyChunks);
        validateDirtyHash(LEFT_RIGHT_PATH, leftRightHash, dirtyChunks);
        validateDirtyHash(RIGHT_LEFT_PATH, rightLeftHash, dirtyChunks);
        validateDirtyHash(RIGHT_RIGHT_PATH, rightRightHash, dirtyChunks);
        validateDirtyHash(7, path7Hash, dirtyChunks);
        validateDirtyHash(8, path8Hash, dirtyChunks);

        chunkLoader.reset(); // not sure if this is needed
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyInternals")})
    @DisplayName("dirtyInternals where mutations are across versions and all are deleted")
    void dirtyInternals_differentVersionsAllDeleted() {
        final VirtualNodeCache cache0 =
                new VirtualNodeCache(VIRTUAL_MAP_CONFIG, VIRTUAL_MAP_CONFIG.hashChunkHeight(), chunkLoader);
        final VirtualNodeCache cache1 = cache0.copy();

        cache0.prepareForHashing();

        final VirtualHashChunk path0Chunk0 = cache0.preloadHashChunk(0);
        final Hash leftLeftHash = hash(LEFT_LEFT_PATH);
        path0Chunk0.setHashAtPath(LEFT_LEFT_PATH, leftLeftHash);
        final Hash leftRightHash = hash(LEFT_RIGHT_PATH);
        path0Chunk0.setHashAtPath(LEFT_RIGHT_PATH, leftRightHash);
        final Hash rightLeftHash = hash(RIGHT_LEFT_PATH);
        path0Chunk0.setHashAtPath(RIGHT_LEFT_PATH, rightLeftHash);
        final Hash rightRightHash = hash(RIGHT_RIGHT_PATH);
        path0Chunk0.setHashAtPath(RIGHT_RIGHT_PATH, rightRightHash);
        cache0.putHashChunk(path0Chunk0);

        cache0.seal();
        final VirtualNodeCache cache2 = cache1.copy();

        cache1.prepareForHashing();

        final VirtualHashChunk path0Chunk1 = cache1.preloadHashChunk(0);
        path0Chunk1.setHashAtPath(LEFT_LEFT_PATH, leftLeftHash);
        cache1.putHashChunk(path0Chunk1);

        cache1.seal();

        cache2.copy();
        cache2.seal();
        cache0.merge();
        cache1.merge();

        final Set<VirtualHashChunk> dirtyChunks = cache1.dirtyHashesForFlush(-1).collect(Collectors.toSet());
        assertEquals(0, dirtyChunks.size(), "No hashes should be dirty");

        chunkLoader.reset(); // not sure if this is needed
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyLeaves")})
    @DisplayName("dirtyLeaves for hashing and flushes do not affect each other")
    void dirtyLeaves_flushesAndHashing() {
        final VirtualNodeCache cache0 =
                new VirtualNodeCache(VIRTUAL_MAP_CONFIG, VIRTUAL_MAP_CONFIG.hashChunkHeight(), chunkLoader);
        cache0.putLeaf(appleLeaf(1));
        cache0.putLeaf(bananaLeaf(2));

        final VirtualNodeCache cache1 = cache0.copy();
        cache0.seal();
        cache1.deleteLeaf(appleLeaf(1));
        cache1.putLeaf(appleLeaf(3));
        cache1.putLeaf(cherryLeaf(4));

        // Hash version 0
        final List<VirtualLeafBytes> dirtyLeaves0H =
                cache0.dirtyLeavesForHash(1, 2).toList();
        assertEquals(List.of(appleLeaf(1), bananaLeaf(2)), dirtyLeaves0H);

        cache1.copy();
        cache1.seal();

        // Hash version 1
        final List<VirtualLeafBytes> dirtyLeaves1 =
                cache1.dirtyLeavesForHash(2, 4).toList();
        assertEquals(List.of(appleLeaf(3), cherryLeaf(4)), dirtyLeaves1);

        // Flush version 0
        final Set<VirtualLeafBytes> dirtyLeaves0F =
                cache0.dirtyLeavesForFlush(1, 2).collect(Collectors.toSet());
        assertEquals(Set.of(appleLeaf(1), bananaLeaf(2)), dirtyLeaves0F);
    }

    @Test
    @DisplayName("Check merged node cache memory overhead")
    void mergedCachesMemoryOverhead() {
        final VirtualNodeCache cache0 =
                new VirtualNodeCache(VIRTUAL_MAP_CONFIG, VIRTUAL_MAP_CONFIG.hashChunkHeight(), chunkLoader);
        cache0.putLeaf(appleLeaf(1));
        final long cache0EstimatedSize = cache0.getEstimatedSize();

        final VirtualNodeCache cache1 = cache0.copy();
        cache1.putLeaf(bananaLeaf(2));
        final long cache1EstimatedSize = cache1.getEstimatedSize();

        final VirtualNodeCache cache2 = cache1.copy();
        final long cache2EstimatedSize = cache2.getEstimatedSize();
        assertEquals(0, cache2EstimatedSize); // empty

        cache0.seal();
        cache1.seal();
        cache2.seal();

        final int concurrentArraySubArrayCapacity = 1024; // keep in sync with ConcurrentArray
        // One empty array (hashes) and two arrays with one element - total 3 sub-arrays
        assertEquals(concurrentArraySubArrayCapacity * 3 * Long.BYTES + cache0EstimatedSize, cache0.getEstimatedSize());
        assertEquals(concurrentArraySubArrayCapacity * 3 * Long.BYTES + cache1EstimatedSize, cache1.getEstimatedSize());
        // Three empty sub-arrays in cache2
        assertEquals(concurrentArraySubArrayCapacity * 3 * Long.BYTES, cache2.getEstimatedSize());

        cache0.merge();
        // Hashes arrays are empty. Two empty sub-arrays are merged into one empty sub-array
        assertEquals(
                concurrentArraySubArrayCapacity * 6 * Long.BYTES + cache0EstimatedSize + cache1EstimatedSize,
                cache1.getEstimatedSize());

        cache1.merge();
        // Cache2 is empty, all its concurrent arrays / sub-arrays are empty. During merge, no new sub-arrays
        // should be created
        assertEquals(
                concurrentArraySubArrayCapacity * 6 * Long.BYTES + cache0EstimatedSize + cache1EstimatedSize,
                cache1.getEstimatedSize());
    }

    // ----------------------------------------------------------------------
    // Test Utility methods
    // ----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private TestValue lookupValue(final VirtualNodeCache cache, final Bytes key) {
        final VirtualLeafBytes<TestValue> leaf = cache.lookupLeafByKey(key);
        return leaf == null ? null : leaf.value(TestValueCodec.INSTANCE);
    }

    private Hash lookupHash(final VirtualNodeCache cache, final long path) {
        assert path > 0;
        final long chunkId = VirtualHashChunk.pathToChunkId(path, HASH_CHUNK_HEIGHT);
        final VirtualHashChunk chunk = cache.lookupHashChunkById(chunkId);
        if (chunk == null) {
            return null;
        }
        return chunk.getHashAtPath(path);
    }

    private void validateCache(final VirtualNodeCache cache, final List<TestValue> expected) {
        assertEquals(
                expected.get(0), lookupValue(cache, A_KEY), "value that was looked up should match expected value");
        assertEquals(
                expected.get(1), lookupValue(cache, B_KEY), "value that was looked up should match expected value");
        assertEquals(
                expected.get(2), lookupValue(cache, C_KEY), "value that was looked up should match expected value");
        assertEquals(
                expected.get(3), lookupValue(cache, D_KEY), "value that was looked up should match expected value");
        assertEquals(
                expected.get(4), lookupValue(cache, E_KEY), "value that was looked up should match expected value");
        assertEquals(
                expected.get(5), lookupValue(cache, F_KEY), "value that was looked up should match expected value");
        assertEquals(
                expected.get(6), lookupValue(cache, G_KEY), "value that was looked up should match expected value");
    }

    private void assertNullHash(final Hash hash) {
        assertTrue((hash == null) || hash.equals(NO_HASH));
    }

    private void validateLeaves(
            final VirtualNodeCache cache, final long firstLeafPath, final List<VirtualLeafBytes> leaves) {
        long expectedPath = firstLeafPath;
        for (final VirtualLeafBytes leaf : leaves) {
            assertEquals(expectedPath, leaf.path(), "path should match expected path");
            assertEquals(
                    leaf, cache.lookupLeafByPath(leaf.path()), "value that was looked up should match original value");
            assertEquals(
                    leaf,
                    cache.lookupLeafByKey(leaf.keyBytes()),
                    "value that was looked up should match original value");
            expectedPath++;
        }
    }

    private void validateDirtyLeaves(final List<VirtualLeafBytes> expected, final Stream<VirtualLeafBytes> stream) {
        final List<VirtualLeafBytes> dirty = stream.toList();
        assertEquals(expected.size(), dirty.size(), "dirtyLeaves did not have the expected number of elements");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), dirty.get(i), "value that was looked up should match expected value");
        }
    }

    private void validateDirtyHashChunkPaths(final Set<Long> chunkPaths, final Set<VirtualHashChunk> dirtyChunks) {
        final Set<Long> dirtyChunkPaths = new HashSet<>();
        dirtyChunks.forEach(c -> dirtyChunkPaths.add(c.path()));
        assertEquals(chunkPaths, dirtyChunkPaths);
    }

    private void validateDirtyHash(final long path, final Hash hash, final Set<VirtualHashChunk> dirtyChunks) {
        for (final VirtualHashChunk chunk : dirtyChunks) {
            try {
                final Hash dirtyHash = chunk.getHashAtPath(path);
                assertEquals(hash, dirtyHash, "Hash mismatch for path " + path);
                return;
            } catch (final IllegalArgumentException e) {
                // the path is not in the chunk, ignore
            }
        }
        fail("No dirty hash chunk for path " + path);
    }

    private void validateNoDirtyHash(final long path, final Set<VirtualHashChunk> dirtyChunks) {
        validateDirtyHash(path, NO_HASH, dirtyChunks);
    }

    private void validateLeaves(final VirtualNodeCache cache, final List<VirtualLeafBytes> nodes) {
        long expectedPath = nodes.size() - 1; // first leaf path
        for (final VirtualLeafBytes leaf : nodes) {
            if (leaf == null) {
                // This signals that a leaf has fallen out of the cache.
                assertNull(cache.lookupLeafByPath(expectedPath), "no value should be found");
            } else {
                assertEquals(expectedPath, leaf.path(), "path should match the expected value");
                assertEquals(
                        leaf,
                        cache.lookupLeafByPath(leaf.path()),
                        "value that was looked up should match original value");
                assertEquals(
                        leaf,
                        cache.lookupLeafByKey(leaf.keyBytes()),
                        "value that was looked up should match original value");
            }
            expectedPath++;
        }
    }

    private Hash digest(Hash left, Hash right) {
        try {
            final MessageDigest md = MessageDigest.getInstance(Cryptography.DEFAULT_DIGEST_TYPE.algorithmName());
            md.update((byte) 0x0F);
            left.getBytes().writeTo(md);
            right.getBytes().writeTo(md);
            return new Hash(md.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
        } catch (final NoSuchAlgorithmException e) {
            throw new CryptographyException(e);
        }
    }

    private void validateDeletedLeaves(
            final List<VirtualLeafBytes> deletedLeaves, final Set<Bytes> expectedKeys, final String name) {

        assertEquals(expectedKeys.size(), deletedLeaves.size(), "Not enough deleted leaves in " + name);

        final Set<Bytes> keys =
                deletedLeaves.stream().map(VirtualLeafBytes::keyBytes).collect(Collectors.toSet());
        assertEquals(deletedLeaves.size(), keys.size(), "Two records with the same key exist in " + name);

        for (final var rec : deletedLeaves) {
            assertTrue(keys.remove(rec.keyBytes()), "A record does not have the expected key in " + name);
        }
    }

    private static class CacheInfo {
        VirtualNodeCache cache;
        long firstLeafPath;
        long lastLeafPath;

        CacheInfo(final VirtualNodeCache cache, final long first, final long last) {
            this.cache = cache;
            this.firstLeafPath = first;
            this.lastLeafPath = last;
        }
    }

    private static class TrackingHashChunkLoader implements CheckedFunction<Long, VirtualHashChunk, IOException> {

        private final Set<Long> chunkIds = new HashSet<>();

        @Override
        public VirtualHashChunk apply(final Long chunkId) {
            chunkIds.add(chunkId);
            return null;
        }

        public Set<Long> getChunkIds() {
            return chunkIds;
        }

        public void reset() {
            chunkIds.clear();
        }
    }
}

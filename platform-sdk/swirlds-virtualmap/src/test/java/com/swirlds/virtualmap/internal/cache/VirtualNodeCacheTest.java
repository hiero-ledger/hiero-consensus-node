// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.cache;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyDoesNotThrow;
import static com.swirlds.virtualmap.internal.cache.VirtualNodeCache.DELETED_HASH;
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

import com.swirlds.base.state.MutabilityException;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.HashBuilder;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.VirtualTestBase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.hiero.base.exceptions.ReferenceCountException;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

class VirtualNodeCacheTest extends VirtualTestBase {
    private static final long BOGUS_KEY_ID = -7000;

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

        // Add apple at path 1
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(1);
        cache0.putLeaf(appleLeaf0);
        validateLeaves(cache0, 1, Collections.singletonList(appleLeaf0));

        // Add banana at path 2
        final VirtualLeafRecord<TestKey, TestValue> bananaLeaf0 = bananaLeaf(2);
        cache0.putLeaf(bananaLeaf0);
        validateLeaves(cache0, 1, asList(appleLeaf0, bananaLeaf0));

        // Move apple to path 3
        appleLeaf0.setPath(3);
        cache0.clearLeafPath(1);
        cache0.putLeaf(appleLeaf0);
        assertEquals(DELETED_LEAF_RECORD, cache0.lookupLeafByPath(1, false), "leaf should have been deleted");
        validateLeaves(cache0, 2, asList(bananaLeaf0, appleLeaf0));

        // Add cherry to path 4
        final VirtualLeafRecord<TestKey, TestValue> cherryLeaf0 = cherryLeaf(4);
        cache0.putLeaf(cherryLeaf0);
        validateLeaves(cache0, 2, asList(bananaLeaf0, appleLeaf0, cherryLeaf0));

        // End the round and create the next round
        nextRound();
        validateDirtyLeaves(asList(bananaLeaf0, appleLeaf0, cherryLeaf0), cache0.dirtyLeavesForHash(2, 4));

        // Add an internal node "left" at index 1 and then root at index 0
        final VirtualHashRecord leftInternal0 = leftInternal();
        final VirtualHashRecord rootInternal0 = rootInternal();
        cache0.putHash(leftInternal0);
        cache0.putHash(rootInternal0);
        cache0.seal();
        validateTree(cache0, asList(rootInternal0, leftInternal0, bananaLeaf0, appleLeaf0, cherryLeaf0));
        final Hash bananaLeaf0intHash = cache0.lookupHashByPath(bananaLeaf0.getPath(), false);
        assertNull(bananaLeaf0intHash);
        final Hash appleLeaf0intHash = cache0.lookupHashByPath(appleLeaf0.getPath(), false);
        assertNull(appleLeaf0intHash);
        final Hash cherryLeaf0intHash = cache0.lookupHashByPath(cherryLeaf0.getPath(), false);
        assertNull(cherryLeaf0intHash);
        // This check (and many similar checks below) is arguable. In real world, dirtyHashes() is only
        // called when a cache is flushed to disk, and it happens only after VirtualMap copy is hashed, all
        // hashes are calculated and put to the cache. Here the cache doesn't contain hashes for dirty leaves
        // (bananaLeaf0, appleLeaf0, cherryLeaf0). Should dirtyHashes() include these leaf nodes? Currently
        // it doesn't
        validateDirtyInternals(Set.of(rootInternal0, leftInternal0), cache0.dirtyHashesForFlush(4));

        // ROUND 1: Add D and E.
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache;

        // Move B to index 5
        final VirtualLeafRecord<TestKey, TestValue> bananaLeaf1 = bananaLeaf(5);
        cache1.clearLeafPath(2);
        cache1.putLeaf(bananaLeaf1);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache1.lookupLeafByPath(2, false),
                "value that was looked up should match original value");
        validateLeaves(cache1, 3, asList(appleLeaf0, cherryLeaf0, bananaLeaf1));

        // Add D at index 6
        final VirtualLeafRecord<TestKey, TestValue> dateLeaf1 = dateLeaf(6);
        cache1.putLeaf(dateLeaf1);
        validateLeaves(cache1, 3, asList(appleLeaf0, cherryLeaf0, bananaLeaf1, dateLeaf1));

        // Move A to index 7
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf1 = appleLeaf(7);
        cache1.clearLeafPath(3);
        cache1.putLeaf(appleLeaf1);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache1.lookupLeafByPath(3, false),
                "value that was looked up should match original value");
        validateLeaves(cache1, 4, asList(cherryLeaf0, bananaLeaf1, dateLeaf1, appleLeaf1));

        // Add E at index 8
        final VirtualLeafRecord<TestKey, TestValue> eggplantLeaf1 = eggplantLeaf(8);
        cache1.putLeaf(eggplantLeaf1);
        validateLeaves(cache1, 4, asList(cherryLeaf0, bananaLeaf1, dateLeaf1, appleLeaf1, eggplantLeaf1));

        // End the round and create the next round
        nextRound();
        validateDirtyLeaves(asList(bananaLeaf1, dateLeaf1, appleLeaf1, eggplantLeaf1), cache1.dirtyLeavesForHash(4, 8));

        // Add an internal node "leftLeft" at index 3 and then "right" at index 2
        final VirtualHashRecord leftLeftInternal1 = leftLeftInternal();
        final VirtualHashRecord rightInternal1 = rightInternal();
        final VirtualHashRecord leftInternal1 = leftInternal();
        final VirtualHashRecord rootInternal1 = rootInternal();
        cache1.putHash(leftLeftInternal1);
        cache1.putHash(rightInternal1);
        cache1.putHash(leftInternal1);
        cache1.putHash(rootInternal1);
        cache1.seal();
        validateTree(
                cache1,
                asList(
                        rootInternal1,
                        leftInternal1,
                        rightInternal1,
                        leftLeftInternal1,
                        cherryLeaf0,
                        bananaLeaf1,
                        dateLeaf1,
                        appleLeaf1,
                        eggplantLeaf1));
        validateDirtyInternals(
                Set.of(rootInternal1, leftInternal1, rightInternal1, leftLeftInternal1), cache1.dirtyHashesForFlush(8));

        // ROUND 2: Add F and G
        final VirtualNodeCache<TestKey, TestValue> cache2 = cache;

        // Move C to index 9
        final VirtualLeafRecord<TestKey, TestValue> cherryLeaf2 = cherryLeaf(9);
        cache2.clearLeafPath(4);
        cache2.putLeaf(cherryLeaf2);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache2.lookupLeafByPath(4, false),
                "value that was looked up should match original value");
        validateLeaves(cache2, 5, asList(bananaLeaf1, dateLeaf1, appleLeaf1, eggplantLeaf1, cherryLeaf2));

        // Add F at index 10
        final VirtualLeafRecord<TestKey, TestValue> figLeaf2 = figLeaf(10);
        cache2.putLeaf(figLeaf2);
        validateLeaves(cache2, 5, asList(bananaLeaf1, dateLeaf1, appleLeaf1, eggplantLeaf1, cherryLeaf2, figLeaf2));

        // Move B to index 11
        final VirtualLeafRecord<TestKey, TestValue> bananaLeaf2 = bananaLeaf(11);
        cache2.clearLeafPath(5);
        cache2.putLeaf(bananaLeaf2);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache2.lookupLeafByPath(5, false),
                "value that was looked up should match original value");
        validateLeaves(cache2, 6, asList(dateLeaf1, appleLeaf1, eggplantLeaf1, cherryLeaf2, figLeaf2, bananaLeaf2));

        // Add G at index 12
        final VirtualLeafRecord<TestKey, TestValue> grapeLeaf2 = grapeLeaf(12);
        cache2.putLeaf(grapeLeaf2);
        validateLeaves(
                cache2,
                6,
                asList(dateLeaf1, appleLeaf1, eggplantLeaf1, cherryLeaf2, figLeaf2, bananaLeaf2, grapeLeaf2));

        // End the round and create the next round
        nextRound();
        validateDirtyLeaves(asList(cherryLeaf2, figLeaf2, bananaLeaf2, grapeLeaf2), cache2.dirtyLeavesForHash(6, 12));

        // Add an internal node "rightLeft" at index 5 and then "leftRight" at index 4
        final VirtualHashRecord rightLeftInternal2 = rightLeftInternal();
        final VirtualHashRecord leftRightInternal2 = leftRightInternal();
        final VirtualHashRecord rightInternal2 = rightInternal();
        final VirtualHashRecord leftInternal2 = leftInternal();
        final VirtualHashRecord rootInternal2 = rootInternal();
        cache2.putHash(rightLeftInternal2);
        cache2.putHash(leftRightInternal2);
        cache2.putHash(rightInternal2);
        cache2.putHash(leftInternal2);
        cache2.putHash(rootInternal2);
        cache2.seal();
        validateTree(
                cache2,
                asList(
                        rootInternal2,
                        leftInternal2,
                        rightInternal2,
                        leftLeftInternal1,
                        leftRightInternal2,
                        rightLeftInternal2,
                        dateLeaf1,
                        appleLeaf1,
                        eggplantLeaf1,
                        cherryLeaf2,
                        figLeaf2,
                        bananaLeaf2,
                        grapeLeaf2));
        validateDirtyInternals(
                Set.of(rootInternal2, leftInternal2, rightInternal2, leftRightInternal2, rightLeftInternal2),
                cache2.dirtyHashesForFlush(12));

        // Now it is time to start mutating the tree. Some leaves will be removed and re-added, some
        // will be removed and replaced with a new value (same key).

        // Remove A and move G to take its place. Move B to path 5
        final VirtualNodeCache<TestKey, TestValue> cache3 = cache;
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf3 = appleLeaf(7);
        cache3.deleteLeaf(appleLeaf3);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache3.lookupLeafByPath(7, false),
                "value that was looked up should match original value");

        final VirtualLeafRecord<TestKey, TestValue> grapeLeaf3 = grapeLeaf(7);
        cache3.clearLeafPath(12);
        cache3.putLeaf(grapeLeaf3);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache3.lookupLeafByPath(12, false),
                "value that was looked up should match original value");

        final VirtualLeafRecord<TestKey, TestValue> bananaLeaf3 = bananaLeaf(5);
        cache3.clearLeafPath(11);
        cache3.putLeaf(bananaLeaf3);
        cache3.deleteHash(5);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache3.lookupLeafByPath(11, false),
                "value that was looked up should match original value");
        assertEquals(
                DELETED_HASH,
                cache3.lookupHashByPath(5, false),
                "value that was looked up should match original value");

        validateLeaves(cache3, 5, asList(bananaLeaf3, dateLeaf1, grapeLeaf3, eggplantLeaf1, cherryLeaf2, figLeaf2));

        // Add A back. Banana is moved to position 11, Apple goes to position 12
        appleLeaf3.setPath(12);
        cache3.putLeaf(appleLeaf3);
        bananaLeaf3.setPath(11);
        cache3.putLeaf(bananaLeaf3);
        cache3.clearLeafPath(5);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache3.lookupLeafByPath(5, false),
                "value that was looked up should match original value");

        validateLeaves(
                cache3,
                6,
                asList(dateLeaf1, grapeLeaf3, eggplantLeaf1, cherryLeaf2, figLeaf2, bananaLeaf3, appleLeaf3));

        // Update D
        final VirtualLeafRecord<TestKey, TestValue> dogLeaf3 = dogLeaf(dateLeaf1.getPath());
        cache3.putLeaf(dogLeaf3);

        // Update F
        final VirtualLeafRecord<TestKey, TestValue> foxLeaf3 = foxLeaf(figLeaf2.getPath());
        cache3.putLeaf(foxLeaf3);

        validateLeaves(
                cache3, 6, asList(dogLeaf3, grapeLeaf3, eggplantLeaf1, cherryLeaf2, foxLeaf3, bananaLeaf3, appleLeaf3));

        // End the round and create the next round
        nextRound();
        validateDirtyLeaves(
                asList(dogLeaf3, grapeLeaf3, foxLeaf3, bananaLeaf3, appleLeaf3), cache3.dirtyLeavesForHash(6, 12));

        // We removed the internal node rightLeftInternal. We need to add it back in.
        final VirtualHashRecord rightLeftInternal3 = rightLeftInternal();
        final VirtualHashRecord leftRightInternal3 = leftRightInternal();
        final VirtualHashRecord leftLeftInternal3 = leftLeftInternal();
        final VirtualHashRecord rightInternal3 = rightInternal();
        final VirtualHashRecord leftInternal3 = leftInternal();
        final VirtualHashRecord rootInternal3 = rootInternal();
        cache3.putHash(rightLeftInternal3);
        cache3.putHash(leftRightInternal3);
        cache3.putHash(leftLeftInternal3);
        cache3.putHash(rightInternal3);
        cache3.putHash(leftInternal3);
        cache3.putHash(rootInternal3);
        cache3.seal();
        validateDirtyInternals(
                Set.of(
                        rootInternal3,
                        leftInternal3,
                        rightInternal3,
                        leftLeftInternal3,
                        leftRightInternal3,
                        rightLeftInternal3),
                cache3.dirtyHashesForFlush(12));

        // At this point, we have built the tree successfully. Verify one more time that each version of
        // the cache still sees things the same way it did at the time the copy was made.
        final VirtualNodeCache<TestKey, TestValue> cache4 = cache;
        validateTree(cache0, asList(rootInternal0, leftInternal0, bananaLeaf0, appleLeaf0, cherryLeaf0));
        validateTree(
                cache1,
                asList(
                        rootInternal1,
                        leftInternal1,
                        rightInternal1,
                        leftLeftInternal1,
                        cherryLeaf0,
                        bananaLeaf1,
                        dateLeaf1,
                        appleLeaf1,
                        eggplantLeaf1));
        validateTree(
                cache2,
                asList(
                        rootInternal2,
                        leftInternal2,
                        rightInternal2,
                        leftLeftInternal1,
                        leftRightInternal2,
                        rightLeftInternal2,
                        dateLeaf1,
                        appleLeaf1,
                        eggplantLeaf1,
                        cherryLeaf2,
                        figLeaf2,
                        bananaLeaf2,
                        grapeLeaf2));
        validateTree(
                cache3,
                asList(
                        rootInternal3,
                        leftInternal3,
                        rightInternal3,
                        leftLeftInternal3,
                        leftRightInternal3,
                        rightLeftInternal3,
                        dogLeaf3,
                        grapeLeaf3,
                        eggplantLeaf1,
                        cherryLeaf2,
                        foxLeaf3,
                        bananaLeaf3,
                        appleLeaf3));
        validateTree(
                cache4,
                asList(
                        rootInternal3,
                        leftInternal3,
                        rightInternal3,
                        leftLeftInternal3,
                        leftRightInternal3,
                        rightLeftInternal3,
                        dogLeaf3,
                        grapeLeaf3,
                        eggplantLeaf1,
                        cherryLeaf2,
                        foxLeaf3,
                        bananaLeaf3,
                        appleLeaf3));

        // Now, we will release the oldest, cache0
        cache0.release();
        assertEventuallyDoesNotThrow(
                () -> {
                    validateTree(
                            cache1,
                            asList(
                                    rootInternal1,
                                    leftInternal1,
                                    rightInternal1,
                                    leftLeftInternal1,
                                    null,
                                    bananaLeaf1,
                                    dateLeaf1,
                                    appleLeaf1,
                                    eggplantLeaf1));
                },
                Duration.ofSeconds(1),
                "expected cache1 to eventually become clean");
        assertEventuallyDoesNotThrow(
                () -> {
                    validateTree(
                            cache2,
                            asList(
                                    rootInternal2,
                                    leftInternal2,
                                    rightInternal2,
                                    leftLeftInternal1,
                                    leftRightInternal2,
                                    rightLeftInternal2,
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
                    validateTree(
                            cache3,
                            asList(
                                    rootInternal3,
                                    leftInternal3,
                                    rightInternal3,
                                    leftLeftInternal3,
                                    leftRightInternal3,
                                    rightLeftInternal3,
                                    dogLeaf3,
                                    grapeLeaf3,
                                    eggplantLeaf1,
                                    cherryLeaf2,
                                    foxLeaf3,
                                    bananaLeaf3,
                                    appleLeaf3));
                },
                Duration.ofSeconds(1),
                "expected cache3 to eventually become clean");
        assertEventuallyDoesNotThrow(
                () -> {
                    validateTree(
                            cache4,
                            asList(
                                    rootInternal3,
                                    leftInternal3,
                                    rightInternal3,
                                    leftLeftInternal3,
                                    leftRightInternal3,
                                    rightLeftInternal3,
                                    dogLeaf3,
                                    grapeLeaf3,
                                    eggplantLeaf1,
                                    cherryLeaf2,
                                    foxLeaf3,
                                    bananaLeaf3,
                                    appleLeaf3));
                },
                Duration.ofSeconds(1),
                "expected cache4 to eventually become clean");

        // Now we will release the next oldest, cache 1
        cache1.release();
        assertEventuallyDoesNotThrow(
                () -> {
                    validateTree(
                            cache2,
                            asList(
                                    rootInternal2,
                                    leftInternal2,
                                    rightInternal2,
                                    null,
                                    leftRightInternal2,
                                    rightLeftInternal2,
                                    null,
                                    null,
                                    null,
                                    cherryLeaf2,
                                    figLeaf2,
                                    bananaLeaf2,
                                    grapeLeaf2));
                },
                Duration.ofSeconds(1),
                "expected cache2 to eventually become clean");
        assertEventuallyDoesNotThrow(
                () -> {
                    validateTree(
                            cache3,
                            asList(
                                    rootInternal3,
                                    leftInternal3,
                                    rightInternal3,
                                    leftLeftInternal3,
                                    leftRightInternal3,
                                    rightLeftInternal3,
                                    dogLeaf3,
                                    grapeLeaf3,
                                    null,
                                    cherryLeaf2,
                                    foxLeaf3,
                                    bananaLeaf3,
                                    appleLeaf3));
                },
                Duration.ofSeconds(1),
                "expected cache3 to eventually become clean");
        assertEventuallyDoesNotThrow(
                () -> {
                    validateTree(
                            cache4,
                            asList(
                                    rootInternal3,
                                    leftInternal3,
                                    rightInternal3,
                                    leftLeftInternal3,
                                    leftRightInternal3,
                                    rightLeftInternal3,
                                    dogLeaf3,
                                    grapeLeaf3,
                                    null,
                                    cherryLeaf2,
                                    foxLeaf3,
                                    bananaLeaf3,
                                    appleLeaf3));
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
    @DisplayName("A fresh cache is mutable for leaves but immutable for internals")
    void freshCacheIsMutableForLeaves() {
        assertFalse(cache.isImmutable(), "Cache was just instantiated");
        assertFalse(cache.isDestroyed(), "Cache was just instantiated");
        final VirtualHashRecord virtualHashRecord = new VirtualHashRecord(0);
        assertThrows(
                MutabilityException.class,
                () -> cache.putHash(virtualHashRecord),
                "A fresh cache is immutable for internals");
    }

    /**
     * When we make a fast copy, the original should be immutable for leaf modifications,
     * while the copy should be mutable.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Copied caches are immutable for all leaf modifications")
    void copiedCacheIsImmutableForLeaves() {
        final VirtualNodeCache<TestKey, TestValue> original = cache;
        nextRound();

        final VirtualNodeCache<TestKey, TestValue> latest = cache;
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
        final VirtualHashRecord virtualHashRecord = new VirtualHashRecord(0);
        assertThrows(
                MutabilityException.class,
                () -> latest.putHash(virtualHashRecord),
                "immutable copy shouldn't be updatable");
    }

    /**
     * Just checking the state to make sure destroyed is not impacted by copying
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Copied caches are not destroyed")
    void copiedCacheIsNotDestroyed() {
        final VirtualNodeCache<TestKey, TestValue> original = cache;
        nextRound();

        final VirtualNodeCache<TestKey, TestValue> latest = cache;
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
        final VirtualNodeCache<TestKey, TestValue> original = cache;
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(A_PATH);
        original.putLeaf(appleLeaf0);
        nextRound();

        assertEquals(appleLeaf0, original.lookupLeafByKey(A_KEY, false), "value that was found should equal original");
        assertEquals(
                appleLeaf0, original.lookupLeafByPath(A_PATH, false), "value that was found should equal original");
    }

    /**
     * If we make a fast copy, the original (which was copied) should now be available
     * for internal records to be set on it.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("First copies are mutable for internal node modifications")
    void firstCopyIsMutableForInternals() {
        final VirtualNodeCache<TestKey, TestValue> original = cache;
        nextRound();

        final VirtualNodeCache<TestKey, TestValue> latest = cache;
        final VirtualHashRecord rootInternal0 = rootInternal();
        original.putHash(rootInternal0);
        assertEquals(
                rootInternal0.hash(),
                original.lookupHashByPath(ROOT_PATH, false),
                "value that was found should equal original");
        assertEquals(
                rootInternal0.hash(),
                latest.lookupHashByPath(ROOT_PATH, false),
                "value that was found should equal original");
    }

    /**
     * When we make a fast copy, the latest copy should be immutable for internal records
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Latest version is immutable for internal node modifications")
    void latestIsMutableForInternals() {
        nextRound();
        final VirtualNodeCache<TestKey, TestValue> latest = cache;
        final VirtualHashRecord virtualHashRecord = rootInternal();
        assertThrows(
                MutabilityException.class,
                () -> latest.putHash(virtualHashRecord),
                "Latest is immutable for internal node modifications");
    }

    /**
     * Any copy older than the latest should be available for internal record modification.
     * We used to think it was only the latest - 1 copy that should accept internal node
     * records, but we learned that during state signing it is possible to be processing more
     * than one round at a time, and thus we need to allow older copies than N-1 to also
     * allow for internal node data.
     */
    @Test(/* no exception expected */ )
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Older copies are mutable for all internal record modifications")
    void secondCopyIsImmutable() {
        final VirtualNodeCache<TestKey, TestValue> original = cache;
        nextRound();

        VirtualNodeCache<TestKey, TestValue> old = original;
        VirtualNodeCache<TestKey, TestValue> latest = cache;
        for (int i = 0; i < 100; i++) {
            // As long as this doesn't throw an exception, the test passes.
            final int iFinal = i;
            final VirtualNodeCache<TestKey, TestValue> oldFinal = old;
            assertDoesNotThrow(() -> oldFinal.putHash(new VirtualHashRecord(iFinal)), "Should not throw exception");

            nextRound();
            old = latest;
            latest = cache;
        }
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
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(1);
        cache0.putLeaf(appleLeaf0);
        final List<TestValue> expected0 = new ArrayList<>(asList(APPLE, null, null, null, null, null, null));
        validateCache(cache0, expected0);
        nextRound();

        // put A->AARDVARK into the next cache (overriding the value from the oldest).
        // put B->BANANA into the next cache
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache;
        final VirtualLeafRecord<TestKey, TestValue> aardvarkLeaf1 = aardvarkLeaf(1);
        final VirtualLeafRecord<TestKey, TestValue> bananaLeaf1 = bananaLeaf(2);
        cache1.putLeaf(aardvarkLeaf1); // Update the value of A
        cache1.putLeaf(bananaLeaf1); // add B
        final List<TestValue> expected1 = new ArrayList<>(asList(AARDVARK, BANANA, null, null, null, null, null));
        validateCache(cache0, expected0);
        validateCache(cache1, expected1);
        nextRound();

        // In the next cache, put C->CHERRY but inherit the values for A and B from the previous cache
        final VirtualNodeCache<TestKey, TestValue> cache2 = cache;
        final VirtualLeafRecord<TestKey, TestValue> cherryLeaf2 = cherryLeaf(3);
        cache2.putLeaf(cherryLeaf2);
        final List<TestValue> expected2 = new ArrayList<>(asList(AARDVARK, BANANA, CHERRY, null, null, null, null));
        validateCache(cache0, expected0);
        validateCache(cache1, expected1);
        validateCache(cache2, expected2);
        nextRound();

        // In this cache:
        // put B->BEAR overriding the value from two caches ago
        // put D->Date, E->EGGPLANT, F->FIG, and G->GRAPE into the cache
        final VirtualNodeCache<TestKey, TestValue> cache3 = cache;
        final VirtualLeafRecord<TestKey, TestValue> bearLeaf3 = bearLeaf(2);
        final VirtualLeafRecord<TestKey, TestValue> dateLeaf3 = dateLeaf(4);
        final VirtualLeafRecord<TestKey, TestValue> eggplantLeaf3 = eggplantLeaf(5);
        final VirtualLeafRecord<TestKey, TestValue> figLeaf3 = figLeaf(6);
        final VirtualLeafRecord<TestKey, TestValue> grapeLeaf3 = grapeLeaf(7);
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
        final VirtualNodeCache<TestKey, TestValue> cache4 = cache;
        final VirtualLeafRecord<TestKey, TestValue> cuttlefishLeaf4 = cuttlefishLeaf(3);
        final VirtualLeafRecord<TestKey, TestValue> dogLeaf4 = dogLeaf(4);
        final VirtualLeafRecord<TestKey, TestValue> emuLeaf4 = emuLeaf(5);
        final VirtualLeafRecord<TestKey, TestValue> foxLeaf4 = foxLeaf(6);
        final VirtualLeafRecord<TestKey, TestValue> gooseLeaf4 = gooseLeaf(7);
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
        final List<VirtualNodeCache<TestKey, TestValue>> caches = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            caches.add(cache);
            nextRound();
        }
        // Try and fail to release caches 99-0 (reverse iteration order -- the oldest caches are first)
        for (int i = 99; i > 0; i--) {
            final VirtualNodeCache<TestKey, TestValue> copy = caches.get(i);
            assertThrows(IllegalStateException.class, copy::release, "cache should not be able to be released");
        }
        // Try (and hopefully succeed!) in releasing caches 0-99 (the oldest first!)
        for (int i = 0; i < 99; i++) {
            final VirtualNodeCache<TestKey, TestValue> copy = caches.get(i);
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
    void releaseDropsState() throws InterruptedException {
        final VirtualNodeCache<TestKey, TestValue> original = cache;
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(A_PATH);
        original.putLeaf(appleLeaf0);
        nextRound();

        final VirtualNodeCache<TestKey, TestValue> oneLess = cache;
        final VirtualHashRecord rootInternal0 = rootInternal();
        final VirtualLeafRecord<TestKey, TestValue> bananaLeaf1 = bananaLeaf(B_PATH);
        original.putHash(rootInternal0);
        oneLess.putLeaf(bananaLeaf1);
        nextRound();

        final VirtualNodeCache<TestKey, TestValue> latest = cache;
        final VirtualHashRecord leftInternal1 = leftInternal();
        final VirtualLeafRecord<TestKey, TestValue> cherryLeaf2 = cherryLeaf(C_PATH);
        oneLess.putHash(leftInternal1);
        latest.putLeaf(cherryLeaf2);

        // I should be able to see everything from all three versions
        assertEquals(
                appleLeaf0,
                latest.lookupLeafByKey(A_KEY, false),
                "value that was looked up should match original value");
        assertEquals(
                appleLeaf0,
                latest.lookupLeafByPath(A_PATH, false),
                "value that was looked up should match original value");
        assertEquals(
                bananaLeaf1,
                latest.lookupLeafByKey(B_KEY, false),
                "value that was looked up should match original value");
        assertEquals(
                bananaLeaf1,
                latest.lookupLeafByPath(B_PATH, false),
                "value that was looked up should match original value");
        assertEquals(
                cherryLeaf2,
                latest.lookupLeafByKey(C_KEY, false),
                "value that was looked up should match original value");
        assertEquals(
                cherryLeaf2,
                latest.lookupLeafByPath(C_PATH, false),
                "value that was looked up should match original value");
        assertEquals(
                rootInternal0.hash(),
                latest.lookupHashByPath(ROOT_PATH, false),
                "value that was looked up should match original value");
        assertEquals(
                leftInternal1.hash(),
                latest.lookupHashByPath(LEFT_PATH, false),
                "value that was looked up should match original value");

        // After releasing the original, I should only see what was available in the latest two
        original.release();

        assertEventuallyDoesNotThrow(
                () -> {
                    assertNull(latest.lookupLeafByKey(A_KEY, false), "no leaf should be found");
                    assertNull(latest.lookupLeafByPath(A_PATH, false), "no leaf should be found");
                    assertEquals(
                            bananaLeaf1,
                            latest.lookupLeafByKey(B_KEY, false),
                            "value that was looked up should match original value");
                    assertEquals(
                            bananaLeaf1,
                            latest.lookupLeafByPath(B_PATH, false),
                            "value that was looked up should match original value");
                    assertEquals(
                            cherryLeaf2,
                            latest.lookupLeafByKey(C_KEY, false),
                            "value that was looked up should match original value");
                    assertEquals(
                            cherryLeaf2,
                            latest.lookupLeafByPath(C_PATH, false),
                            "value that was looked up should match original value");
                    assertNull(latest.lookupHashByPath(ROOT_PATH, false), "no leaf should be found");
                    assertEquals(
                            leftInternal1.hash(),
                            latest.lookupHashByPath(LEFT_PATH, false),
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
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        nextRound();
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache;
        nextRound();
        final VirtualNodeCache<TestKey, TestValue> cache2 = cache;
        nextRound();
        nextRound();
        final VirtualNodeCache<TestKey, TestValue> cache4 = cache;
        nextRound();
        final VirtualNodeCache<TestKey, TestValue> cache5 = cache;
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
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(1);
        final VirtualLeafRecord<TestKey, TestValue> bananaLeaf0 = bananaLeaf(2);
        cache0.putLeaf(appleLeaf0);
        cache0.putLeaf(bananaLeaf0);

        nextRound();

        // Change A from APPLE -> AARDVARK
        // Delete B (BANANA)
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache;
        final VirtualLeafRecord<TestKey, TestValue> aardvarkLeaf1 = aardvarkLeaf(1);
        cache1.putLeaf(aardvarkLeaf1);
        cache1.deleteLeaf(bananaLeaf(2));

        cache0.seal();
        cache1.seal();

        // Merge cache 0 into cache 1
        cache0.merge();

        // Aardvark should be in cache 1
        assertEquals(
                aardvarkLeaf1,
                cache1.lookupLeafByPath(1, false),
                "value that was looked up should match original value");
        assertEquals(
                aardvarkLeaf1,
                cache1.lookupLeafByKey(A_KEY, false),
                "value that was looked up should match original value");
        // And Banana should be deleted
        assertEquals(
                DELETED_LEAF_RECORD,
                cache1.lookupLeafByPath(2, false),
                "value that was looked up should match original value");
        assertEquals(
                DELETED_LEAF_RECORD,
                cache1.lookupLeafByKey(B_KEY, false),
                "value that was looked up should match original value");

        final List<VirtualLeafRecord<TestKey, TestValue>> dirtyLeaves =
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
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        nextRound();
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache;
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf1 = appleLeaf(1);
        cache1.putLeaf(appleLeaf1);

        cache0.seal();
        cache1.seal();

        // Merge cache 0 into cache 1
        cache0.merge();

        // Apple should be in cache 1
        assertEquals(
                appleLeaf1, cache1.lookupLeafByPath(1, false), "value that was looked up should match original value");
        assertEquals(
                appleLeaf1,
                cache1.lookupLeafByKey(A_KEY, false),
                "value that was looked up should match original value");
    }

    /**
     * If the old cache had a mutation but the new cache did not, then the mutation should be
     * available as part of the new cache.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Lifecycle")})
    @DisplayName("Merging a mutation into a cache without one retains the mutation")
    void mergeOldMutationIntoNewerCacheWithNoMutation() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(1);
        cache0.putLeaf(appleLeaf0);

        nextRound();
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache;

        cache0.seal();
        cache1.seal();

        // Merge cache 0 into cache 1
        cache0.merge();

        // Apple should be in cache 1
        assertEquals(
                appleLeaf0, cache1.lookupLeafByPath(1, false), "value that was looked up should match original value");
        assertEquals(
                appleLeaf0,
                cache1.lookupLeafByKey(A_KEY, false),
                "value that was looked up should match original value");
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
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(3);
        final VirtualLeafRecord<TestKey, TestValue> bananaLeaf0 = bananaLeaf(2);
        final VirtualLeafRecord<TestKey, TestValue> cherryLeaf0 = cherryLeaf(4);
        cache0.putLeaf(appleLeaf0); // new leaf
        cache0.putLeaf(bananaLeaf0); // new leaf
        cache0.putLeaf(cherryLeaf0); // new leaf
        final List<TestValue> expected0 = new ArrayList<>(asList(APPLE, BANANA, CHERRY, null, null, null, null));
        validateCache(cache0, expected0);
        nextRound();
        cache0.putHash(rootInternal());
        cache0.putHash(leftInternal());

        // Update, Remove, and add some items
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache;
        final VirtualLeafRecord<TestKey, TestValue> aardvarkLeaf1 = aardvarkLeaf(3);
        final VirtualLeafRecord<TestKey, TestValue> bananaLeaf1 = bananaLeaf(5);
        final VirtualLeafRecord<TestKey, TestValue> dateLeaf1 = dateLeaf(6);
        cache1.putLeaf(aardvarkLeaf1); // updated leaf
        cache1.putLeaf(bananaLeaf1); // moved leaf
        cache1.putLeaf(dateLeaf1); // new leaf
        final List<TestValue> expected1 = new ArrayList<>(asList(AARDVARK, BANANA, CHERRY, DATE, null, null, null));
        validateCache(cache0, expected0);
        validateCache(cache1, expected1);
        nextRound();
        cache1.putHash(rootInternal());
        cache1.putHash(leftInternal());
        cache1.putHash(rightInternal());

        // Update, Remove, and add some of the same items and some new ones
        final VirtualNodeCache<TestKey, TestValue> cache2 = cache;
        final VirtualLeafRecord<TestKey, TestValue> cuttlefishLeaf2 = cuttlefishLeaf(4);
        final VirtualLeafRecord<TestKey, TestValue> aardvarkLeaf2 = aardvarkLeaf(7);
        final VirtualLeafRecord<TestKey, TestValue> eggplantLeaf2 = eggplantLeaf(8);
        cache2.putLeaf(aardvarkLeaf2); // moved leaf
        cache2.putLeaf(cuttlefishLeaf2); // updated leaf
        cache2.putLeaf(eggplantLeaf2); // new leaf
        final List<TestValue> expected2 =
                new ArrayList<>(asList(AARDVARK, BANANA, CUTTLEFISH, DATE, EGGPLANT, null, null));
        validateCache(cache0, expected0);
        validateCache(cache1, expected1);
        validateCache(cache2, expected2);
        nextRound();
        cache2.putHash(rootInternal());
        cache2.putHash(leftInternal());
        cache2.putHash(rightInternal());
        cache2.putHash(leftLeftInternal());

        // And a cache 3 just for fun
        final VirtualNodeCache<TestKey, TestValue> cache3 = cache;
        final VirtualLeafRecord<TestKey, TestValue> cuttlefishLeaf3 = cuttlefishLeaf(9);
        final VirtualLeafRecord<TestKey, TestValue> figLeaf3 = figLeaf(10);
        final VirtualLeafRecord<TestKey, TestValue> emuLeaf3 = emuLeaf(8);
        cache3.putLeaf(cuttlefishLeaf3); // moved leaf
        cache3.putLeaf(figLeaf3); // new leaf
        cache3.putLeaf(emuLeaf3); // updated leaf
        final List<TestValue> expected3 = new ArrayList<>(asList(AARDVARK, BANANA, CUTTLEFISH, DATE, EMU, FIG, null));
        validateCache(cache0, expected0);
        validateCache(cache1, expected1);
        validateCache(cache2, expected2);
        validateCache(cache3, expected3);

        // Now merge cache0 into cache1.
        cache0.seal();
        cache1.seal();
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
        // Add all the leaves. They go in at path totalMutationCount+leafIndex because internal nodes go first.
        final int totalMutationCount = 100_000;
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        for (int i = 0; i < totalMutationCount; i++) {
            cache0.putLeaf(new VirtualLeafRecord<>(totalMutationCount + i, new TestKey(i), new TestValue("Value" + i)));
        }

        nextRound();

        // Now add the internal nodes for round 0
        for (int i = 0; i < totalMutationCount; i++) {
            final byte[] internalBytes = ("Internal" + i).getBytes(StandardCharsets.UTF_8);
            cache0.putHash(new VirtualHashRecord(i, CRYPTO.digestSync(internalBytes)));
        }

        // Replace the first 60,000 leaves with newer versions
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache;
        final int nextMutationCount = 60_000;
        for (int i = 0; i < nextMutationCount; i++) {
            cache1.putLeaf(new VirtualLeafRecord<>(
                    totalMutationCount + i, new TestKey(i), new TestValue("OverriddenValue" + i)));
        }

        nextRound();

        // Now override the first 60,000 internal nodes with newer versions
        for (int i = 0; i < nextMutationCount; i++) {
            final byte[] internalBytes = ("OverriddenInternal" + i).getBytes(StandardCharsets.UTF_8);
            cache1.putHash(new VirtualHashRecord(i, CRYPTO.digestSync(internalBytes)));
        }

        nextRound();

        // Merge cache 0 into cache 1
        cache0.seal();
        cache1.seal();
        cache0.merge();

        // Verify everything
        final AtomicInteger index = new AtomicInteger(0);
        cache1.dirtyLeavesForFlush(totalMutationCount, totalMutationCount * 2)
                .sorted(Comparator.comparingLong(VirtualLeafRecord::getPath))
                .forEach(rec -> {
                    final int i = index.getAndIncrement();
                    assertEquals(
                            totalMutationCount + i, rec.getPath(), "path should be one greater than mutation count");
                    assertEquals(new TestKey(i), rec.getKey(), "key should match expected");
                    if (i < nextMutationCount) {
                        assertEquals(
                                new TestValue("OverriddenValue" + i),
                                rec.getValue(),
                                "value should have the expected data");
                    } else {
                        assertEquals(new TestValue("Value" + i), rec.getValue(), "value should have the expected data");
                    }
                });

        index.set(0);
        cache1.dirtyHashesForFlush(totalMutationCount * 2).forEach(rec -> {
            final int i = index.getAndIncrement();
            assertEquals(i, rec.path(), "path should match index");
            if (i < nextMutationCount) { // mutated internal nodes
                final byte[] internalBytes = ("OverriddenInternal" + i).getBytes(StandardCharsets.UTF_8);
                assertEquals(CRYPTO.digestSync(internalBytes), rec.hash(), "hashes should match");
            } else if (i < totalMutationCount) { // original internal nodes
                final byte[] internalBytes = ("Internal" + i).getBytes(StandardCharsets.UTF_8);
                assertEquals(CRYPTO.digestSync(internalBytes), rec.hash(), "hashes should match");
            } else { // leaf nodes, not hashed
                assertNull(rec.hash(), "hashes should be null");
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
        final ConcurrentLinkedDeque<VirtualNodeCache<TestKey, TestValue>> caches = new ConcurrentLinkedDeque<>();

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
                        cache.putLeaf(new VirtualLeafRecord<>(id, new TestKey(id), new TestValue(r + ":" + id)));
                    } else {
                        cache.deleteLeaf(new VirtualLeafRecord<>(id, new TestKey(id), null));
                    }
                }
                final VirtualNodeCache<TestKey, TestValue> done = cache;
                nextRound();
                done.seal();
                caches.addLast(done);
            }
        });
        creatorThread.setDaemon(true);
        creatorThread.setUncaughtExceptionHandler((t, e) -> creatorThreadException.set(e));
        creatorThread.start();

        // I will have another thread that performs releases. Every 100us it will attempt to release a cache
        final AtomicReference<VirtualNodeCache<TestKey, TestValue>> toRelease = new AtomicReference<>();
        final AtomicReference<Throwable> releaseThreadException = new AtomicReference<>();
        final Thread releaseThread = new Thread(() -> {
            long startNanos = System.nanoTime();
            while (!stop.get()) {
                final long currentNanos = System.nanoTime();
                if (currentNanos - startNanos >= 100_000) {
                    final VirtualNodeCache<TestKey, TestValue> cache = toRelease.getAndSet(null);
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
                final Iterator<VirtualNodeCache<TestKey, TestValue>> itr = caches.iterator();
                if (itr.hasNext()) {
                    final VirtualNodeCache<TestKey, TestValue> toMerge = itr.next();
                    if (itr.hasNext()) {
                        itr.remove(); // get rid of "toMerge". It is to be merged into the next.
                        toMerge.merge();
                        final VirtualNodeCache<TestKey, TestValue> merged = itr.next();
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
    // Tests for leaves
    // ----------------------------------------------------------------------

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("NPE when putting a null internal")
    void puttingANullInternalLeadsToNPE() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        nextRound();
        assertThrows(NullPointerException.class, () -> cache0.putHash(null), "null shouldn't be accepted into cache");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Put an internal")
    void putAnInternal() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        nextRound();

        final VirtualHashRecord root0 = rootInternal();
        cache0.putHash(root0);
        assertEquals(
                root0.hash(),
                cache0.lookupHashByPath(ROOT_PATH, false),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Put the same internal twice")
    void putAnInternalTwice() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        nextRound();

        final VirtualHashRecord root0 = rootInternal();
        cache0.putHash(root0);
        assertEquals(
                root0.hash(),
                cache0.lookupHashByPath(ROOT_PATH, false),
                "value that was looked up should match original value");
        cache0.putHash(root0);
        assertEquals(
                root0.hash(),
                cache0.lookupHashByPath(ROOT_PATH, false),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Put the same internal twice with different hashes")
    void putAnInternalTwiceWithDifferentHashes() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        nextRound();

        final VirtualHashRecord root1 = new VirtualHashRecord(
                rootInternal().path(), CRYPTO.digestSync("Root 1".getBytes(StandardCharsets.UTF_8)));
        cache0.putHash(root1);
        final VirtualHashRecord root2 = new VirtualHashRecord(
                rootInternal().path(), CRYPTO.digestSync("Root 2".getBytes(StandardCharsets.UTF_8)));
        cache0.putHash(root2);
        assertEquals(
                root2.hash(),
                cache0.lookupHashByPath(ROOT_PATH, false),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Sealed cache cannot delete internals")
    void deletingAnInternalWithSealedCacheThrows() {
        cache.seal();
        assertThrows(
                MutabilityException.class, () -> cache.deleteHash(ROOT_PATH), "can't delete after cache is sealed");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Deleting a non-existent internal is OK")
    void deletingAnInternalThatDoesNotExistIsOK() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        nextRound();

        final VirtualHashRecord root0 = rootInternal();
        final VirtualHashRecord left0 = leftInternal();
        final VirtualHashRecord right0 = rightInternal();
        cache0.putHash(root0);
        cache0.putHash(left0);
        cache0.putHash(right0);

        assertNull(cache.lookupHashByPath(LEFT_LEFT_PATH, false), "no node should be found");
        cache.deleteHash(LEFT_LEFT_PATH);
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Deleting an existent internal in the same cache version is OK")
    void deletingAnInternalThatDoesExistIsOK() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        nextRound();

        final VirtualHashRecord root0 = rootInternal();
        final VirtualHashRecord left0 = leftInternal();
        final VirtualHashRecord right0 = rightInternal();
        cache0.putHash(root0);
        cache0.putHash(left0);
        cache0.putHash(right0);

        assertEquals(
                right0.hash(),
                cache0.lookupHashByPath(RIGHT_PATH, false),
                "value that was looked up should match original value");
        cache.deleteHash(RIGHT_PATH);
        assertEquals(
                DELETED_HASH,
                cache.lookupHashByPath(RIGHT_PATH, false),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Deleting an existent internal across cache versions is OK")
    void deletingAnInternalThatDoesExistInOlderCacheIsOK() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        nextRound();

        final VirtualHashRecord root0 = rootInternal();
        final VirtualHashRecord left0 = leftInternal();
        final VirtualHashRecord right0 = rightInternal();
        cache0.putHash(root0);
        cache0.putHash(left0);
        cache0.putHash(right0);
        nextRound();
        cache0.seal();

        cache.deleteHash(RIGHT_PATH);
        assertEquals(
                DELETED_HASH,
                cache.lookupHashByPath(RIGHT_PATH, false),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Exception when getting an internal on a released cache")
    void gettingAnInternalOnReleasedCacheThrows() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        nextRound();

        final VirtualHashRecord root0 = rootInternal();
        cache0.putHash(root0);
        cache0.seal();
        cache0.release();

        assertNull(cache0.lookupLeafByPath(ROOT_PATH, false), "should not be able to look up value on destroyed cache");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Getting non-existent internals returns null")
    void gettingANonExistentInternalGivesNull() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        nextRound();
        assertNull(cache0.lookupHashByPath(100, false), "value should not be found");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("Lookup by internal with forModify works across versions")
    void getInternalWithForModify() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        nextRound();

        final VirtualHashRecord root0 = rootInternal();
        final VirtualHashRecord left0 = leftInternal();
        final VirtualHashRecord right0 = new VirtualHashRecord(
                rightInternal().path(), CRYPTO.digestSync("Right 0".getBytes(StandardCharsets.UTF_8)));
        cache0.putHash(root0);
        cache0.putHash(left0);
        cache0.putHash(right0);
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache;
        nextRound();

        final VirtualNodeCache<TestKey, TestValue> cache2 = cache;
        nextRound();
        final Hash right1 = cache2.lookupHashByPath(RIGHT_PATH, true);
        // assertNotNull(right1, "value should have been found");

        assertEquals(
                right0.hash(),
                cache1.lookupHashByPath(RIGHT_PATH, false),
                "value that was looked up should match original value");
        assertEquals(
                right1,
                cache2.lookupHashByPath(RIGHT_PATH, false),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Internal")})
    @DisplayName("dirtyInternals() cannot be called if the dirtyInternals are still mutable")
    void dirtyInternalsMustButImmutableToCreateASortedStream() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        nextRound();

        final VirtualHashRecord root0 = rootInternal();
        final VirtualHashRecord left0 = leftInternal();
        final VirtualHashRecord right0 = rightInternal();
        cache0.putHash(root0);
        cache0.putHash(left0);
        cache0.putHash(right0);
        nextRound(); // seals dirtyLeaves, but not dirtyInternals
        // assertThrows(MutabilityException.class, () -> cache0.dirtyHashes(2, 4),
        //				"shouldn't be able to call method on immutable cache");
        assertThrows(
                MutabilityException.class,
                () -> cache0.dirtyHashesForFlush(right0.path()),
                "shouldn't be able to call method on immutable cache");
    }

    // ----------------------------------------------------------------------
    // Tests for internals
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
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        assertEquals(
                appleLeaf0,
                cache.lookupLeafByKey(A_KEY, false),
                "value that was looked up should match original value");
        assertEquals(
                appleLeaf0,
                cache.lookupLeafByPath(A_PATH, false),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Put the same leaf twice")
    void putALeafTwice() {
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        cache.putLeaf(appleLeaf0);
        assertEquals(
                appleLeaf0,
                cache.lookupLeafByKey(A_KEY, false),
                "value that was looked up should match original value");
        assertEquals(
                appleLeaf0,
                cache.lookupLeafByPath(A_PATH, false),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Put the same leaf twice with different values")
    void putALeafTwiceWithDifferentValues() {
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf1 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf1);
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf2 = appleLeaf(A_PATH);
        appleLeaf2.setValue(new TestValue("second"));
        cache.putLeaf(appleLeaf2);
        assertEquals(
                appleLeaf2,
                cache.lookupLeafByKey(A_KEY, false),
                "value that was looked up should match original value");
        assertEquals(
                appleLeaf2,
                cache.lookupLeafByPath(A_PATH, false),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Put the same leaf twice with different paths")
    void putALeafTwiceWithDifferentPaths() {
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        cache.clearLeafPath(A_PATH);
        appleLeaf0.setPath(100);
        cache.putLeaf(appleLeaf0);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByPath(A_PATH, false),
                "value that was looked up should match original value");
        assertEquals(
                appleLeaf0,
                cache.lookupLeafByKey(A_KEY, false),
                "value that was looked up should match original value");
        assertEquals(
                appleLeaf0, cache.lookupLeafByPath(100, false), "value that was looked up should match original value");
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
        final VirtualLeafRecord<TestKey, TestValue> virtualRecord = appleLeaf(1);
        assertThrows(
                MutabilityException.class,
                () -> cache.deleteLeaf(virtualRecord),
                "delete should not be possible on immutable cache");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Deleting a non-existent node is OK")
    void deletingALeafThatDoesNotExistIsOK() {
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(1);
        assertNull(cache.lookupLeafByKey(appleLeaf0.getKey(), false), "no value should be found");
        cache.deleteLeaf(appleLeaf0);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByKey(appleLeaf0.getKey(), false),
                "value that was looked up should match original value");
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByPath(1, false),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Deleting an existent node in the same cache version is OK")
    void deletingALeafThatDoesExistIsOK() {
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        assertEquals(
                appleLeaf0,
                cache.lookupLeafByKey(appleLeaf0.getKey(), false),
                "value that was looked up should match original value");
        assertEquals(
                appleLeaf0,
                cache.lookupLeafByPath(A_PATH, false),
                "value that was looked up should match original value");
        cache.deleteLeaf(appleLeaf0);
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByKey(appleLeaf0.getKey(), false),
                "value that was looked up should match original value");
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByPath(A_PATH, false),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Deleting an existent node across cache versions is OK")
    void deletingALeafThatDoesExistInOlderCacheIsOK() {
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        final VirtualNodeCache<TestKey, TestValue> original = cache;
        nextRound();
        original.seal();

        final VirtualLeafRecord<TestKey, TestValue> appleLeaf1 = appleLeaf(A_PATH);
        cache.deleteLeaf(appleLeaf1);

        assertEquals(
                appleLeaf0,
                original.lookupLeafByKey(appleLeaf0.getKey(), false),
                "value that was looked up should match original value");
        assertEquals(
                appleLeaf0,
                original.lookupLeafByPath(A_PATH, false),
                "value that was looked up should match original value");
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByKey(appleLeaf0.getKey(), false),
                "value that was looked up should match original value");
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByPath(A_PATH, false),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Clearing a leaf path results in a deletion tombstone for that path")
    void clearingALeafPath() {
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        final VirtualNodeCache<TestKey, TestValue> original = cache;
        nextRound();
        original.seal();

        cache.clearLeafPath(A_PATH);

        assertEquals(
                appleLeaf0,
                original.lookupLeafByKey(appleLeaf0.getKey(), false),
                "value that was looked up should match original value");
        assertEquals(
                appleLeaf0,
                original.lookupLeafByPath(A_PATH, false),
                "value that was looked up should match original value");
        assertEquals(
                appleLeaf0,
                cache.lookupLeafByKey(appleLeaf0.getKey(), false),
                "value that was looked up should match original value");
        assertEquals(
                DELETED_LEAF_RECORD,
                cache.lookupLeafByPath(A_PATH, false),
                "value that was looked up should match original value");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("NPE When getting a leaf with a null key")
    void gettingALeafWithANullKeyLeadsToNPE() {
        assertThrows(
                NullPointerException.class,
                () -> cache.lookupLeafByKey(null, false),
                "should not be able to look up a null key");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Exception when getting a leaf on a destroyed cache")
    void gettingALeafOnDestroyedCacheThrows() {
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache.putLeaf(appleLeaf0);
        cache.seal();
        cache.release();

        assertNull(cache.lookupLeafByKey(A_KEY, false), "shouldn't be able to key on destroyed cache");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Getting non-existent leaves returns null")
    void gettingANonExistentLeafGivesNull() {
        assertNull(cache.lookupLeafByKey(new TestKey(BOGUS_KEY_ID), false), "no value should be found");
        assertNull(cache.lookupLeafByPath(100, false), "no value should be found");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Lookup by leaf key with forModify works across versions")
    void getLeafByKeyWithForModify() {
        // Add APPLE to the original cache
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache0.putLeaf(appleLeaf0);
        nextRound();

        // Create a new cache and use lookupLeafByKey with forModify true
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache0.copy();
        final VirtualLeafRecord<TestKey, TestValue> aardvarkLeaf1 = cache1.lookupLeafByKey(A_KEY, true);
        assertNotNull(aardvarkLeaf1, "value should have been found");
        aardvarkLeaf1.setValue(AARDVARK);
        assertEquals(appleLeaf0, cache0.lookupLeafByKey(A_KEY, false), "value should match original");
        assertEquals(appleLeaf0, cache0.lookupLeafByPath(A_PATH, false), "lookup by path should work");
        assertEquals(aardvarkLeaf1, cache1.lookupLeafByKey(A_KEY, false), "value should match original");
        assertEquals(aardvarkLeaf1, cache1.lookupLeafByPath(A_PATH, false), "lookup by path should work");

        // Create a new cache. Release the original cache, and then lookup APPLE by A_PATH.
        final VirtualNodeCache<TestKey, TestValue> cache2 = cache1.copy();
        cache0.release();
        assertEquals(aardvarkLeaf1, cache2.lookupLeafByKey(A_KEY, false), "value should match aardvark");
        assertEquals(aardvarkLeaf1, cache2.lookupLeafByPath(A_PATH, false), "lookup by path should work");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("Lookup by leaf path with forModify works across versions")
    void getLeafByPathWithForModify() {
        // Add APPLE to the original cache
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(A_PATH);
        cache0.putLeaf(appleLeaf0);
        nextRound();

        // Create a new cache and use lookupLeafByKey with forModify true
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache0.copy();
        final VirtualLeafRecord<TestKey, TestValue> aardvarkLeaf1 = cache1.lookupLeafByPath(A_PATH, true);
        assertNotNull(aardvarkLeaf1, "value should have been found");
        aardvarkLeaf1.setValue(AARDVARK);
        assertEquals(appleLeaf0, cache0.lookupLeafByKey(A_KEY, false), "value should match original");
        assertEquals(appleLeaf0, cache0.lookupLeafByPath(A_PATH, false), "lookup by path should work");
        assertEquals(aardvarkLeaf1, cache1.lookupLeafByKey(A_KEY, false), "value should match original");
        assertEquals(aardvarkLeaf1, cache1.lookupLeafByPath(A_PATH, false), "lookup by path should work");

        // Create a new cache. Release the original cache, and then lookup APPLE by A_PATH.
        final VirtualNodeCache<TestKey, TestValue> cache2 = cache1.copy();
        cache0.release();
        assertEquals(aardvarkLeaf1, cache2.lookupLeafByKey(A_KEY, false), "value should match aardvark");
        assertEquals(aardvarkLeaf1, cache2.lookupLeafByPath(A_PATH, false), "lookup by path should work");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("dirtyLeaves() cannot be called if the dirtyLeaves are still mutable")
    void dirtyLeavesMustButImmutableToCreateASortedStream() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = cache;
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(A_PATH);
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

        final VirtualMap<TestKey, TestValue> map0 = createMap();
        final VirtualNodeCache<TestKey, TestValue> cache0 = getRoot(map0).getCache();
        // A: [C (APPLE), v0]
        // B: [C (BANANA), v0]
        // C: [C+U (CHERRY, CUTTLEFISH), v0]
        // D: [C+U (DATE, DOG), v0]
        // E: [C+U (EGGPLANT, EMU), v0]
        // F: [C (FIG), v0]
        map0.put(A_KEY, APPLE);
        map0.put(B_KEY, BANANA);
        map0.put(C_KEY, CHERRY);
        map0.put(C_KEY, CUTTLEFISH);
        map0.put(D_KEY, DATE);
        map0.put(D_KEY, DOG);
        map0.put(E_KEY, EGGPLANT);
        map0.put(E_KEY, EMU);
        map0.put(F_KEY, FIG);

        final VirtualMap<TestKey, TestValue> map1 = map0.copy();
        final VirtualNodeCache<TestKey, TestValue> cache1 = getRoot(map1).getCache();

        // A: [U+D (AARDVARK), v1]
        // B: [D, v1]
        // C: [U (COMET), v1]
        // D: [U+D (DRACO), v1]
        // F: [U (FOX), v1]
        // G: [C (GRAPE), v1]
        map1.put(A_KEY, AARDVARK);
        map1.remove(A_KEY);
        map1.remove(B_KEY);
        map1.put(C_KEY, COMET);
        map1.put(D_KEY, DRACO);
        map1.remove(D_KEY);
        map1.put(F_KEY, FOX);
        map1.put(G_KEY, GRAPE);

        final VirtualMap<TestKey, TestValue> map2 = map1.copy();
        final VirtualNodeCache<TestKey, TestValue> cache2 = getRoot(map2).getCache();

        // A: [D, v2]
        // B: [C+U (BEAR, BLASTOFF), v2]
        // C: [D, v2]
        // D: [C+U (DISCIPLINE, DENMARK), v2]
        // E: [D, v2]
        // F: [D, v2]
        // G: [U (GOOSE), v2]
        map2.remove(A_KEY);
        map2.put(B_KEY, BEAR);
        map2.put(B_KEY, BLASTOFF);
        map2.remove(C_KEY);
        map2.put(D_KEY, DISCIPLINE);
        map2.put(D_KEY, DENMARK);
        map2.remove(E_KEY);
        map2.remove(F_KEY);
        map2.put(G_KEY, GOOSE);

        final VirtualMap<TestKey, TestValue> map3 = map2.copy();
        final VirtualNodeCache<TestKey, TestValue> cache3 = getRoot(map3).getCache();

        // B: [D, v3]
        // C: [C+U+D (CHEMISTRY, CHAD), v3]
        // E: [C+U (EXOPLANET, ECOLOGY), v3]
        // F: [C (FORCE), v3]
        // G: [U (GRAVITY), v3]
        map3.remove(B_KEY);
        map3.put(C_KEY, CHEMISTRY);
        map3.put(C_KEY, CHAD);
        map3.remove(C_KEY);
        map3.put(E_KEY, EXOPLANET);
        map3.put(E_KEY, ECOLOGY);
        map3.put(F_KEY, FORCE);
        map3.put(G_KEY, GRAVITY);

        // One last copy, so we can get the dirty leaves without an exception
        final VirtualMap<TestKey, TestValue> map4 = map3.copy();

        final List<VirtualLeafRecord<TestKey, TestValue>> deletedLeaves0 =
                cache0.deletedLeaves().collect(Collectors.toList());
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
        cache.putHash(rootInternal());
        final VirtualNodeCache<TestKey, TestValue> snapshot =
                cache.snapshot().snapshot().snapshot();
        assertEquals(appleLeaf(1), snapshot.lookupLeafByKey(A_KEY, false), "value should match expected");
        assertEquals(appleLeaf(1), snapshot.lookupLeafByPath(1, false), "value should match expected");
        assertEquals(bananaLeaf(2), snapshot.lookupLeafByKey(B_KEY, false), "value should match expected");
        assertEquals(bananaLeaf(2), snapshot.lookupLeafByPath(2, false), "value should match expected");
    }

    @Test
    @DisplayName("cache can produce multiple snapshots")
    void multipleSnapshotsFromOneCache() {
        cache.putLeaf(appleLeaf(1));
        cache.putLeaf(bananaLeaf(2));
        cache.copy();
        cache.putHash(rootInternal());
        VirtualNodeCache<TestKey, TestValue> snapshot;
        for (int i = 0; i < 10; i++) {
            snapshot = cache.snapshot();
            assertEquals(appleLeaf(1), snapshot.lookupLeafByKey(A_KEY, false), "value should match expected");
            assertEquals(appleLeaf(1), snapshot.lookupLeafByPath(1, false), "value should match expected");
            assertEquals(bananaLeaf(2), snapshot.lookupLeafByKey(B_KEY, false), "value should match expected");
            assertEquals(bananaLeaf(2), snapshot.lookupLeafByPath(2, false), "value should match expected");
        }
    }

    /**
     * This test validates that serialization happens only on snapshot instances.
     * if we try to serialize a non-snapshot instance, then an
     * {@link IllegalStateException} is thrown.
     *
     * @throws IOException
     * 		In case of error
     */
    @Test
    @DisplayName("serialize should only be called on a snapshot")
    void serializingBeforeSnapshot() throws IOException {
        try (final InputOutputStream ioStream = new InputOutputStream()) {
            final Exception exception = assertThrows(
                    IllegalStateException.class,
                    () -> ioStream.getOutput().writeSerializable(cache, true),
                    "Serialization should be done on a snapshot");
            assertEquals(
                    "Trying to serialize a non-snapshot instance",
                    exception.getMessage(),
                    "Serialization is only valid on snapshot");
        }
    }

    /**
     * This test validates that the snapshot of a copy is serialized and
     * deserialized correctly by matching its contents against the
     * original cache (cache0).
     *
     * @throws IOException
     * 		In case of error
     */
    @Test
    @DisplayName("Deserialized cache matches its non-copied original cache")
    void serializeAndDeserialize() throws IOException {
        final List<CacheInfo> caches = createCaches();
        final List<CacheInfo> snapshots = caches.stream()
                .map(original ->
                        new CacheInfo(original.cache.snapshot(), original.firstLeafPath, original.lastLeafPath))
                .toList();

        final List<CacheInfo> expectedCaches = createCaches();
        for (int i = 0; i < snapshots.size(); i++) {
            final CacheInfo snapshot = snapshots.get(i);
            final CacheInfo expected = expectedCaches.get(i);

            try (final InputOutputStream ioStream = new InputOutputStream()) {
                ioStream.getOutput().writeSerializable(snapshot.cache, true);
                ioStream.startReading();

                final VirtualNodeCache<TestKey, TestValue> deserializedCache =
                        ioStream.getInput().readSerializable();
                final CacheInfo deserializedInfo =
                        new CacheInfo(deserializedCache, snapshot.firstLeafPath, snapshot.lastLeafPath);
                validateSnapshot(expected, deserializedInfo, i);
            }
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
        final VirtualNodeCache<TestKey, TestValue> cache0 = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
        final VirtualLeafRecord<TestKey, TestValue> appleLeaf0 = appleLeaf(1);
        final VirtualLeafRecord<TestKey, TestValue> bananaLeaf0 = bananaLeaf(2);
        cache0.putLeaf(appleLeaf0);
        cache0.putLeaf(bananaLeaf0);
        // Move A to path 3 and add D at 4.
        cache0.clearLeafPath(appleLeaf0.getPath());
        appleLeaf0.setPath(3);
        cache0.putLeaf(appleLeaf0);
        final VirtualLeafRecord<TestKey, TestValue> cherryLeaf0 = cherryLeaf(4);
        cache0.putLeaf(cherryLeaf0);
        // Move B to 5 and put D at 6
        cache0.clearLeafPath(bananaLeaf0.getPath());
        bananaLeaf0.setPath(5);
        cache0.putLeaf(bananaLeaf0);
        final VirtualLeafRecord<TestKey, TestValue> dateLeaf0 = dateLeaf(6);
        cache0.putLeaf(dateLeaf0);
        // Move A to 7 and put E at 8
        cache0.clearLeafPath(appleLeaf0.getPath());
        appleLeaf0.setPath(7);
        cache0.putLeaf(appleLeaf0);
        final VirtualLeafRecord<TestKey, TestValue> eggplantLeaf0 = eggplantLeaf(8);
        cache0.putLeaf(eggplantLeaf0);
        // Move C to 9 and put F at 10
        cache0.clearLeafPath(cherryLeaf0.getPath());
        cherryLeaf0.setPath(9);
        cache0.putLeaf(cherryLeaf0);
        final VirtualLeafRecord<TestKey, TestValue> figLeaf0 = figLeaf(10);
        cache0.putLeaf(figLeaf0);
        // Move B to 11 and put G at 12
        cache0.clearLeafPath(bananaLeaf0.getPath());
        bananaLeaf0.setPath(11);
        cache0.putLeaf(bananaLeaf0);
        final VirtualLeafRecord<TestKey, TestValue> grapeLeaf0 = grapeLeaf(12);
        cache0.putLeaf(grapeLeaf0);
        // Create the copy and add the root internals and hash everything
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache0.copy();

        final VirtualHashRecord appleLeaf0int =
                new VirtualHashRecord(appleLeaf0.getPath(), CRYPTO.digestSync(appleLeaf0));
        final VirtualHashRecord bananaLeaf0int =
                new VirtualHashRecord(bananaLeaf0.getPath(), CRYPTO.digestSync(bananaLeaf0));
        final VirtualHashRecord cherryLeaf0int =
                new VirtualHashRecord(cherryLeaf0.getPath(), CRYPTO.digestSync(cherryLeaf0));
        final VirtualHashRecord dateLeaf0int = new VirtualHashRecord(dateLeaf0.getPath(), CRYPTO.digestSync(dateLeaf0));
        final VirtualHashRecord eggplantLeaf0int =
                new VirtualHashRecord(eggplantLeaf0.getPath(), CRYPTO.digestSync(eggplantLeaf0));
        final VirtualHashRecord figLeaf0int = new VirtualHashRecord(figLeaf0.getPath(), CRYPTO.digestSync(figLeaf0));
        final VirtualHashRecord grapeLeaf0int =
                new VirtualHashRecord(grapeLeaf0.getPath(), CRYPTO.digestSync(grapeLeaf0));

        final VirtualHashRecord leftRight0 =
                new VirtualHashRecord(leftRightInternal().path(), digest(cherryLeaf0int, figLeaf0int));
        final VirtualHashRecord leftLeft0 =
                new VirtualHashRecord(leftLeftInternal().path(), digest(appleLeaf0int, eggplantLeaf0int));
        final VirtualHashRecord rightLeft0 =
                new VirtualHashRecord(rightLeftInternal().path(), digest(bananaLeaf0int, grapeLeaf0int));
        final VirtualHashRecord right0 =
                new VirtualHashRecord(rightInternal().path(), digest(rightLeft0, dateLeaf0int));
        final VirtualHashRecord left0 = new VirtualHashRecord(leftInternal().path(), digest(leftLeft0, leftRight0));
        final VirtualHashRecord root0 = new VirtualHashRecord(rootInternal().path(), digest(left0, right0));

        cache0.putHash(appleLeaf0int);
        cache0.putHash(eggplantLeaf0int);
        cache0.putHash(leftLeft0);
        cache0.putHash(figLeaf0int);
        cache0.putHash(cherryLeaf0int);
        cache0.putHash(leftRight0);
        cache0.putHash(left0);
        cache0.putHash(bananaLeaf0int);
        cache0.putHash(grapeLeaf0int);
        cache0.putHash(rightLeft0);
        cache0.putHash(dateLeaf0int);
        cache0.putHash(right0);
        cache0.putHash(root0);

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
        final VirtualLeafRecord<TestKey, TestValue> cherryLeaf1 = cache1.lookupLeafByKey(C_KEY, true);
        assert cherryLeaf1 != null;
        cherryLeaf1.setValue(CUTTLEFISH);
        // Delete B and move G
        cache1.deleteLeaf(bananaLeaf0);
        cache1.deleteHash(5);
        final VirtualLeafRecord<TestKey, TestValue> grapeLeaf1 = cache1.lookupLeafByKey(G_KEY, true);
        assert grapeLeaf1 != null;
        grapeLeaf1.setPath(5);
        cache1.putLeaf(grapeLeaf1);
        cache1.clearLeafPath(12);
        // Make a copy and hash
        final VirtualNodeCache<TestKey, TestValue> cache2 = cache1.copy();
        final VirtualHashRecord cherryLeaf1int =
                new VirtualHashRecord(cherryLeaf1.getPath(), CRYPTO.digestSync(cherryLeaf1));
        cache1.putHash(cherryLeaf1int);
        VirtualHashRecord leftRight1 = new VirtualHashRecord(leftRight0.path(), digest(cherryLeaf1int, figLeaf0int));
        cache1.putHash(leftRight1);
        VirtualHashRecord left1 = new VirtualHashRecord(left0.path(), digest(leftLeft0, leftRight1));
        cache1.putHash(left1);
        final VirtualHashRecord grapeLeaf1int =
                new VirtualHashRecord(grapeLeaf1.getPath(), CRYPTO.digestSync(grapeLeaf1));
        cache1.putHash(grapeLeaf1int);
        VirtualHashRecord right1 = new VirtualHashRecord(right0.path(), digest(grapeLeaf1int, dateLeaf0int));
        cache1.putHash(right1);
        VirtualHashRecord root1 = new VirtualHashRecord(root0.path(), digest(left1, right1));
        cache1.putHash(root1);

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
        final VirtualLeafRecord<TestKey, TestValue> dateLeaf2 = cache2.lookupLeafByKey(D_KEY, true);
        assert dateLeaf2 != null;
        dateLeaf2.setValue(DOG);
        // Delete A and move F into A's place and C into leftRight's place
        cache2.deleteLeaf(appleLeaf0);
        final VirtualLeafRecord<TestKey, TestValue> figLeaf2 = cache2.lookupLeafByKey(F_KEY, true);
        assert figLeaf2 != null;
        figLeaf2.setPath(appleLeaf0.getPath());
        cache2.putLeaf(figLeaf2);
        cache2.deleteHash(leftRight1.path());
        final VirtualLeafRecord<TestKey, TestValue> cherryLeaf2 = cache2.lookupLeafByKey(C_KEY, true);
        assert cherryLeaf2 != null;
        cherryLeaf2.setPath(leftRight1.path());
        cache2.putLeaf(cherryLeaf2);
        // Delete E and move F into leftLeft's place
        cache2.deleteLeaf(eggplantLeaf0);
        cache2.deleteHash(leftLeft0.path());
        figLeaf2.setPath(leftLeft0.path());
        cache2.putLeaf(figLeaf2);
        // Finally, add B and move F back down to where it was
        final VirtualLeafRecord<TestKey, TestValue> bananaLeaf2 = bananaLeaf(8);
        cache2.putLeaf(bananaLeaf2);
        figLeaf2.setPath(7);
        cache2.putLeaf(figLeaf2);
        // And we don't hash this one or make a copy.

        // Verify the contents of cache0 are as expected
        assertEquals(dateLeaf0, cache0.lookupLeafByKey(D_KEY, false), "value should match original");
        assertEquals(dateLeaf0, cache0.lookupLeafByPath(6, false), "value should match original");
        assertEquals(appleLeaf0, cache0.lookupLeafByKey(A_KEY, false), "value should match original");
        assertEquals(appleLeaf0, cache0.lookupLeafByPath(7, false), "value should match original");
        assertEquals(eggplantLeaf0, cache0.lookupLeafByKey(E_KEY, false), "value should match original");
        assertEquals(eggplantLeaf0, cache0.lookupLeafByPath(8, false), "value should match original");
        assertEquals(cherryLeaf0, cache0.lookupLeafByKey(C_KEY, false), "value should match original");
        assertEquals(cherryLeaf0, cache0.lookupLeafByPath(9, false), "value should match original");
        assertEquals(figLeaf0, cache0.lookupLeafByKey(F_KEY, false), "value should match original");
        assertEquals(figLeaf0, cache0.lookupLeafByPath(10, false), "value should match original");
        assertEquals(root0.hash(), cache0.lookupHashByPath(0, false), "value should match original");
        assertEquals(left0.hash(), cache0.lookupHashByPath(1, false), "value should match original");
        assertEquals(right0.hash(), cache0.lookupHashByPath(2, false), "value should match original");
        assertEquals(leftLeft0.hash(), cache0.lookupHashByPath(3, false), "value should match original");
        assertEquals(leftRight0.hash(), cache0.lookupHashByPath(4, false), "value should match original");
        assertEquals(rightLeft0.hash(), cache0.lookupHashByPath(5, false), "value should match original");

        // Verify the contents of cache1 are as expected
        assertEquals(grapeLeaf1, cache1.lookupLeafByKey(G_KEY, false), "value should match original");
        assertEquals(grapeLeaf1, cache1.lookupLeafByPath(5, false), "value should match original");
        assertEquals(dateLeaf0, cache1.lookupLeafByKey(D_KEY, false), "value should match original");
        assertEquals(dateLeaf0, cache1.lookupLeafByPath(6, false), "value should match original");
        assertEquals(appleLeaf0, cache1.lookupLeafByKey(A_KEY, false), "value should match original");
        assertEquals(appleLeaf0, cache1.lookupLeafByPath(7, false), "value should match original");
        assertEquals(eggplantLeaf0, cache1.lookupLeafByKey(E_KEY, false), "value should match original");
        assertEquals(eggplantLeaf0, cache1.lookupLeafByPath(8, false), "value should match original");
        assertEquals(cherryLeaf1, cache1.lookupLeafByKey(C_KEY, false), "value should match original");
        assertEquals(cherryLeaf1, cache1.lookupLeafByPath(9, false), "value should match original");
        assertEquals(figLeaf0, cache1.lookupLeafByKey(F_KEY, false), "value should match original");
        assertEquals(figLeaf0, cache1.lookupLeafByPath(10, false), "value should match original");
        assertEquals(DELETED_LEAF_RECORD, cache1.lookupLeafByKey(B_KEY, false), "value should be deleted");
        assertEquals(DELETED_LEAF_RECORD, cache1.lookupLeafByPath(11, false), "value should be deleted");
        assertEquals(root1.hash(), cache1.lookupHashByPath(0, false), "value should match original");
        assertEquals(left1.hash(), cache1.lookupHashByPath(1, false), "value should match original");
        assertEquals(right1.hash(), cache1.lookupHashByPath(2, false), "value should match original");
        assertEquals(leftLeft0.hash(), cache1.lookupHashByPath(3, false), "value should match original");
        assertEquals(leftRight1.hash(), cache1.lookupHashByPath(4, false), "value should match original");
        assertEquals(grapeLeaf1int.hash(), cache1.lookupHashByPath(5, false), "value should match original");

        // Verify the contents of cache2 are as expected
        assertEquals(cherryLeaf2, cache2.lookupLeafByKey(C_KEY, false), "value should match original");
        assertEquals(cherryLeaf2, cache2.lookupLeafByPath(4, false), "value should match original");
        assertEquals(grapeLeaf1, cache2.lookupLeafByKey(G_KEY, false), "value should match original");
        assertEquals(grapeLeaf1, cache2.lookupLeafByPath(5, false), "value should match original");
        assertEquals(dateLeaf2, cache2.lookupLeafByKey(D_KEY, false), "value should match original");
        assertEquals(dateLeaf2, cache2.lookupLeafByPath(6, false), "value should match original");
        assertEquals(figLeaf2, cache2.lookupLeafByKey(F_KEY, false), "value should match original");
        assertEquals(figLeaf2, cache2.lookupLeafByPath(7, false), "value should match original");
        assertEquals(bananaLeaf2, cache2.lookupLeafByKey(B_KEY, false), "value should match original");
        assertEquals(bananaLeaf2, cache2.lookupLeafByPath(8, false), "value should match original");

        assertEquals(DELETED_LEAF_RECORD, cache2.lookupLeafByKey(A_KEY, false), "value should be deleted");
        assertEquals(DELETED_LEAF_RECORD, cache2.lookupLeafByKey(E_KEY, false), "value should be deleted");

        return List.of(new CacheInfo(cache0, 6, 12), new CacheInfo(cache1, 5, 10), new CacheInfo(cache2, 4, 8));
    }

    private void validateSnapshot(final CacheInfo expected, final CacheInfo snapshot, final int iteration) {
        assertEquals(expected.firstLeafPath, snapshot.firstLeafPath, "Should have the same firstLeafPath");
        assertEquals(expected.lastLeafPath, snapshot.lastLeafPath, "Should have the same lastLeafPath");

        assertEquals(
                expected.cache.lookupLeafByKey(A_KEY, false),
                snapshot.cache.lookupLeafByKey(A_KEY, false),
                "Expected the leaf for A_KEY to match for snapshot on iteration " + iteration);
        assertEquals(
                expected.cache.lookupLeafByKey(B_KEY, false),
                snapshot.cache.lookupLeafByKey(B_KEY, false),
                "Expected the leaf for B_KEY to match for snapshot on iteration " + iteration);
        assertEquals(
                expected.cache.lookupLeafByKey(C_KEY, false),
                snapshot.cache.lookupLeafByKey(C_KEY, false),
                "Expected the leaf for C_KEY to match for snapshot on iteration " + iteration);
        assertEquals(
                expected.cache.lookupLeafByKey(D_KEY, false),
                snapshot.cache.lookupLeafByKey(D_KEY, false),
                "Expected the leaf for D_KEY to match for snapshot on iteration " + iteration);

        // Test looking up leaves by paths, including paths we have never used
        for (long j = expected.firstLeafPath; j <= expected.lastLeafPath; j++) {
            assertEquals(
                    expected.cache.lookupLeafByPath(j, false),
                    snapshot.cache.lookupLeafByPath(j, false),
                    "Unexpected leaf for path " + j + " in snapshot on iteration " + iteration);
        }

        // Test looking up internals by paths, including paths we have never used
        for (int j = 0; j < expected.firstLeafPath; j++) {
            assertEquals(
                    expected.cache.lookupHashByPath(j, false),
                    snapshot.cache.lookupHashByPath(j, false),
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
        final VirtualNodeCache<TestKey, TestValue> cache = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
        cache.putLeaf(appleLeaf(7));
        cache.putLeaf(bananaLeaf(5));
        cache.putLeaf(cherryLeaf(4));
        cache.putLeaf(dateLeaf(6));
        cache.putLeaf(eggplantLeaf(8));
        cache.seal();

        final List<VirtualLeafRecord<TestKey, TestValue>> leaves =
                cache.dirtyLeavesForHash(4, 8).toList();
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
        final VirtualNodeCache<TestKey, TestValue> cache = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
        cache.putLeaf(appleLeaf(7));
        cache.putLeaf(bananaLeaf(5));
        cache.putLeaf(cherryLeaf(4));
        cache.putLeaf(dateLeaf(6));
        cache.putLeaf(eggplantLeaf(8));

        cache.deleteLeaf(eggplantLeaf(8));
        cache.putLeaf(appleLeaf(3));
        cache.seal();

        final List<VirtualLeafRecord<TestKey, TestValue>> leaves =
                cache.dirtyLeavesForHash(3, 6).toList();
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
        final VirtualNodeCache<TestKey, TestValue> cache = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
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

        final List<VirtualLeafRecord<TestKey, TestValue>> leaves =
                cache.dirtyLeavesForFlush(-1, -1).toList();
        assertEquals(0, leaves.size(), "All leaves should be missing");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyLeaves")})
    @DisplayName("dirtyLeaves where all mutations are in the same version and some paths have hosted multiple leaves")
    void dirtyLeaves_allInSameVersionSomeDeletedPathConflict() {
        final VirtualNodeCache<TestKey, TestValue> cache = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
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

        final List<VirtualLeafRecord<TestKey, TestValue>> leaves =
                cache.dirtyLeavesForHash(2, 4).toList();
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
        final VirtualNodeCache<TestKey, TestValue> cache0 = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
        cache0.putLeaf(appleLeaf(1));

        final VirtualNodeCache<TestKey, TestValue> cache1 = cache0.copy();
        cache1.putLeaf(bananaLeaf(2));
        cache1.putLeaf(appleLeaf(3));
        cache1.putLeaf(cherryLeaf(4));

        final VirtualNodeCache<TestKey, TestValue> cache2 = cache1.copy();
        cache2.putLeaf(bananaLeaf(5));
        cache2.putLeaf(dateLeaf(6));
        cache2.putLeaf(appleLeaf(7));
        cache2.putLeaf(eggplantLeaf(8));

        cache0.seal();
        cache1.seal();
        cache2.seal();

        cache0.merge();
        cache1.merge();

        final Set<VirtualLeafRecord<TestKey, TestValue>> leaves =
                cache2.dirtyLeavesForFlush(4, 8).collect(Collectors.toSet());
        assertEquals(5, leaves.size(), "All leaves should be dirty");
        assertEquals(Set.of(cherryLeaf(4), bananaLeaf(5), dateLeaf(6), appleLeaf(7), eggplantLeaf(8)), leaves);
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyLeaves")})
    @DisplayName("dirtyLeaves where mutations are across versions and some are deleted")
    void dirtyLeaves_differentVersionsSomeDeleted() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
        cache0.putLeaf(appleLeaf(1));

        final VirtualNodeCache<TestKey, TestValue> cache1 = cache0.copy();
        cache1.putLeaf(bananaLeaf(2));
        cache1.putLeaf(appleLeaf(3));
        cache1.deleteLeaf(appleLeaf(3));
        cache1.putLeaf(figLeaf(3));
        cache1.putLeaf(cherryLeaf(4));

        final VirtualNodeCache<TestKey, TestValue> cache2 = cache1.copy();
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

        final Set<VirtualLeafRecord<TestKey, TestValue>> leaves =
                cache2.dirtyLeavesForFlush(3, 6).collect(Collectors.toSet());
        assertEquals(4, leaves.size(), "Some leaves should be dirty");
        assertEquals(Set.of(figLeaf(3), eggplantLeaf(4), dateLeaf(5), grapeLeaf(6)), leaves);
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyLeaves")})
    @DisplayName("dirtyLeaves where mutations are across versions and all are deleted")
    void dirtyLeaves_differentVersionsAllDeleted() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
        cache0.putLeaf(appleLeaf(1));
        cache0.putLeaf(bananaLeaf(2));
        cache0.putLeaf(appleLeaf(3));
        cache0.putLeaf(cherryLeaf(4));
        cache0.deleteLeaf(appleLeaf(3));
        cache0.putLeaf(cherryLeaf(1));

        final VirtualNodeCache<TestKey, TestValue> cache1 = cache0.copy();
        cache1.putLeaf(cherryLeaf(3));
        cache1.putLeaf(dateLeaf(4));
        cache1.deleteLeaf(bananaLeaf(2));
        cache1.putLeaf(dateLeaf(2));
        cache1.putLeaf(cherryLeaf(1));

        final VirtualNodeCache<TestKey, TestValue> cache2 = cache1.copy();
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

        final List<VirtualLeafRecord<TestKey, TestValue>> leaves =
                cache2.dirtyLeavesForFlush(-1, -1).toList();
        assertEquals(0, leaves.size(), "All leaves should be deleted");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyInternals")})
    @DisplayName("dirtyInternals where all mutations are in the same version and none are deleted")
    void dirtyInternals_allInSameVersionNoneDeleted() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
        cache0.copy(); // Needed until #3842 is fixed

        cache0.putHash(rootInternal());
        cache0.putHash(leftInternal());
        cache0.putHash(rightInternal());
        cache0.putHash(leftLeftInternal());
        cache0.putHash(leftRightInternal());
        cache0.putHash(rightLeftInternal());
        cache0.seal();

        final List<VirtualHashRecord> internals = cache0.dirtyHashesForFlush(12).toList();
        assertEquals(6, internals.size(), "All internals should be dirty");
        assertEquals(rootInternal(), internals.get(0), "Unexpected internal");
        assertEquals(leftInternal(), internals.get(1), "Unexpected internal");
        assertEquals(rightInternal(), internals.get(2), "Unexpected internal");
        assertEquals(leftLeftInternal(), internals.get(3), "Unexpected internal");
        assertEquals(leftRightInternal(), internals.get(4), "Unexpected internal");
        assertEquals(rightLeftInternal(), internals.get(5), "Unexpected internal");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyInternals")})
    @DisplayName("dirtyInternals where mutations are across versions and none are deleted")
    void dirtyInternals_differentVersionsNoneDeleted() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache0.copy();
        cache0.putHash(rootInternal());
        cache0.putHash(leftInternal());
        cache0.putHash(rightInternal());
        cache1.copy(); // Needed until #3842 is fixed
        cache1.putHash(leftLeftInternal());
        cache1.putHash(leftRightInternal());
        cache1.putHash(rightLeftInternal());
        cache0.seal();
        cache1.seal();
        cache0.merge();

        final List<VirtualHashRecord> internals = cache1.dirtyHashesForFlush(12).toList();
        assertEquals(6, internals.size(), "All internals should be dirty");
        assertEquals(
                Set.of(
                        rootInternal(),
                        leftInternal(),
                        rightInternal(),
                        leftLeftInternal(),
                        leftRightInternal(),
                        rightLeftInternal()),
                new HashSet<>(internals),
                "All internals should be dirty");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyInternals")})
    @DisplayName("dirtyInternals where mutations are across versions and some are deleted")
    void dirtyInternals_differentVersionsSomeDeleted() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache0.copy();
        cache0.putHash(rootInternal());
        cache0.putHash(leftInternal());
        cache0.putHash(rightInternal());
        cache1.deleteHash(2);

        final VirtualNodeCache<TestKey, TestValue> cache2 = cache1.copy();
        cache1.putHash(rightInternal());
        cache1.putHash(leftLeftInternal());
        cache1.putHash(leftRightInternal());
        cache2.deleteHash(4);
        cache2.deleteHash(3);

        cache2.copy(); // Needed until #3842 is fixed
        cache2.putHash(leftLeftInternal());
        cache2.putHash(leftRightInternal());
        cache2.putHash(rightLeftInternal());

        cache0.seal();
        cache1.seal();
        cache2.seal();
        cache0.merge();
        cache1.merge();

        final List<VirtualHashRecord> internals = cache2.dirtyHashesForFlush(12).toList();
        assertEquals(6, internals.size(), "All internals should be dirty");
        assertEquals(
                Set.of(
                        rootInternal(),
                        leftInternal(),
                        rightInternal(),
                        leftLeftInternal(),
                        leftRightInternal(),
                        rightLeftInternal()),
                new HashSet<>(internals),
                "All internals should be dirty");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyInternals")})
    @DisplayName("dirtyInternals where mutations are across versions and all are deleted")
    void dirtyInternals_differentVersionsAllDeleted() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
        final VirtualNodeCache<TestKey, TestValue> cache1 = cache0.copy();
        cache0.putHash(rootInternal());
        cache0.putHash(leftInternal());
        cache0.putHash(rightInternal());
        cache0.putHash(leftLeftInternal());
        cache0.putHash(leftRightInternal());
        cache0.putHash(rightLeftInternal());
        cache1.deleteHash(6);
        cache1.deleteHash(5);
        cache1.deleteHash(4);

        final VirtualNodeCache<TestKey, TestValue> cache2 = cache1.copy();
        cache1.putHash(leftLeftInternal());
        cache2.deleteHash(4);
        cache2.deleteHash(3);
        cache2.deleteHash(2);
        cache2.deleteHash(1);
        cache2.deleteHash(0);

        cache2.copy();
        cache0.seal();
        cache1.seal();
        cache2.seal();
        cache0.merge();
        cache1.merge();

        final List<VirtualHashRecord> internals = cache2.dirtyHashesForFlush(-1).toList();
        assertEquals(0, internals.size(), "No internals should be dirty");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("DirtyLeaves")})
    @DisplayName("dirtyLeaves for hashing and flushes do not affect each other")
    void dirtyLeaves_flushesAndHashing() {
        final VirtualNodeCache<TestKey, TestValue> cache0 = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
        cache0.putLeaf(appleLeaf(1));
        cache0.putLeaf(bananaLeaf(2));

        final VirtualNodeCache<TestKey, TestValue> cache1 = cache0.copy();
        cache0.seal();
        cache1.deleteLeaf(appleLeaf(1));
        cache1.putLeaf(appleLeaf(3));
        cache1.putLeaf(cherryLeaf(4));

        // Hash version 0
        final List<VirtualLeafRecord<TestKey, TestValue>> dirtyLeaves0H =
                cache0.dirtyLeavesForHash(1, 2).toList();
        assertEquals(List.of(appleLeaf(1), bananaLeaf(2)), dirtyLeaves0H);

        cache1.copy();
        cache1.seal();

        // Hash version 1
        final List<VirtualLeafRecord<TestKey, TestValue>> dirtyLeaves1 =
                cache1.dirtyLeavesForHash(2, 4).toList();
        assertEquals(List.of(appleLeaf(3), cherryLeaf(4)), dirtyLeaves1);

        // Flush version 0
        final Set<VirtualLeafRecord<TestKey, TestValue>> dirtyLeaves0F =
                cache0.dirtyLeavesForFlush(1, 2).collect(Collectors.toSet());
        assertEquals(Set.of(appleLeaf(1), bananaLeaf(2)), dirtyLeaves0F);
    }

    // ----------------------------------------------------------------------
    // Test Utility methods
    // ----------------------------------------------------------------------

    private TestValue lookupValue(final VirtualNodeCache<TestKey, TestValue> cache, final TestKey key) {
        final VirtualLeafRecord<TestKey, TestValue> leaf = cache.lookupLeafByKey(key, false);
        return leaf == null ? null : leaf.getValue();
    }

    private void validateCache(final VirtualNodeCache<TestKey, TestValue> cache, final List<TestValue> expected) {
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

    private void validateLeaves(
            final VirtualNodeCache<TestKey, TestValue> cache,
            final long firstLeafPath,
            final List<VirtualLeafRecord<TestKey, TestValue>> leaves) {
        long expectedPath = firstLeafPath;
        for (final VirtualLeafRecord<TestKey, TestValue> leaf : leaves) {
            assertEquals(expectedPath, leaf.getPath(), "path should match expected path");
            assertEquals(
                    leaf,
                    cache.lookupLeafByPath(leaf.getPath(), false),
                    "value that was looked up should match original value");
            assertEquals(
                    leaf,
                    cache.lookupLeafByKey(leaf.getKey(), false),
                    "value that was looked up should match original value");
            expectedPath++;
        }
    }

    private void validateDirtyLeaves(
            final List<VirtualLeafRecord<TestKey, TestValue>> expected,
            final Stream<VirtualLeafRecord<TestKey, TestValue>> stream) {
        final List<VirtualLeafRecord<TestKey, TestValue>> dirty = stream.toList();
        assertEquals(expected.size(), dirty.size(), "dirtyLeaves did not have the expected number of elements");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), dirty.get(i), "value that was looked up should match expected value");
        }
    }

    private void validateDirtyInternals(final Set<VirtualHashRecord> expected, final Stream<VirtualHashRecord> actual) {
        final List<VirtualHashRecord> dirty = actual.toList();
        assertEquals(expected.size(), dirty.size(), "dirtyInternals did not have the expected number of elements");
        for (int i = 0; i < expected.size(); i++) {
            assertTrue(expected.contains(dirty.get(i)), "unexpected value");
        }
    }

    private void validateTree(final VirtualNodeCache<TestKey, TestValue> cache, final List<?> nodes) {
        long expectedPath = 0;
        for (final Object node : nodes) {
            if (node == null) {
                // This signals that a leaf has fallen out of the cache.
                assertNull(cache.lookupLeafByPath(expectedPath, false), "no value should be found");
                assertNull(cache.lookupHashByPath(expectedPath, false), "no value should be found");
                expectedPath++;
            } else {
                if (node instanceof VirtualLeafRecord virtualLeafRecord) {
                    assertEquals(expectedPath, virtualLeafRecord.getPath(), "path should match the expected value");
                    //noinspection unchecked
                    final VirtualLeafRecord<TestKey, TestValue> leaf = (VirtualLeafRecord<TestKey, TestValue>) node;
                    assertEquals(
                            leaf,
                            cache.lookupLeafByPath(leaf.getPath(), false),
                            "value that was looked up should match original value");
                    assertEquals(
                            leaf,
                            cache.lookupLeafByKey(leaf.getKey(), false),
                            "value that was looked up should match original value");
                } else if (node instanceof VirtualHashRecord virtualHashRecord) {
                    assertEquals(
                            virtualHashRecord.hash(),
                            cache.lookupHashByPath(virtualHashRecord.path(), false),
                            "value that was looked up should match original value");
                } else {
                    throw new IllegalArgumentException("Unexpected node type: " + node.getClass());
                }
                expectedPath++;
            }
        }
    }

    private Hash digest(VirtualHashRecord left, VirtualHashRecord right) {
        final HashBuilder builder = new HashBuilder(Cryptography.DEFAULT_DIGEST_TYPE);
        builder.update(left.hash());
        builder.update(right.hash());
        return builder.build();
    }

    private void validateDeletedLeaves(
            final List<VirtualLeafRecord<TestKey, TestValue>> deletedLeaves,
            final Set<TestKey> expectedKeys,
            final String name) {

        assertEquals(expectedKeys.size(), deletedLeaves.size(), "Not enough deleted leaves in " + name);

        final Set<TestKey> keys =
                deletedLeaves.stream().map(VirtualLeafRecord::getKey).collect(Collectors.toSet());
        assertEquals(deletedLeaves.size(), keys.size(), "Two records with the same key exist in " + name);

        for (final var rec : deletedLeaves) {
            assertTrue(keys.remove(rec.getKey()), "A record does not have the expected key in " + name);
        }
    }

    private static final class CacheInfo {
        VirtualNodeCache<TestKey, TestValue> cache;
        long firstLeafPath;
        long lastLeafPath;

        CacheInfo(final VirtualNodeCache<TestKey, TestValue> cache, final long first, final long last) {
            this.cache = cache;
            this.firstLeafPath = first;
            this.lastLeafPath = last;
        }
    }
}

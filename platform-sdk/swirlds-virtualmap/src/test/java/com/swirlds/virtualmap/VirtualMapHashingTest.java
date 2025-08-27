// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.createMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("VirtualMap Hashing Tests")
class VirtualMapHashingTest {

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Hash Empty Map")
    void hashEmptyMap() {
        final VirtualMap map = createMap();
        final VirtualMap copy = map.copy();
        final Hash hash = TestMerkleCryptoFactory.getInstance().digestTreeSync(map);
        assertNotNull(hash, "hash should not be null");

        map.release();
        copy.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Hash Map With One Entry")
    void hashMapWithOneEntry() {
        final VirtualMap map = createMap();
        map.put(TestKey.charToKey('a'), new TestValue("a"), TestValueCodec.INSTANCE);
        final VirtualMap copy = map.copy();

        final Hash hash = TestMerkleCryptoFactory.getInstance().digestTreeSync(map);
        assertNotNull(hash, "hash should not be null");

        map.release();
        copy.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Hash Map With Many Entries")
    void hashMapWithManyEntries() {
        final VirtualMap map0 = createMap();
        for (int i = 0; i < 100; i++) {
            map0.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }

        final VirtualMap map1 = map0.copy();
        final Hash hash0 = TestMerkleCryptoFactory.getInstance().digestTreeSync(map0);
        assertNotNull(hash0, "hash should not be null");

        for (int i = 100; i < 200; i++) {
            map1.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }

        final VirtualMap map2 = map1.copy();
        final Hash hash1 = TestMerkleCryptoFactory.getInstance().digestTreeSync(map1);
        assertNotNull(hash1, "hash should not be null");

        final Hash hash0_2 = TestMerkleCryptoFactory.getInstance().digestTreeSync(map0);
        assertNotEquals(hash0, hash1, "hash should have changed");
        assertEquals(hash0_2, map0.getHash(), "map should still have the same hash");

        map0.release();
        map1.release();
        map2.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Embedded At Root Sync")
    void embeddedAtRootSync() {

        final VirtualMap mapA = createMap();
        for (int i = 0; i < 100; i++) {
            mapA.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }
        final VirtualMap copyA = mapA.copy();
        final Hash hashA = TestMerkleCryptoFactory.getInstance().digestTreeSync(mapA);
        assertNotNull(hashA, "hash should not be null");

        final VirtualMap mapB = createMap();
        for (int i = 0; i < 100; i++) {
            mapB.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }
        final VirtualMap copyB = mapB.copy();
        final Hash hashB = TestMerkleCryptoFactory.getInstance().digestTreeSync(mapB);
        assertEquals(hashA, hashB, "both trees should derive the same hash");

        mapA.release();
        mapB.release();
        copyA.release();
        copyB.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Embedded At Root Async")
    void embeddedAtRootAsync() throws ExecutionException, InterruptedException {

        final VirtualMap mapA = createMap();
        for (int i = 0; i < 100; i++) {
            mapA.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }
        final VirtualMap copyA = mapA.copy();
        final Hash hashA = TestMerkleCryptoFactory.getInstance().digestTreeSync(mapA);
        assertNotNull(hashA, "hash should not be null");

        final VirtualMap mapB = createMap();
        for (int i = 0; i < 100; i++) {
            mapB.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }
        final VirtualMap copyB = mapB.copy();
        final Hash hashB = TestMerkleCryptoFactory.getInstance().digestTreeSync(mapB);
        assertEquals(hashA, hashB, "both trees should derive the same hash");

        mapA.release();
        mapB.release();
        copyA.release();
        copyB.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Embedded In Tree Sync")
    void embeddedInTreeSync() {

        final VirtualMap map = createMap();
        for (int i = 0; i < 100; i++) {
            map.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }

        final MerkleInternal root = MerkleTestUtils.buildLessSimpleTreeExtended();

        // Put the map deep into the tree
        root.getChild(2)
                .asInternal()
                .getChild(1)
                .asInternal()
                .getChild(0)
                .asInternal()
                .setChild(2, map);

        final VirtualMap copy = map.copy();

        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

        assertNotNull(map.getHash(), "map should be hashed");
        assertNotNull(root.getHash(), "tree should be hashed");

        root.release();
        copy.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Embedded In Tree ASync")
    void embeddedInTreeAsync() throws ExecutionException, InterruptedException {

        final VirtualMap map = createMap();
        for (int i = 0; i < 100; i++) {
            map.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }

        final MerkleInternal root = MerkleTestUtils.buildLessSimpleTreeExtended();

        // Put the map deep into the tree
        root.getChild(2)
                .asInternal()
                .getChild(1)
                .asInternal()
                .getChild(0)
                .asInternal()
                .setChild(2, map);

        final VirtualMap copy = map.copy();

        TestMerkleCryptoFactory.getInstance().digestTreeAsync(root).get();

        assertNotNull(map.getHash(), "map should be hashed");
        assertNotNull(root.getHash(), "tree should be hashed");

        root.release();
        copy.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Multiple Maps Embedded In Tree Sync")
    void multipleMapsEmbeddedInTreeSync() {

        final VirtualMap map0 = createMap();
        for (int i = 0; i < 100; i++) {
            map0.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }

        final VirtualMap map1 = createMap();
        for (int i = 100; i < 200; i++) {
            map1.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }

        final VirtualMap map2 = createMap();
        for (int i = 200; i < 300; i++) {
            map2.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }

        final MerkleInternal root = MerkleTestUtils.buildLessSimpleTreeExtended();

        // Put the maps into the tree
        root.setChild(3, map0);
        root.getChild(2).asInternal().getChild(1).asInternal().setChild(2, map1);
        root.getChild(2)
                .asInternal()
                .getChild(1)
                .asInternal()
                .getChild(0)
                .asInternal()
                .setChild(2, map2);

        final VirtualMap copy0 = map0.copy();
        final VirtualMap copy1 = map1.copy();
        final VirtualMap copy2 = map2.copy();

        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

        assertNotNull(map0.getHash(), "map should be hashed");
        assertNotNull(map1.getHash(), "map should be hashed");
        assertNotNull(map2.getHash(), "map should be hashed");
        assertNotNull(root.getHash(), "tree should be hashed");

        root.release();
        copy0.release();
        copy1.release();
        copy2.release();
    }

    @ParameterizedTest
    @CsvSource({"1,2", "1,3", "1,4", "1,5", "2,3", "2,4", "2,5", "3,4", "3,5", "4,5"})
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Delete some tree nodes and hash")
    void hashBugFoundByPTT(long delete1, long delete2) {
        final VirtualMap map0 = createMap();
        map0.put(TestKey.longToKey(1), new TestValue(1), TestValueCodec.INSTANCE);
        map0.put(TestKey.longToKey(2), new TestValue(2), TestValueCodec.INSTANCE);
        map0.put(TestKey.longToKey(3), new TestValue(3), TestValueCodec.INSTANCE);
        map0.put(TestKey.longToKey(4), new TestValue(4), TestValueCodec.INSTANCE);
        map0.put(TestKey.longToKey(5), new TestValue(5), TestValueCodec.INSTANCE);

        map0.remove(TestKey.longToKey(delete1));
        map0.remove(TestKey.longToKey(delete2));

        final VirtualMap map1 = map0.copy();
        final Hash hash0 = TestMerkleCryptoFactory.getInstance().digestTreeSync(map0);
        assertNotNull(hash0, "hash should not be null");

        map0.release();
        map1.release();
    }

    /**
     * This test failed prior to a race condition that used to exist.
     */
    @ParameterizedTest
    @ValueSource(
            ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 16, 17, 30, 31, 32, 33, 62, 64, 120, 256, 1000, 1022, 1023, 1024
            })
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Internal node operations are properly synchronized")
    void internalNodeSynchronization(int nKeys) throws ExecutionException, InterruptedException {
        VirtualMap current = createMap();
        for (int i = 0; i < nKeys; ++i) {
            current.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }

        final VirtualMap prev = current;
        current = current.copy();
        Future<Hash> future = TestMerkleCryptoFactory.getInstance().digestTreeAsync(prev);

        for (int i = 0; i < nKeys; ++i) {
            current.remove(TestKey.longToKey(i));
        }

        future.get();
        prev.release();

        final VirtualNodeCache cache = current.getCache();
        cache.seal();
        assertEquals(
                0,
                cache.dirtyHashesForFlush(current.getMetadata().getLastLeafPath())
                        .toList()
                        .size());

        current.release();
    }

    @Test
    @DisplayName("Remove all but one elements and rehash")
    void removeLeafTwo() {
        VirtualMap map = createMap();

        try {
            map.put(TestKey.longToKey(1), new TestValue("a"), TestValueCodec.INSTANCE);
            map.put(TestKey.longToKey(2), new TestValue("b"), TestValueCodec.INSTANCE);

            VirtualMap copy = map.copy();
            final Hash hash1 = map.getHash(); // virtual root node hash
            map.release();
            map = copy;

            // Remove the second leaf, it must affect the root hash
            map.remove(TestKey.longToKey(2));

            copy = map.copy();
            final Hash hash2 = map.getHash(); // virtual root node hash
            map.release();
            map = copy;

            assertNotEquals(hash1, hash2, "Hash must be changed");

            // Remove the last leaf, it must also change the hash
            map.remove(TestKey.longToKey(1));

            copy = map.copy();
            final Hash hash3 = map.getHash(); // virtual root node hash
            map.release();
            map = copy;

            assertNotEquals(hash2, hash3, "Hash must be changed");

            // Now check the other order: remove leaf 1 first, then leaf 2

            map.put(TestKey.longToKey(1), new TestValue("a"), TestValueCodec.INSTANCE);
            map.put(TestKey.longToKey(2), new TestValue("b"), TestValueCodec.INSTANCE);

            copy = map.copy();
            final Hash hash4 = map.getHash(); // virtual root node hash
            map.release();
            map = copy;

            // Remove the first leaf, it must affect the root hash
            map.remove(TestKey.longToKey(1));

            copy = map.copy();
            final Hash hash5 = map.getHash(); // virtual root node hash
            map.release();
            map = copy;

            assertNotEquals(hash4, hash5, "Hash must be changed");

            // Remove the last leaf, it must also change the hash
            map.remove(TestKey.longToKey(2));

            copy = map.copy();
            final Hash hash6 = map.getHash(); // virtual root node hash
            map.release();
            map = copy;

            assertNotEquals(hash5, hash6, "Hash must be changed");
        } finally {
            map.release();
        }
    }
}

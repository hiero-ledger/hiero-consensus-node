// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.createMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import java.util.concurrent.ExecutionException;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("VirtualMap Hashing Tests")
class VirtualMapHashingTest {

    // FUTURE WORK tests to write:
    //  - deterministic hashing
    //  - hash does not change after fast copy
    //    - check nodes inside the tree

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Hash Empty Root")
    void hashEmptyMap() {
        final VirtualMap map = createMap();
        final VirtualMap copy = map.copy();

        final Hash hash = map.getHash();
        assertNotNull(hash, "hash should not be null");

        map.release();
        copy.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Hash Root With One Data  Entry")
    void hashMapWithOneEntry() {
        final VirtualMap map = createMap();
        map.put(TestKey.longToKey('a'), new TestValue("a"), TestValueCodec.INSTANCE);
        final VirtualMap copy = map.copy();

        assertNotNull(map.getHash(), "hash should not be null");

        map.release();
        copy.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Hash Root With Many Entries")
    void hashMapWithManyEntries() {
        final VirtualMap root0 = createMap();
        for (int i = 0; i < 100; i++) {
            root0.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }

        final VirtualMap map1 = root0.copy();
        final Hash hash0 = root0.getHash();
        assertNotNull(hash0, "hash should not be null");

        for (int i = 100; i < 200; i++) {
            map1.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }

        final VirtualMap map2 = map1.copy();
        final Hash hash1 = map1.getHash();
        assertNotNull(hash1, "hash should not be null");

        assertNotEquals(hash0, hash1, "hash should have changed");
        assertEquals(hash0, root0.getHash(), "root should still have the same hash");

        root0.release();
        map1.release();
        map2.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Embedded At Root Sync")
    void embeddedAtRootSync() {

        final VirtualMap rootA = createMap();
        for (int i = 0; i < 100; i++) {
            rootA.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }
        final VirtualMap copyA = rootA.copy();
        final Hash hashA = rootA.getHash();
        assertNotNull(hashA, "hash should not be null");

        final VirtualMap rootB = createMap();
        for (int i = 0; i < 100; i++) {
            rootB.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }
        final VirtualMap copyB = rootB.copy();
        final Hash hashB = TestMerkleCryptoFactory.getInstance().digestTreeSync(rootA);

        assertEquals(hashA, hashB, "both algorithms should derive the same hash");

        rootA.release();
        rootB.release();
        copyA.release();
        copyB.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Embedded At Root Async")
    void embeddedAtRootAsync() throws ExecutionException, InterruptedException {

        final VirtualMap rootA = createMap();
        for (int i = 0; i < 100; i++) {
            rootA.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }
        final VirtualMap copyA = rootA.copy();
        final Hash hashA = rootA.getHash();
        assertNotNull(hashA, "hash should not be null");

        final VirtualMap rootB = createMap();
        for (int i = 0; i < 100; i++) {
            rootB.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }
        final VirtualMap copyB = rootB.copy();
        final Hash hashB =
                TestMerkleCryptoFactory.getInstance().digestTreeAsync(rootA).get();

        assertEquals(hashA, hashB, "both algorithms should derive the same hash");

        rootA.release();
        rootB.release();
        copyA.release();
        copyB.release();
    }

    @ParameterizedTest
    @CsvSource({"1,2", "1,3", "1,4", "1,5", "2,3", "2,4", "2,5", "3,4", "3,5", "4,5"})
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Delete some tree nodes and hash")
    void hashBugFoundByPTT(long delete1, long delete2) {
        final VirtualMap root0 = createMap();
        root0.put(TestKey.longToKey(1), new TestValue(1), TestValueCodec.INSTANCE);
        root0.put(TestKey.longToKey(2), new TestValue(2), TestValueCodec.INSTANCE);
        root0.put(TestKey.longToKey(3), new TestValue(3), TestValueCodec.INSTANCE);
        root0.put(TestKey.longToKey(4), new TestValue(4), TestValueCodec.INSTANCE);
        root0.put(TestKey.longToKey(5), new TestValue(5), TestValueCodec.INSTANCE);

        root0.remove(TestKey.longToKey(delete1));
        root0.remove(TestKey.longToKey(delete2));

        final VirtualMap root1 = root0.copy();
        final Hash hash0 = root0.getHash();
        assertNotNull(hash0, "hash should not be null");

        root0.release();
        root1.release();
    }
}

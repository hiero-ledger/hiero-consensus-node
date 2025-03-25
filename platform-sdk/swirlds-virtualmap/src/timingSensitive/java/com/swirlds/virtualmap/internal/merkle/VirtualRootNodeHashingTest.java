// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.createRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.swirlds.common.crypto.Hash;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import com.swirlds.virtualmap.test.fixtures.DummyVirtualStateAccessor;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("VirtualRootNode Hashing Tests")
class VirtualRootNodeHashingTest {

    // FUTURE WORK tests to write:
    //  - deterministic hashing
    //  - hash does not change after fast copy
    //    - check nodes inside the tree

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Hash Empty Root")
    void hashEmptyMap() {
        final VirtualRootNode root = createRoot();
        final VirtualRootNode copy = root.copy();

        final Hash hash = root.getHash();
        assertNotNull(hash, "hash should not be null");

        final Hash expectedHash = new VirtualHasher().emptyRootHash();
        assertEquals(expectedHash, hash, "empty root hash value didn't match expectations");

        root.release();
        copy.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Hash Root With One Entry")
    void hashMapWithOneEntry() {
        final VirtualRootNode root = createRoot();
        root.put(TestKey.longToKey('a'), new TestValue("a"), TestValueCodec.INSTANCE);
        final VirtualRootNode copy = root.copy();

        assertNotNull(root.getHash(), "hash should not be null");

        root.release();
        copy.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Hash Root With Many Entries")
    void hashMapWithManyEntries() {
        final VirtualRootNode root0 = createRoot();
        for (int i = 0; i < 100; i++) {
            root0.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }

        final VirtualRootNode root1 = root0.copy();
        root1.postInit(new DummyVirtualStateAccessor());
        final Hash hash0 = root0.getHash();
        assertNotNull(hash0, "hash should not be null");

        for (int i = 100; i < 200; i++) {
            root1.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }

        final VirtualRootNode root2 = root1.copy();
        root2.postInit(new DummyVirtualStateAccessor());
        final Hash hash1 = root1.getHash();
        assertNotNull(hash1, "hash should not be null");

        assertNotEquals(hash0, hash1, "hash should have changed");
        assertEquals(hash0, root0.getHash(), "root should still have the same hash");

        root0.release();
        root1.release();
        root2.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Embedded At Root Sync")
    void embeddedAtRootSync() {

        final VirtualRootNode rootA = createRoot();
        for (int i = 0; i < 100; i++) {
            rootA.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }
        final VirtualRootNode copyA = rootA.copy();
        final Hash hashA = rootA.getHash();
        assertNotNull(hashA, "hash should not be null");

        final VirtualRootNode rootB = createRoot();
        for (int i = 0; i < 100; i++) {
            rootB.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }
        final VirtualRootNode copyB = rootB.copy();
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

        final VirtualRootNode rootA = createRoot();
        for (int i = 0; i < 100; i++) {
            rootA.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }
        final VirtualRootNode copyA = rootA.copy();
        final Hash hashA = rootA.getHash();
        assertNotNull(hashA, "hash should not be null");

        final VirtualRootNode rootB = createRoot();
        for (int i = 0; i < 100; i++) {
            rootB.put(TestKey.longToKey(i), new TestValue(Integer.toString(i)), TestValueCodec.INSTANCE);
        }
        final VirtualRootNode copyB = rootB.copy();
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
        final VirtualRootNode root0 = createRoot();
        root0.put(TestKey.longToKey(1), new TestValue(1), TestValueCodec.INSTANCE);
        root0.put(TestKey.longToKey(2), new TestValue(2), TestValueCodec.INSTANCE);
        root0.put(TestKey.longToKey(3), new TestValue(3), TestValueCodec.INSTANCE);
        root0.put(TestKey.longToKey(4), new TestValue(4), TestValueCodec.INSTANCE);
        root0.put(TestKey.longToKey(5), new TestValue(5), TestValueCodec.INSTANCE);

        root0.remove(TestKey.longToKey(delete1), null);
        root0.remove(TestKey.longToKey(delete2), null);

        final VirtualRootNode root1 = root0.copy();
        final Hash hash0 = root0.getHash();
        assertNotNull(hash0, "hash should not be null");

        root0.release();
        root1.release();
    }
}

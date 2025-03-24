// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.createRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.virtualmap.test.fixtures.DummyVirtualStateAccessor;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import java.util.concurrent.ExecutionException;
import org.hiero.consensus.model.crypto.Hash;
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
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        final VirtualRootNode<TestKey, TestValue> copy = root.copy();

        final Hash hash = root.getHash();
        assertNotNull(hash, "hash should not be null");

        final MerkleInternal expected = new InternalWithBasicHash();
        final Hash expectedHash = TestMerkleCryptoFactory.getInstance().digestTreeSync(expected);
        assertEquals(expectedHash, hash, "empty root hash value didn't match expectations");

        root.release();
        copy.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Hash Root With One Entry")
    void hashMapWithOneEntry() {
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        root.put(new TestKey('a'), new TestValue("a"));
        final VirtualRootNode<TestKey, TestValue> copy = root.copy();

        assertNotNull(root.getHash(), "hash should not be null");

        root.release();
        copy.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Hash Root With Many Entries")
    void hashMapWithManyEntries() {
        final VirtualRootNode<TestKey, TestValue> root0 = createRoot();
        for (int i = 0; i < 100; i++) {
            root0.put(new TestKey(i), new TestValue(Integer.toString(i)));
        }

        final VirtualRootNode<TestKey, TestValue> root1 = root0.copy();
        root1.postInit(new DummyVirtualStateAccessor());
        final Hash hash0 = root0.getHash();
        assertNotNull(hash0, "hash should not be null");

        for (int i = 100; i < 200; i++) {
            root1.put(new TestKey(i), new TestValue(Integer.toString(i)));
        }

        final VirtualRootNode<TestKey, TestValue> root2 = root1.copy();
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

        final VirtualRootNode<TestKey, TestValue> rootA = createRoot();
        for (int i = 0; i < 100; i++) {
            rootA.put(new TestKey(i), new TestValue(Integer.toString(i)));
        }
        final VirtualRootNode<TestKey, TestValue> copyA = rootA.copy();
        final Hash hashA = rootA.getHash();
        assertNotNull(hashA, "hash should not be null");

        final VirtualRootNode<TestKey, TestValue> rootB = createRoot();
        for (int i = 0; i < 100; i++) {
            rootB.put(new TestKey(i), new TestValue(Integer.toString(i)));
        }
        final VirtualRootNode<TestKey, TestValue> copyB = rootB.copy();
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

        final VirtualRootNode<TestKey, TestValue> rootA = createRoot();
        for (int i = 0; i < 100; i++) {
            rootA.put(new TestKey(i), new TestValue(Integer.toString(i)));
        }
        final VirtualRootNode<TestKey, TestValue> copyA = rootA.copy();
        final Hash hashA = rootA.getHash();
        assertNotNull(hashA, "hash should not be null");

        final VirtualRootNode<TestKey, TestValue> rootB = createRoot();
        for (int i = 0; i < 100; i++) {
            rootB.put(new TestKey(i), new TestValue(Integer.toString(i)));
        }
        final VirtualRootNode<TestKey, TestValue> copyB = rootB.copy();
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
        final VirtualRootNode<TestKey, TestValue> root0 = createRoot();
        root0.put(new TestKey(1), new TestValue(1));
        root0.put(new TestKey(2), new TestValue(2));
        root0.put(new TestKey(3), new TestValue(3));
        root0.put(new TestKey(4), new TestValue(4));
        root0.put(new TestKey(5), new TestValue(5));

        root0.remove(new TestKey(delete1));
        root0.remove(new TestKey(delete2));

        final VirtualRootNode<TestKey, TestValue> root1 = root0.copy();
        final Hash hash0 = root0.getHash();
        assertNotNull(hash0, "hash should not be null");

        root0.release();
        root1.release();
    }

    /**
     * A simple merkle internal implementation used to validate basic VM hashing
     */
    private static class InternalWithBasicHash extends PartialBinaryMerkleInternal implements MerkleInternal {
        @Override
        public long getClassId() {
            return VirtualRootNode.CLASS_ID;
        }

        @Override
        public int getVersion() {
            return VirtualRootNode.ClassVersion.CURRENT_VERSION;
        }

        @Override
        public MerkleInternal copy() {
            return null;
        }
    }
}

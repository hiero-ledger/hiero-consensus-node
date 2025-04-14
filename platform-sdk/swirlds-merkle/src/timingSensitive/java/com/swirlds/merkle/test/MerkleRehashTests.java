// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import static com.swirlds.common.merkle.utility.MerkleUtils.invalidateTree;
import static com.swirlds.common.merkle.utility.MerkleUtils.rehashTree;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.generateRandomTree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.exceptions.FailedRehashException;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.common.merkle.impl.PartialNaryMerkleInternal;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleNode;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

@DisplayName("Merkle Rehash Tests")
class MerkleRehashTests {

    /**
     * If the root of the tree is an internal node, add some self hashing nodes that "explode" if rehashed.
     */
    private void addSelfHashingNodes(final MerkleNode root) {
        if (!root.isLeaf()) {
            final MerkleInternal internal = root.asInternal();
            final int childCount = internal.getNumberOfChildren();

            internal.setChild(childCount, new DummySelfHashingLeaf());

            final MerkleInternal subtree = new DummySelfHashingInternal();
            subtree.setChild(0, new DummySelfHashingLeaf());
            subtree.setChild(1, new DummySelfHashingLeaf());

            internal.setChild(childCount + 1, subtree);
        }
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Invalidate Behavior")
    void invalidateBehavior() {

        for (final MerkleNode root : MerkleTestUtils.buildTreeList()) {
            if (root == null) {
                continue;
            }

            addSelfHashingNodes(root);

            root.forEachNode((final MerkleNode node) -> {
                if (node.isSelfHashing()) {
                    assertEquals(Cryptography.NULL_HASH, node.getHash(), "dummy node should have null hash");
                } else {
                    assertNull(node.getHash(), "node should have a null hash");
                }
            });

            TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

            root.forEachNode((final MerkleNode node) -> {
                assertNotNull(node.getHash(), "node should not have a null hash");
            });

            invalidateTree(root);

            root.forEachNode((final MerkleNode node) -> {
                if (node.isSelfHashing()) {
                    assertEquals(Cryptography.NULL_HASH, node.getHash(), "dummy node should have null hash");
                } else {
                    assertNull(node.getHash(), "node should have a null hash");
                }
            });
        }
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Rehash Behavior")
    void rehashBehavior() {

        for (final MerkleNode root : MerkleTestUtils.buildTreeList()) {
            if (root == null) {
                continue;
            }

            addSelfHashingNodes(root);

            root.forEachNode((final MerkleNode node) -> {
                if (node.isSelfHashing()) {
                    assertEquals(Cryptography.NULL_HASH, node.getHash(), "dummy node should have null hash");
                } else {
                    assertNull(node.getHash(), "node should have a null hash");
                }
            });

            TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

            final Map<MerkleRoute, Hash> hashes = new HashMap<>();

            root.forEachNode((final MerkleNode node) -> {
                assertNotNull(node.getHash(), "node should not have a null hash");
                hashes.put(node.getRoute(), node.getHash());
            });

            rehashTree(TestMerkleCryptoFactory.getInstance(), root);

            root.forEachNode((final MerkleNode node) -> {
                assertNotNull(node.getHash(), "node should not have a null hash");
                assertEquals(hashes.get(node.getRoute()), node.getHash(), "node should have the same hash");
                if (!node.isSelfHashing()) {
                    assertNotSame(hashes.get(node.getRoute()), node.getHash(), "hash should be a different object");
                }
            });
        }
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Failed Rehash Behavior")
    public void failedRehash() {

        DummyMerkleNode root = generateRandomTree(0, 2, 1, 1, 0, 3, 1, 0.25);
        MerkleNode child = spy(root.asInternal().getChild(0).copy());
        root.asInternal().setChild(0, child);
        when(child.getHash()).then(new Answer<Hash>() {
            private int count = 0;

            @Override
            public Hash answer(final InvocationOnMock invocation) throws Throwable {
                if (count++ < 2) {
                    return (Hash) invocation.callRealMethod();
                } else {
                    throw new RuntimeException("Test failure");
                }
            }
        });
        assertThrows(FailedRehashException.class, () -> rehashTree(TestMerkleCryptoFactory.getInstance(), root));
    }

    private static class DummySelfHashingLeaf extends PartialMerkleLeaf implements MerkleLeaf {

        @Override
        public long getClassId() {
            // unique ID just in case constructable registry discovers this object
            return 0xeca90add22ad0775L;
        }

        @Override
        public void serialize(final SerializableDataOutputStream out) throws IOException {}

        @Override
        public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {}

        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public DummySelfHashingLeaf copy() {
            throw new UnsupportedOperationException("not implemented");
        }

        @Override
        public void invalidateHash() {
            throw new UnsupportedOperationException("not implemented");
        }

        @Override
        public Hash getHash() {
            return Cryptography.NULL_HASH;
        }

        @Override
        public void setHash(final Hash hash) {
            throw new UnsupportedOperationException("not implemented");
        }

        @Override
        public boolean isSelfHashing() {
            return true;
        }
    }

    private static class DummySelfHashingInternal extends PartialNaryMerkleInternal implements MerkleInternal {

        @Override
        public long getClassId() {
            // unique ID just in case constructable registry discovers this object
            return 0xcbac5958c3d04e5bL;
        }

        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public DummySelfHashingInternal copy() {
            throw new UnsupportedOperationException("not implemented");
        }

        @Override
        public void invalidateHash() {
            throw new UnsupportedOperationException("not implemented");
        }

        @Override
        public Hash getHash() {
            return Cryptography.NULL_HASH;
        }

        @Override
        public void setHash(final Hash hash) {
            throw new UnsupportedOperationException("not implemented");
        }

        @Override
        public boolean isSelfHashing() {
            return true;
        }
    }
}

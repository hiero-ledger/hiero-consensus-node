// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.state.MutabilityException;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.impl.ComposedMerkleInternal;
import com.swirlds.common.merkle.impl.ComposedMerkleLeaf;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.merkle.impl.PartialMerkleInternal;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.common.merkle.impl.PartialNaryMerkleInternal;
import com.swirlds.common.merkle.impl.destroyable.DestroyableBinaryMerkleInternal;
import com.swirlds.common.merkle.impl.destroyable.DestroyableMerkleLeaf;
import com.swirlds.common.merkle.impl.destroyable.DestroyableNaryMerkleInternal;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteFactory;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleLeaf;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.hiero.base.constructable.ConstructableIgnored;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Sanity checks on the abstract merkle node implementations.
 */
@DisplayName("Abstract Node Tests")
class PartialNodeTests {

    /**
     * Describes how to build an implementation of {@link MerkleNode}.
     */
    private record NodeImpl<T extends MerkleNode>(String name, Supplier<T> constructor) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Some additional methods used by this test.
     */
    private interface TestNode {

        /**
         * Set node mutability status.
         */
        void setImmutable(boolean immutable);

        /**
         * Check if the destroy callback has been called.
         */
        boolean destroyCallbackInvoked();
    }

    @ConstructableIgnored
    private static class DirectLeaf extends PartialMerkleLeaf implements TestNode, MerkleLeaf {

        private boolean destroyCallbackInvoked;

        @Override
        public long getClassId() {
            return 1111;
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
        public DirectLeaf copy() {
            return null;
        }

        @Override
        public void setImmutable(final boolean immutable) {
            super.setImmutable(immutable);
        }

        @Override
        protected void destroyNode() {
            assertFalse(destroyCallbackInvoked, "should only be destroyed once");
            destroyCallbackInvoked = true;
        }

        @Override
        public boolean destroyCallbackInvoked() {
            return destroyCallbackInvoked;
        }
    }

    @ConstructableIgnored
    private static class ComposedLeaf implements ComposedMerkleLeaf, TestNode {

        private boolean destroyCallbackInvoked;

        final DestroyableMerkleLeaf merkleImpl = new DestroyableMerkleLeaf(() -> this.destroyCallbackInvoked = true);

        @Override
        public void serialize(final SerializableDataOutputStream out) throws IOException {}

        @Override
        public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {}

        @Override
        public MerkleLeaf copy() {

            return null;
        }

        @Override
        public DestroyableMerkleLeaf getMerkleImplementation() {
            return merkleImpl;
        }

        @Override
        public void setImmutable(final boolean immutable) {
            merkleImpl.setImmutable(immutable);
        }

        @Override
        public boolean destroyCallbackInvoked() {
            return destroyCallbackInvoked;
        }

        @Override
        public long getClassId() {
            return 2222;
        }

        @Override
        public int getVersion() {
            return 2;
        }
    }

    @ConstructableIgnored
    private static class DirectBinaryInternal extends PartialBinaryMerkleInternal implements TestNode, MerkleInternal {

        private boolean destroyCallbackInvoked;

        @Override
        public long getClassId() {
            return 3333;
        }

        @Override
        public int getVersion() {
            return 3;
        }

        @Override
        public DirectBinaryInternal copy() {
            return null;
        }

        @Override
        public void setImmutable(final boolean immutable) {
            super.setImmutable(immutable);
        }

        @Override
        public boolean destroyCallbackInvoked() {
            return destroyCallbackInvoked;
        }

        @Override
        protected void destroyNode() {
            destroyCallbackInvoked = true;
        }
    }

    @ConstructableIgnored
    private static class ComposedBinaryInternal implements ComposedMerkleInternal, TestNode {

        private boolean destroyCallbackInvoked;

        final DestroyableBinaryMerkleInternal merkleImpl =
                new DestroyableBinaryMerkleInternal(() -> this.destroyCallbackInvoked = true);

        @Override
        public PartialMerkleInternal getMerkleImplementation() {
            return merkleImpl;
        }

        @Override
        public void setImmutable(final boolean immutable) {
            merkleImpl.setImmutable(immutable);
        }

        @Override
        public boolean destroyCallbackInvoked() {
            return destroyCallbackInvoked;
        }

        @Override
        public MerkleInternal copy() {
            return null;
        }

        @Override
        public long getClassId() {
            return 4444;
        }

        @Override
        public int getVersion() {
            return 4;
        }
    }

    @ConstructableIgnored
    private static class DirectNaryInternal extends PartialNaryMerkleInternal implements TestNode, MerkleInternal {

        private boolean destroyCallbackInvoked;

        @Override
        public long getClassId() {
            return 5555;
        }

        @Override
        public int getVersion() {
            return 5;
        }

        @Override
        public DirectNaryInternal copy() {
            return null;
        }

        @Override
        public void setImmutable(final boolean immutable) {
            super.setImmutable(immutable);
        }

        @Override
        public boolean destroyCallbackInvoked() {
            return destroyCallbackInvoked;
        }

        @Override
        protected void destroyNode() {
            destroyCallbackInvoked = true;
        }
    }

    @ConstructableIgnored
    private static class ComposedNaryInternal implements ComposedMerkleInternal, TestNode {

        private boolean destroyCallbackInvoked;

        final DestroyableNaryMerkleInternal merkleImpl =
                new DestroyableNaryMerkleInternal(() -> this.destroyCallbackInvoked = true);

        @Override
        public PartialMerkleInternal getMerkleImplementation() {
            return merkleImpl;
        }

        @Override
        public void setImmutable(final boolean immutable) {
            merkleImpl.setImmutable(immutable);
        }

        @Override
        public boolean destroyCallbackInvoked() {
            return destroyCallbackInvoked;
        }

        @Override
        public MerkleInternal copy() {
            return null;
        }

        @Override
        public long getClassId() {
            return 6666;
        }

        @Override
        public int getVersion() {
            return 6;
        }
    }

    static Stream<Arguments> merkleNodes() {
        return Stream.of(
                Arguments.of(new NodeImpl<>("extends AbstractMerkleLeaf directly", DirectLeaf::new)),
                Arguments.of(new NodeImpl<>("extends AbstractMerkleLeaf by composition", ComposedLeaf::new)),
                Arguments.of(
                        new NodeImpl<>("extends AbstractBinaryMerkleInternal directly", DirectBinaryInternal::new)),
                Arguments.of(new NodeImpl<>(
                        "extends AbstractBinaryMerkleInternal by composition", ComposedBinaryInternal::new)),
                Arguments.of(new NodeImpl<>("extends AbstractNaryMerkleInternal directly", DirectNaryInternal::new)),
                Arguments.of(new NodeImpl<>(
                        "extends AbstractNaryMerkleInternal by composition", ComposedNaryInternal::new)));
    }

    static Stream<Arguments> merkleInternals() {
        return Stream.of(
                Arguments.of(
                        new NodeImpl<>("extends AbstractBinaryMerkleInternal directly", DirectBinaryInternal::new)),
                Arguments.of(new NodeImpl<>(
                        "extends AbstractBinaryMerkleInternal by composition", ComposedBinaryInternal::new)),
                Arguments.of(new NodeImpl<>("extends AbstractNaryMerkleInternal directly", DirectNaryInternal::new)),
                Arguments.of(new NodeImpl<>(
                        "extends AbstractNaryMerkleInternal by composition", ComposedNaryInternal::new)));
    }

    @ParameterizedTest
    @MethodSource("merkleNodes")
    @DisplayName("Hash Test")
    void hashTest(final NodeImpl<MerkleNode> nodeImpl) {
        final Cryptography cryptography = CryptographyProvider.getInstance();
        final MerkleNode node = nodeImpl.constructor.get();

        assertNull(node.getHash(), "node should start with null hash");
        final Hash hash1 = Cryptography.NULL_HASH;
        node.setHash(hash1);
        assertSame(hash1, node.getHash(), "unexpected hash");

        final byte[] bytes = new byte[100];
        final Hash hash2 = cryptography.digestSync(bytes);
        node.setHash(hash2);
        assertSame(hash2, node.getHash(), "unexpected hash");

        node.invalidateHash();
        assertNull(node.getHash(), "hash should be cleared");
    }

    @ParameterizedTest
    @MethodSource("merkleNodes")
    @DisplayName("Class ID / Version Test")
    void classIdVersionTest(final NodeImpl<MerkleNode> nodeImpl) {
        final MerkleNode node = nodeImpl.constructor.get();

        final long classId = node.getClassId();

        // By design, the class ID version of each node is in the format: class ID = NNNN, version = N
        assertTrue(classId > 0, "class ID should be a positive number");
        assertEquals(node.getVersion(), classId / 1111, "unexpected version");
    }

    @ParameterizedTest
    @MethodSource("merkleNodes")
    @DisplayName("Mutability Test")
    void mutabilityTest(final NodeImpl<MerkleNode> nodeImpl) {
        final MerkleNode node = nodeImpl.constructor.get();

        assertFalse(node.isImmutable(), "node should not be immutable");
        assertTrue(node.isMutable(), "node should be mutable");
        node.throwIfImmutable();

        ((TestNode) node).setImmutable(true);
        assertTrue(node.isImmutable(), "node should be immutable");
        assertFalse(node.isMutable(), "node should not be mutable");
        assertThrows(MutabilityException.class, node::throwIfImmutable, "node should have thrown");

        ((TestNode) node).setImmutable(false);
        assertFalse(node.isImmutable(), "node should not be immutable");
        assertTrue(node.isMutable(), "node should be mutable");
        node.throwIfImmutable();
    }

    @ParameterizedTest
    @MethodSource("merkleNodes")
    @DisplayName("Reference Count Test")
    void referenceCountTest(final NodeImpl<MerkleNode> nodeImpl) {
        MerkleNode node = nodeImpl.constructor.get();
        assertEquals(0, node.getReservationCount(), "node should have implicit reference");
        assertFalse(((TestNode) node).destroyCallbackInvoked(), "node should not be destroyed");
        assertFalse(node.isDestroyed(), "node should not be destroyed");
        node.release();
        assertTrue(((TestNode) node).destroyCallbackInvoked(), "node should be destroyed");
        assertTrue(node.isDestroyed(), "node should be destroyed");

        node = nodeImpl.constructor.get();

        node.reserve();
        assertFalse(((TestNode) node).destroyCallbackInvoked(), "node should not be destroyed");
        assertFalse(node.isDestroyed(), "node should not be destroyed");
        assertEquals(1, node.getReservationCount(), "unexpected reference count");

        node.reserve();
        assertFalse(((TestNode) node).destroyCallbackInvoked(), "node should not be destroyed");
        assertFalse(node.isDestroyed(), "node should not be destroyed");
        assertEquals(2, node.getReservationCount(), "unexpected reference count");

        node.reserve();
        assertFalse(((TestNode) node).destroyCallbackInvoked(), "node should not be destroyed");
        assertFalse(node.isDestroyed(), "node should not be destroyed");
        assertEquals(3, node.getReservationCount(), "unexpected reference count");

        node.release();
        assertFalse(((TestNode) node).destroyCallbackInvoked(), "node should not be destroyed");
        assertFalse(node.isDestroyed(), "node should not be destroyed");
        assertEquals(2, node.getReservationCount(), "unexpected reference count");

        node.release();
        assertFalse(((TestNode) node).destroyCallbackInvoked(), "node should not be destroyed");
        assertFalse(node.isDestroyed(), "node should not be destroyed");
        assertEquals(1, node.getReservationCount(), "unexpected reference count");

        node.release();
        assertTrue(((TestNode) node).destroyCallbackInvoked(), "node should be destroyed");
        assertTrue(node.isDestroyed(), "node should be destroyed");
        assertEquals(-1, node.getReservationCount(), "node should be destroyed");
    }

    @ParameterizedTest
    @MethodSource("merkleNodes")
    @DisplayName("MerkleRoute Test")
    void merkleRouteTest(final NodeImpl<MerkleNode> nodeImpl) {
        final MerkleNode node = nodeImpl.constructor.get();
        assertEquals(MerkleRouteFactory.getEmptyRoute(), node.getRoute(), "route should empty");

        final MerkleRoute route = MerkleRouteFactory.buildRoute(1, 2, 3, 4);
        node.setRoute(route);
        assertSame(route, node.getRoute(), "node should have route");
    }

    @ParameterizedTest
    @MethodSource("merkleInternals")
    @DisplayName("MerkleRoute Test")
    void setChildTest(final NodeImpl<MerkleInternal> nodeImpl) {
        final MerkleInternal node = nodeImpl.constructor.get();

        final MerkleNode A = new DummyMerkleLeaf("A");
        assertEquals(0, A.getReservationCount(), "should have an implicit reservation");

        node.setChild(0, A);
        assertEquals(1, A.getReservationCount(), "unexpected reference count");
        assertEquals(MerkleRouteFactory.buildRoute(0), A.getRoute(), "unexpected route");

        final MerkleNode B = new DummyMerkleLeaf("B");
        assertEquals(0, B.getReservationCount(), "should have an implicit reservation");
        node.setChild(0, B);

        assertTrue(A.isDestroyed(), "should have been destroyed after replacement");
        assertEquals(1, B.getReservationCount(), "unexpected reference count");
        assertEquals(MerkleRouteFactory.buildRoute(0), B.getRoute(), "unexpected route");

        node.setChild(0, null);
        assertTrue(B.isDestroyed(), "should have been destroyed after replacement");
    }

    @ParameterizedTest
    @MethodSource("merkleInternals")
    @DisplayName("addDeserializedChildren() Releases Old Children Test")
    void addDeserializedChildrenReleasesOldChildrenTest(final NodeImpl<MerkleInternal> nodeImpl) {
        final MerkleInternal node = nodeImpl.constructor.get();

        final DummyMerkleLeaf originalChild = new DummyMerkleLeaf();
        node.setChild(0, originalChild);

        final DummyMerkleLeaf newChild = new DummyMerkleLeaf();

        node.addDeserializedChildren(List.of(newChild), node.getVersion());

        assertEquals(1, newChild.getReservationCount());
        assertTrue(originalChild.isDestroyed());
    }
}

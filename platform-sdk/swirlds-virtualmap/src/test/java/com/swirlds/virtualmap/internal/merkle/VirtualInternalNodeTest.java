// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.createRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyBinaryMerkleInternal;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestKeySerializer;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueSerializer;
import com.swirlds.virtualmap.test.fixtures.VirtualTestBase;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

class VirtualInternalNodeTest extends VirtualTestBase {

    /**
     * We don't support the setChild method. This test makes sure exceptions are raised.
     */
    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("setChild is not a supported method")
    void setChildNotSupported() {
        final VirtualHashRecord virtualHashRecord = new VirtualHashRecord(1, null);
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        final VirtualInternalNode<TestKey, TestValue> internalNode = new VirtualInternalNode<>(root, virtualHashRecord);

        final DummyBinaryMerkleInternal child = new DummyBinaryMerkleInternal();
        assertThrows(
                UnsupportedOperationException.class,
                () -> internalNode.setChild(3, child),
                "setChild is not required on VirtualInternalNodes");

        assertThrows(
                UnsupportedOperationException.class,
                () -> internalNode.setChild(3, child, mock(MerkleRoute.class), false),
                "setChild is not required on VirtualInternalNodes");
    }

    /**
     * The copy method is not supported either.
     */
    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("copy method is not supported")
    void copyNotSupported() {
        final VirtualHashRecord virtualHashRecord = new VirtualHashRecord(0, null);
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        final VirtualInternalNode<TestKey, TestValue> internalNode = new VirtualInternalNode<>(root, virtualHashRecord);

        assertThrows(UnsupportedOperationException.class, internalNode::copy, "Copy is not supported");
    }

    /**
     * Tries to read the children of a root node with no children.
     */
    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("Getting a leaf outside leaf paths returns null")
    void getInvalidLeaf() {
        // This root node has no children -- no left, and no right. This is a valid use case
        // (and in fact, any internal node other than the root node will ALWAYS have both a
        // left and right child).
        final VirtualHashRecord virtualHashRecord = new VirtualHashRecord(0, null);
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        final VirtualInternalNode<TestKey, TestValue> internalNode = new VirtualInternalNode<>(root, virtualHashRecord);
        assertNull(internalNode.getChild(0), "No left child");
        assertNull(internalNode.getChild(1), "No right child");
    }

    /**
     * This is a strict binary tree, so only values 0 and 1 would ever work.
     */
    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("Getting a child that isn't 0 or 1")
    void getInvalidChild() {
        // Standard test scenario. There are valid children of this pathHashRecord. I want this so
        // that I know that under normal circumstances the call would succeed, and is only *not*
        // returning a valid because it shouldn't.
        final VirtualHashRecord virtualHashRecord = new VirtualHashRecord(3, null);
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        root.put(A_KEY, APPLE);
        root.put(B_KEY, BANANA);
        root.put(C_KEY, CHERRY);
        root.put(D_KEY, DATE);
        root.put(E_KEY, EGGPLANT);
        root.put(F_KEY, FIG);
        root.put(G_KEY, GRAPE);
        final VirtualInternalNode<TestKey, TestValue> internalNode = new VirtualInternalNode<>(root, virtualHashRecord);
        final VirtualLeafNode<TestKey, TestValue> leftChild = internalNode.getChild(0);
        final VirtualLeafNode<TestKey, TestValue> rightChild = internalNode.getChild(1);
        assertNotNull(leftChild, "child should not be null");
        assertNotNull(rightChild, "child should not be null");
        assertEquals(A_KEY, leftChild.getKey(), "key should match original");
        assertEquals(E_KEY, rightChild.getKey(), "key should match original");
        assertNull(internalNode.getChild(2), "value should be null");
    }

    @SuppressWarnings("rawtypes")
    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("Getting a child that isn't 0 or 1")
    void getInternalNodeFromCacheAndFromDisk() {
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        root.put(A_KEY, APPLE);
        root.put(B_KEY, BANANA);
        root.put(C_KEY, CHERRY);
        root.put(D_KEY, DATE);
        root.put(E_KEY, EGGPLANT);
        root.put(F_KEY, FIG);
        root.put(G_KEY, GRAPE);

        MerkleNode child = root.getChild(1);
        assertTrue(child instanceof VirtualInternalNode, "child should be an internal node");
        VirtualInternalNode internalNode = (VirtualInternalNode) child;

        assertEquals(5, ((VirtualInternalNode) internalNode.getChild(0)).getPath());
        root.getCache().release();
        assertEquals(5, ((VirtualInternalNode) internalNode.getChild(0)).getPath());
    }

    /**
     * Get a child that is on disk. For rigour, I will test getting all 7 of
     * my leaf nodes from disk.
     */
    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("Getting a child that is on disk")
    void getValidChildOnDisk() throws IOException {
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        root.getState().setFirstLeafPath(6);
        root.getState().setLastLeafPath(12);
        final List<VirtualLeafRecord<TestKey, TestValue>> leaves = List.of(
                new VirtualLeafRecord<>(6, D_KEY, DATE),
                new VirtualLeafRecord<>(7, A_KEY, APPLE),
                new VirtualLeafRecord<>(8, E_KEY, EGGPLANT),
                new VirtualLeafRecord<>(9, C_KEY, CHERRY),
                new VirtualLeafRecord<>(10, F_KEY, FIG),
                new VirtualLeafRecord<>(11, G_KEY, GRAPE),
                new VirtualLeafRecord<>(12, B_KEY, BANANA));
        root.getDataSource()
                .saveRecords(
                        6,
                        12,
                        leaves.stream().map(this::hash),
                        leaves.stream().map(r -> r.toBytes(TestKeySerializer.INSTANCE, TestValueSerializer.INSTANCE)),
                        Stream.empty());

        VirtualHashRecord virtualHashRecord = new VirtualHashRecord(2, null);
        VirtualInternalNode<TestKey, TestValue> internalNode = new VirtualInternalNode<>(root, virtualHashRecord);
        VirtualLeafNode<TestKey, TestValue> rightChildLeaf = internalNode.getChild(1);
        assertNotNull(rightChildLeaf, "value should not be null");
        assertEquals(D_KEY, rightChildLeaf.getKey(), "key should match original");

        virtualHashRecord = new VirtualHashRecord(3, null);
        internalNode = new VirtualInternalNode<>(root, virtualHashRecord);
        VirtualLeafNode<TestKey, TestValue> leftChildLeaf = internalNode.getChild(0);
        rightChildLeaf = internalNode.getChild(1);
        assertNotNull(leftChildLeaf, "value should not be null");
        assertNotNull(rightChildLeaf, "value should not be null");
        assertEquals(A_KEY, leftChildLeaf.getKey(), "key should match original");
        assertEquals(E_KEY, rightChildLeaf.getKey(), "key should match original");

        virtualHashRecord = new VirtualHashRecord(4, null);
        internalNode = new VirtualInternalNode<>(root, virtualHashRecord);
        leftChildLeaf = internalNode.getChild(0);
        rightChildLeaf = internalNode.getChild(1);
        assertNotNull(leftChildLeaf, "value should not be null");
        assertNotNull(rightChildLeaf, "value should not be null");
        assertEquals(C_KEY, leftChildLeaf.getKey(), "key should match original");
        assertEquals(F_KEY, rightChildLeaf.getKey(), "key should match original");

        virtualHashRecord = new VirtualHashRecord(5, null);
        internalNode = new VirtualInternalNode<>(root, virtualHashRecord);
        leftChildLeaf = internalNode.getChild(0);
        rightChildLeaf = internalNode.getChild(1);
        assertNotNull(leftChildLeaf, "value should not be null");
        assertNotNull(rightChildLeaf, "value should not be null");
        assertEquals(G_KEY, leftChildLeaf.getKey(), "key should match original");
        assertEquals(B_KEY, rightChildLeaf.getKey(), "key should match original");
    }

    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("Check version and class are valid")
    void getVersionAndClassId() {
        final VirtualHashRecord virtualHashRecord = new VirtualHashRecord(0, null);
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        final VirtualInternalNode<TestKey, TestValue> internalNode = new VirtualInternalNode<>(root, virtualHashRecord);
        assertEquals(0xaf2482557cfdb6bfL, internalNode.getClassId(), "Increases code coverage for getClassId");
        assertEquals(1, internalNode.getVersion(), "Increases code coverage for getVersion");
    }

    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("toString doesn't throw")
    void toStringTest() {
        // Honestly, I'm just juicing the code coverage
        final VirtualHashRecord virtualHashRecord = new VirtualHashRecord(0, null);
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        final VirtualInternalNode<TestKey, TestValue> internalNode = new VirtualInternalNode<>(root, virtualHashRecord);
        assertNotNull(internalNode.toString(), "value should not be null");
    }
}

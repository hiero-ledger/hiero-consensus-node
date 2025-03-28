// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.common.test.fixtures.crypto.CryptoRandomUtils.randomHash;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.buildLessSimpleTree;
import static com.swirlds.platform.state.signed.SignedStateComparison.mismatchedNodeIterator;
import static com.swirlds.platform.state.signed.SignedStateComparison.printMismatchedNodes;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.utility.MerkleLong;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleInternal;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleLeaf;
import com.swirlds.platform.state.signed.MismatchedNodes;
import java.util.Iterator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("SignedState Comparison Test")
class SignedStateComparisonTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Null States")
    void nullStates(final boolean deep) {
        final MerkleNode stateA = null;
        final MerkleNode stateB = null;

        final Iterator<MismatchedNodes> iterator = mismatchedNodeIterator(stateA, stateB, deep);

        assertFalse(iterator.hasNext(), "iterator should be empty");

        // There should be no exceptions thrown by printing.
        printMismatchedNodes(mismatchedNodeIterator(stateA, stateB, deep), 1000);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("State A Is Null")
    void stateAIsNull(final boolean deep) {

        final MerkleNode stateA = null;
        final MerkleNode stateB = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(stateB);

        final Iterator<MismatchedNodes> iterator = mismatchedNodeIterator(stateA, stateB, deep);

        final MismatchedNodes nodes = iterator.next();

        assertNull(nodes.nodeA(), "node A should be null");
        assertSame(nodes.nodeB(), stateB, "node B should be the root of tree B");

        assertFalse(iterator.hasNext(), "iterator should be empty");

        // There should be no exceptions thrown by printing.
        printMismatchedNodes(mismatchedNodeIterator(stateA, stateB, deep), 1000);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("State B Is Null")
    void stateBIsNull(final boolean deep) {
        final MerkleNode stateA = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(stateA);
        final MerkleNode stateB = null;

        final Iterator<MismatchedNodes> iterator = mismatchedNodeIterator(stateA, stateB, deep);

        final MismatchedNodes nodes = iterator.next();

        assertSame(nodes.nodeA(), stateA, "node A should be the root of tree A");
        assertNull(nodes.nodeB(), "node B should be null");

        assertFalse(iterator.hasNext(), "iterator should be empty");

        // There should be no exceptions thrown by printing.
        printMismatchedNodes(mismatchedNodeIterator(stateA, stateB, deep), 1000);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Matching States")
    void matchingStates(final boolean deep) {
        final MerkleNode stateA = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(stateA);
        final MerkleNode stateB = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(stateB);

        final Iterator<MismatchedNodes> iterator = mismatchedNodeIterator(stateA, stateB, deep);

        assertFalse(iterator.hasNext(), "iterator should be empty");

        // There should be no exceptions thrown by printing.
        printMismatchedNodes(mismatchedNodeIterator(stateA, stateB, deep), 1000);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Different Hashes")
    void differentHashes(final boolean deep) {
        final MerkleNode stateA = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(stateA);
        final MerkleNode stateB = buildLessSimpleTree();
        ((DummyMerkleLeaf) stateB.getNodeAtRoute(1, 0)).setValue("X");
        TestMerkleCryptoFactory.getInstance().digestTreeSync(stateB);

        final Iterator<MismatchedNodes> iterator = mismatchedNodeIterator(stateA, stateB, deep);

        MismatchedNodes nodes = iterator.next();
        assertSame(nodes.nodeA(), stateA, "nodes should match");
        assertSame(nodes.nodeB(), stateB, "nodes should match");

        nodes = iterator.next();
        assertSame(nodes.nodeA(), stateA.getNodeAtRoute(1), "nodes should match");
        assertSame(nodes.nodeB(), stateB.getNodeAtRoute(1), "nodes should match");

        nodes = iterator.next();
        assertSame(nodes.nodeA(), stateA.getNodeAtRoute(1, 0), "nodes should match");
        assertSame(nodes.nodeB(), stateB.getNodeAtRoute(1, 0), "nodes should match");

        assertFalse(iterator.hasNext(), "iterator should be empty");

        // There should be no exceptions thrown by printing.
        printMismatchedNodes(mismatchedNodeIterator(stateA, stateB, deep), 1000);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Different Types")
    void differentTypes(final boolean deep) {
        final MerkleNode stateA = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(stateA);
        final MerkleNode stateB = buildLessSimpleTree();
        ((DummyMerkleInternal) stateB.getNodeAtRoute(1)).setChild(0, new MerkleLong(1234));
        TestMerkleCryptoFactory.getInstance().digestTreeSync(stateB);

        final Iterator<MismatchedNodes> iterator = mismatchedNodeIterator(stateA, stateB, deep);

        MismatchedNodes nodes = iterator.next();
        assertSame(nodes.nodeA(), stateA, "nodes should match");
        assertSame(nodes.nodeB(), stateB, "nodes should match");

        nodes = iterator.next();
        assertSame(nodes.nodeA(), stateA.getNodeAtRoute(1), "nodes should match");
        assertSame(nodes.nodeB(), stateB.getNodeAtRoute(1), "nodes should match");

        nodes = iterator.next();
        assertSame(nodes.nodeA(), stateA.getNodeAtRoute(1, 0), "nodes should match");
        assertSame(nodes.nodeB(), stateB.getNodeAtRoute(1, 0), "nodes should match");

        assertFalse(iterator.hasNext(), "iterator should be empty");

        // There should be no exceptions thrown by printing.
        printMismatchedNodes(mismatchedNodeIterator(stateA, stateB, deep), 1000);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Different Topologies")
    void differentTopologies(final boolean deep) {
        final MerkleNode stateA = buildLessSimpleTree();
        stateA.asInternal().setChild(0, null);
        TestMerkleCryptoFactory.getInstance().digestTreeSync(stateA);
        final MerkleNode stateB = buildLessSimpleTree();
        stateB.asInternal().setChild(1, null);
        TestMerkleCryptoFactory.getInstance().digestTreeSync(stateB);

        final Iterator<MismatchedNodes> iterator = mismatchedNodeIterator(stateA, stateB, deep);

        MismatchedNodes nodes = iterator.next();
        assertSame(nodes.nodeA(), stateA, "nodes should match");
        assertSame(nodes.nodeB(), stateB, "nodes should match");

        nodes = iterator.next();
        assertNull(nodes.nodeA(), "node should be null");
        assertSame(nodes.nodeB(), stateB.getNodeAtRoute(0), "nodes should match");

        nodes = iterator.next();
        assertSame(nodes.nodeA(), stateA.getNodeAtRoute(1), "nodes should match");
        assertNull(nodes.nodeB(), "node should be null");

        assertFalse(iterator.hasNext(), "iterator should be empty");

        // There should be no exceptions thrown by printing.
        printMismatchedNodes(mismatchedNodeIterator(stateA, stateB, deep), 1000);
        printMismatchedNodes(mismatchedNodeIterator(stateA, stateB, deep), 2);
        printMismatchedNodes(mismatchedNodeIterator(stateA, stateB, deep), 1);
        printMismatchedNodes(mismatchedNodeIterator(stateA, stateB, deep), 0);
    }

    @Test
    @DisplayName("Deep Comparison Required")
    void deepComparisonRequired() {
        final MerkleNode stateA = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(stateA);

        final MerkleNode stateB = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(stateB);

        // Change a leaf without rehashing the tree
        ((DummyMerkleLeaf) stateB.getNodeAtRoute(1, 1)).enableDuplicateHashing().setHash(randomHash());

        // Comparing with a shallow iterator should find no differences
        final Iterator<MismatchedNodes> shallowIterator = mismatchedNodeIterator(stateA, stateB, false);
        assertFalse(shallowIterator.hasNext(), "no nodes should have been detected");

        // Comparing with a deep iterator should find the divergence
        final Iterator<MismatchedNodes> deepIterator = mismatchedNodeIterator(stateA, stateB, true);

        assertTrue(deepIterator.hasNext(), "difference should have been found");

        // There should be no exceptions thrown by printing.
        printMismatchedNodes(shallowIterator, 1000);
        printMismatchedNodes(deepIterator, 1000);
    }
}

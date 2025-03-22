// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import static com.swirlds.common.merkle.copy.MerkleInitialize.initializeTreeAfterCopy;
import static com.swirlds.common.test.fixtures.io.ResourceLoader.loadLog4jContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.copy.MerkleCopy;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.utility.MerkleUtils;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyCustomReconnectRoot;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleInternal;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleLeaf;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleNode;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@DisplayName("Merkle Synchronization Tests")
public class MerkleSynchronizationTests {

    private final Configuration configuration = new TestConfigBuilder()
            // This is important! A low value will cause a failed reconnect to finish more quicly.
            .withValue("reconnect.asyncStreamTimeout", "5s")
            .withValue("reconnect.maxAckDelay", "1000ms")
            .getOrCreateConfig();

    private final ReconnectConfig reconnectConfig = configuration.getConfigData(ReconnectConfig.class);

    private final MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(configuration);

    @BeforeAll
    public static void startup() throws ConstructableRegistryException, FileNotFoundException {
        loadLog4jContext();
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("org.hiero.consensus");
    }

    /**
     * For each n^2 combination of trees, sync one tree to the other.
     */
    protected void testSynchronization(Supplier<List<MerkleNode>> treeListBuilder) throws Exception {
        final List<MerkleNode> listI = treeListBuilder.get();
        final List<MerkleNode> listJ = treeListBuilder.get();

        // Setup initial reference count for roots of tree
        listI.forEach((final MerkleNode root) -> {
            if (root != null) {
                root.reserve();
            }
        });
        listJ.forEach((final MerkleNode root) -> {
            if (root != null) {
                root.reserve();
            }
        });

        for (final MerkleNode nodeI : listI) {
            for (final MerkleNode nodeJ : listJ) {
                final MerkleNode result = MerkleTestUtils.hashAndTestSynchronization(nodeI, nodeJ, reconnectConfig);
                if (result != null && result != nodeI) {
                    result.release();
                }
            }
        }

        // Verify that none of the original trees have not been modified
        final List<MerkleNode> cleanList = treeListBuilder.get();
        for (int i = 0; i < cleanList.size(); i++) {
            assertTrue(
                    MerkleTestUtils.areTreesEqual(cleanList.get(i), listI.get(i)),
                    "ListI does not match the original tree");
            assertTrue(
                    MerkleTestUtils.areTreesEqual(cleanList.get(i), listJ.get(i)),
                    "ListJ does not match the original tree");
        }

        // Clean up reference counts
        listI.forEach((final MerkleNode root) -> {
            if (root != null) {
                root.release();
                root.forEachNode((final MerkleNode node) -> {
                    if (node != null) {
                        assertTrue(node.isDestroyed(), "all nodes should be destroyed");
                    }
                });
            }
        });
        listJ.forEach((final MerkleNode root) -> {
            if (root != null) {
                root.release();
                root.forEachNode((final MerkleNode node) -> {
                    if (node != null) {
                        assertTrue(node.isDestroyed(), "all nodes should be destroyed");
                    }
                });
            }
        });
    }

    /**
     * Generate a random tree and put a DummyCustomReconnectViewRoot node at the root.
     */
    private MerkleNode buildRandomTreeWithCustomRoot(final int seed, final int depth) {
        final MerkleInternal root = new DummyCustomReconnectRoot();
        root.rebuild();
        final MerkleNode randomTree = MerkleTestUtils.generateRandomBalancedTree(seed, depth, 2, 20, 0);
        MerkleCopy.adoptChildren(randomTree.asInternal(), root);
        randomTree.release();
        return root;
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Synchronization Tests")
    void synchronizationTests() throws Exception {

        final long seed = new Random().nextLong();
        System.out.println("seed = " + seed);

        final Supplier<List<MerkleNode>> builder = () -> {
            final Random random = new Random(seed);

            final List<MerkleNode> roots = new ArrayList<>(MerkleTestUtils.buildTreeList());

            // one custom view subtree
            final MerkleInternal tree0 = MerkleTestUtils.buildLessSimpleTreeExtended();
            tree0.setChild(3, buildRandomTreeWithCustomRoot(random.nextInt(), 2));
            roots.add(tree0);

            // one custom view subtree in a different location
            final MerkleInternal tree1 = MerkleTestUtils.buildLessSimpleTreeExtended();
            tree1.getChild(2)
                    .asInternal()
                    .getChild(1)
                    .asInternal()
                    .getChild(0)
                    .asInternal()
                    .setChild(2, buildRandomTreeWithCustomRoot(random.nextInt(), 3));
            roots.add(tree1);

            // two custom view subtrees
            final MerkleInternal tree2 = MerkleTestUtils.buildLessSimpleTreeExtended();
            tree2.setChild(3, buildRandomTreeWithCustomRoot(random.nextInt(), 4));
            tree2.getChild(2)
                    .asInternal()
                    .getChild(1)
                    .asInternal()
                    .getChild(0)
                    .asInternal()
                    .setChild(2, buildRandomTreeWithCustomRoot(random.nextInt(), 5));
            roots.add(tree2);

            return roots;
        };

        testSynchronization(builder);
    }

    /**
     * <pre>
     *        root
     *      / |  \ \
     *     A  I0 B I1
     *            / \
     *           C  I2
     * </pre>
     */
    protected DummyMerkleInternal buildTreeForVerifyResultIsATree() {
        final DummyMerkleLeaf A = new DummyMerkleLeaf("A");
        final DummyMerkleInternal I0 = new DummyMerkleInternal("I0");
        final DummyMerkleLeaf B = new DummyMerkleLeaf("B");
        final DummyMerkleLeaf C = new DummyMerkleLeaf("C");
        final DummyMerkleInternal I2 = new DummyMerkleInternal("I2");
        final DummyMerkleInternal I1 = new DummyMerkleInternal("I1");
        I1.setChild(0, C);
        I1.setChild(1, I2);
        final DummyMerkleInternal root = new DummyMerkleInternal("root");
        root.setChild(0, A);
        root.setChild(1, I0);
        root.setChild(2, B);
        root.setChild(3, I1);
        initializeTreeAfterCopy(root);

        return root;
    }

    /**
     * There was once a bug where the resulting merkle tree returned was not a DAG and not a tree. This test verifies
     * that the observed bug is no longer present.
     *
     * Starting tree
     * <pre>
     *                 root
     *               / |  \ \
     *              A  I0 B I1
     *                     / \
     *                    C  I2
     * </pre>
     *
     * Desired tree
     * <pre>
     *                root
     *              / |  \ \
     *             A  I0 B I1
     *                    / \
     *                   D  I2
     * </pre>
     * </p>
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Verify result is a tree")
    void verifyResultIsATree() throws Exception {
        final DummyMerkleInternal startingTree = buildTreeForVerifyResultIsATree();
        startingTree.reserve();

        final DummyMerkleInternal desiredTree = buildTreeForVerifyResultIsATree();
        desiredTree.reserve();
        ((DummyMerkleInternal) desiredTree.getChild(3)).setChild(0, new DummyMerkleLeaf("D"));

        MerkleTestUtils.hashAndTestSynchronization(startingTree, desiredTree, reconnectConfig);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Verify exception handling")
    void verifyExceptionHandling() {
        // Sending an un-hashed tree should result in an exception
        final DummyMerkleNode desiredTree = MerkleTestUtils.buildLessSimpleTree();
        desiredTree.reserve();
        assertThrows(
                MerkleSynchronizationException.class,
                () -> MerkleTestUtils.testSynchronization(null, desiredTree, reconnectConfig));
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Send Invalid Tree")
    void sendInvalidTree() throws Exception {
        // Modify a leaf value without re-hashing.
        final DummyMerkleNode root1 = MerkleTestUtils.buildLessSimpleTree();
        root1.reserve();
        merkleCryptography.digestTreeSync(root1);

        ((DummyMerkleLeaf) root1.asInternal().getChild(0)).setValue("this is not the hashed value");

        final MerkleNode newRoot1 = MerkleTestUtils.hashAndTestSynchronization(null, root1, reconnectConfig);
        final Hash resultingHash1 = newRoot1.getHash();
        assertNotEquals(root1.getHash(), resultingHash1, "we should not derive the same hash since data was changed");
        MerkleUtils.rehashTree(merkleCryptography, newRoot1);
        assertEquals(
                resultingHash1,
                newRoot1.getHash(),
                "hash reported should be a valid representation of data sent by teacher");

        // Modify an internal node without re-hashing.
        final DummyMerkleNode root2 = MerkleTestUtils.buildLessSimpleTree();
        root2.reserve();
        merkleCryptography.digestTreeSync(root2);

        Hash oldHash = root2.getHash();
        root2.asInternal().setChild(3, null);
        root2.setHash(oldHash);

        final MerkleNode newRoot2 = MerkleTestUtils.hashAndTestSynchronization(null, root2, reconnectConfig);
        final Hash resultingHash2 = newRoot2.getHash();
        assertNotEquals(root2.getHash(), resultingHash2, "we should not derive the same hash since data was changed");
        MerkleUtils.rehashTree(merkleCryptography, newRoot2);
        assertEquals(
                resultingHash2,
                newRoot2.getHash(),
                "hash reported should be a valid representation of data sent by teacher");
    }
}

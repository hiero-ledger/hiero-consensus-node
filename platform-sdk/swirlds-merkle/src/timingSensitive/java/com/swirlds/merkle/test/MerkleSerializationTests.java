// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import static com.swirlds.common.io.utility.FileUtils.deleteDirectory;
import static com.swirlds.common.test.fixtures.io.ResourceLoader.getFile;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.areTreesEqual;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.buildLessSimpleTree;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.buildLessSimpleTreeExtended;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.isFullyInitialized;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.io.streams.DebuggableMerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.route.MerkleRouteFactory;
import com.swirlds.common.merkle.utility.MerkleLong;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleInternal;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleLeaf;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleNode;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.exceptions.InvalidVersionException;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Merkle Serialization Tests")
class MerkleSerializationTests {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("org.hiero");
    }

    private void resetDirectory() throws IOException {
        deleteDirectory(testDirectory);
        Files.createDirectories(testDirectory);
    }

    /**
     * Serialize and deserialize a tree. Assert that the resulting tree matches the original tree.
     */
    private void assertSerializationValidity(final MerkleNode root) throws IOException {

        resetDirectory();

        final ByteArrayOutputStream baseStream = new ByteArrayOutputStream();
        final MerkleDataOutputStream outputStream = new MerkleDataOutputStream(baseStream);

        outputStream.writeMerkleTree(testDirectory, root);

        final MerkleDataInputStream inputStream =
                new MerkleDataInputStream(new ByteArrayInputStream(baseStream.toByteArray()));

        final DummyMerkleNode deserializedTree = inputStream.readMerkleTree(testDirectory, Integer.MAX_VALUE);

        if (root == null) {
            assertNull(deserializedTree, "tree should be null");
        } else {
            assertTrue(areTreesEqual(root, deserializedTree), "deserialized tree should match constructed tree");
            assertTrue(isFullyInitialized(deserializedTree), "tree should be fully initialized");
        }
    }

    /**
     * Serialize and deserialize a variety of merkle trees.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @Tag(TestComponentTags.IO)
    @DisplayName("Serialize Then Deserialize")
    void serializeThenDeserialize() throws IOException {
        final List<DummyMerkleNode> trees = MerkleTestUtils.buildTreeList();

        for (final DummyMerkleNode tree : trees) {
            assertSerializationValidity(tree);
        }
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @Tag(TestComponentTags.IO)
    @DisplayName("Test External Serialization")
    void testExternalSerialization() throws IOException {

        resetDirectory();

        DummyMerkleNode tree = MerkleTestUtils.buildTreeWithExternalData();

        // Serialize normally
        ByteArrayOutputStream baseStream = new ByteArrayOutputStream();
        MerkleDataOutputStream outputStream = new MerkleDataOutputStream(baseStream);

        outputStream.writeMerkleTree(testDirectory, tree);

        MerkleDataInputStream inputStream =
                new MerkleDataInputStream(new ByteArrayInputStream(baseStream.toByteArray()));
        final DummyMerkleNode deserialized = inputStream.readMerkleTree(testDirectory, Integer.MAX_VALUE);

        assertTrue(areTreesEqual(tree, deserialized), "tree should match generated");
        assertTrue(isFullyInitialized(deserialized), "tree should be initialized");
    }

    /**
     * Utility function that writes a tree to a file.
     */
    void writeTreeToFile(final MerkleNode tree, final String filePath) throws IOException {

        resetDirectory();

        final FileOutputStream baseStream = new FileOutputStream(filePath);
        final MerkleDataOutputStream outputStream = new MerkleDataOutputStream(baseStream);
        outputStream.writeProtocolVersion();
        outputStream.writeMerkleTree(testDirectory, tree);
        baseStream.close();
    }

    /**
     * Make sure that previously written merkle trees can be deserialized from file.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @Tag(TestComponentTags.IO)
    @DisplayName("Deserialize Tree From File")
    void deserializeTreeFromFile() throws IOException, URISyntaxException {
        // uncomment the following line to regenerate the file
        // writeTreeToFile(MerkleTestUtils.buildTreeWithExternalData(), "serialized-tree-v3.dat");

        final Path dir = getFile("merkle/serialized-tree-v3");

        final MerkleDataInputStream dataStream = new MerkleDataInputStream(
                getClass().getResourceAsStream("/merkle/serialized-tree-v3/serialized-tree-v3.dat"));
        dataStream.readProtocolVersion();
        final DummyMerkleNode tree = dataStream.readMerkleTree(dir, Integer.MAX_VALUE);
        assertTrue(
                areTreesEqual(MerkleTestUtils.buildTreeWithExternalData(), tree),
                "deserialized should match constructed");
        assertTrue(isFullyInitialized(tree), "tree should be initialized");
    }

    protected void assertDeserializationFailure(final MerkleNode tree) throws IOException {
        resetDirectory();

        final ByteArrayOutputStream baseStream = new ByteArrayOutputStream();
        final MerkleDataOutputStream outputStream = new MerkleDataOutputStream(baseStream);
        outputStream.writeMerkleTree(testDirectory, tree);

        final MerkleDataInputStream inputStream =
                new MerkleDataInputStream(new ByteArrayInputStream(baseStream.toByteArray()));

        assertThrows(
                InvalidVersionException.class,
                () -> inputStream.readMerkleTree(testDirectory, Integer.MAX_VALUE),
                "expected error during deserialization");
    }

    /**
     * Attempt to deserialize versions that are not supported.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Deserialize Invalid Trees")
    void deserializeInvalidTrees() throws IOException {
        final List<MerkleNode> trees = new LinkedList<>();

        // Too low of a version on a leaf
        DummyMerkleInternal root = buildLessSimpleTree();
        ((DummyMerkleLeaf) root.asInternal().getChild(0)).setVersion(0);
        trees.add(root);

        // Too high of a version on a leaf
        root = buildLessSimpleTree();
        ((DummyMerkleLeaf) root.asInternal().getChild(0)).setVersion(1234);
        trees.add(root);

        // Too low of a version on an internal node
        root = buildLessSimpleTree();
        root.setVersion(0);
        trees.add(root);

        // Too high of a version on an internal node
        root = buildLessSimpleTree();
        root.setVersion(1234);
        trees.add(root);

        for (final MerkleNode tree : trees) {
            assertDeserializationFailure(tree);
        }
    }

    /**
     * This test asserts that the hash of a tree does not change due to future code changes.
     * <p>
     * Although there is no contractual requirement not to change the hash between versions, we should at least be aware
     * if a change occurs.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Test Hash From File")
    void testHashFromFile() throws IOException {
        //		writeTreeToFile(MerkleTestUtils.buildLessSimpleTree(), "hashed-tree-merkle-v1.dat");
        final DataInputStream dataStream =
                new DataInputStream(getClass().getResourceAsStream("/merkle/hashed-tree-merkle-v1.dat"));

        final Hash oldHash = new Hash(dataStream.readAllBytes(), DigestType.SHA_384);
        final MerkleNode tree = buildLessSimpleTree();

        assertEquals(
                oldHash,
                TestMerkleCryptoFactory.getInstance().digestTreeSync(tree),
                "deserialized hash should match computed hash");
    }

    @Test
    void nodeMigrationTest() throws IOException {
        final MerkleInternal originalRoot = buildLessSimpleTreeExtended();

        resetDirectory();

        DummyMerkleLeaf.setMigrationMapper((final DummyMerkleLeaf node, final Integer version) -> {
            assertEquals(DummyMerkleLeaf.CLASS_VERSION, version, "unexpected version");

            if (node.getRoute().equals(MerkleRouteFactory.buildRoute(0))) {
                // Replace leaf node A with a different leaf node
                return new MerkleLong(1234);
            }

            if (node.getRoute().equals(MerkleRouteFactory.buildRoute(2, 0))) {
                // Replace leaf node D with an internal node
                final MerkleInternal internal = new DummyMerkleInternal();
                internal.setChild(0, new DummyMerkleLeaf("D"));
                internal.rebuild();
                return internal;
            }

            if (node.getRoute().equals(MerkleRouteFactory.buildRoute(2, 1, 1))) {
                // Replace leaf node E with null
                return null;
            }

            // Don't mess with other nodes
            return node;
        });

        DummyMerkleInternal.setMigrationMapper((final DummyMerkleInternal node, final Integer version) -> {
            assertEquals(DummyMerkleInternal.CLASS_VERSION, version, "unexpected version");
            assertTrue(node.isInitialized(), "node should be initialized");

            if (node.getRoute().equals(MerkleRouteFactory.buildRoute(1))) {
                // Replace internal node i0 with a different internal node
                final MerkleInternal internal = new DummyMerkleInternal();
                internal.setChild(0, new DummyMerkleLeaf("foo"));
                internal.setChild(1, new DummyMerkleLeaf("bar"));
                internal.rebuild();
                return internal;
            }

            if (node.getRoute().equals(MerkleRouteFactory.buildRoute(2, 1, 0))) {
                // Replace internal node i3 with a leaf node
                return new MerkleLong(4321);
            }

            // Don't mess with other nodes
            return node;
        });

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final MerkleDataOutputStream out = new MerkleDataOutputStream(byteOut);

        out.writeMerkleTree(testDirectory, originalRoot);
        out.flush();

        final MerkleDataInputStream in =
                new DebuggableMerkleDataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));

        final MerkleInternal deserializedRoot = in.readMerkleTree(testDirectory, Integer.MAX_VALUE);

        // The root is untouched
        assertTrue(deserializedRoot instanceof DummyMerkleInternal, "incorrect node type");
        assertEquals(3, deserializedRoot.getNumberOfChildren(), "incorrect child count");

        // Node i1 is untouched
        assertTrue(deserializedRoot.getNodeAtRoute(2) instanceof DummyMerkleInternal, "incorrect node type");
        assertEquals(2, deserializedRoot.getNodeAtRoute(2).asInternal().getNumberOfChildren(), "incorrect child count");

        // Node i2 is untouched
        assertTrue(deserializedRoot.getNodeAtRoute(2, 1) instanceof DummyMerkleInternal, "incorrect node type");
        assertEquals(
                2, deserializedRoot.getNodeAtRoute(2, 1).asInternal().getNumberOfChildren(), "incorrect child count");

        // Node A was replaced
        final MerkleNode nodeReplacingA = deserializedRoot.getNodeAtRoute(0);
        assertTrue(nodeReplacingA instanceof MerkleLong, "incorrect node type");
        assertEquals(1234, ((MerkleLong) nodeReplacingA).getValue(), "unexpected value");

        // Node D was replaced
        final MerkleNode nodeReplacingD = deserializedRoot.getNodeAtRoute(2, 0);
        assertTrue(nodeReplacingD instanceof DummyMerkleInternal, "incorrect node type");
        assertEquals(1, nodeReplacingD.asInternal().getNumberOfChildren());
        assertTrue(nodeReplacingD.asInternal().getChild(0) instanceof DummyMerkleLeaf, "incorrect node type");

        // Node E was replaced
        assertNull(deserializedRoot.getNodeAtRoute(2, 1, 1));

        // Node i0 was replaced
        final MerkleNode nodeReplacingI0 = deserializedRoot.getNodeAtRoute(1);
        assertTrue(nodeReplacingI0 instanceof DummyMerkleInternal, "incorrect node type");
        assertEquals(2, nodeReplacingI0.asInternal().getNumberOfChildren());
        assertEquals(
                "foo", ((DummyMerkleLeaf) nodeReplacingI0.asInternal().getChild(0)).getValue(), "unexpected value");
        assertEquals(
                "bar", ((DummyMerkleLeaf) nodeReplacingI0.asInternal().getChild(1)).getValue(), "unexpected value");

        // Node i3 was replaced
        final MerkleNode nodeReplacingI3 = deserializedRoot.getNodeAtRoute(2, 1, 0);
        assertTrue(nodeReplacingI3 instanceof MerkleLong, "incorrect node type");
        assertEquals(4321, ((MerkleLong) nodeReplacingI3).getValue(), "unexpected value");

        deserializedRoot.forEachNode((final MerkleNode node) -> {
            if (node instanceof MerkleInternal) {
                assertTrue(((DummyMerkleInternal) node).isInitialized(), "node should have been initialized");
            }
        });

        // Basic sanity checks
        deserializedRoot.forEachNode((final MerkleNode node) -> {
            assertFalse(node.isDestroyed(), "node should not be destroyed");
            if (node.getDepth() == 0) {
                assertEquals(0, node.getReservationCount(), "root should have no references");
            } else {
                assertEquals(1, node.getReservationCount(), "node should have exactly one reference");
            }
            if (node instanceof DummyMerkleInternal) {
                assertTrue(((DummyMerkleInternal) node).isInitialized(), "node should be initialized");
            }
            assertTrue(node.isMutable(), "node should be mutable");
            assertSame(node, deserializedRoot.getNodeAtRoute(node.getRoute()), "node has invalid route");
        });

        DummyMerkleLeaf.resetMigrationMapper();
        DummyMerkleInternal.resetMigrationMapper();
    }

    @Test
    void migrateRootTest() throws IOException {

        // In this test, we will attempt to swap out originalRoot with newRoot

        final MerkleNode originalRoot = buildLessSimpleTreeExtended();
        final MerkleNode newRoot = buildLessSimpleTree();

        final AtomicReference<MerkleNode> deserializedRootBeforeMigration = new AtomicReference<>();
        DummyMerkleInternal.setMigrationMapper((node, version) -> {
            if (node.getDepth() == 0) {
                assertNull(deserializedRootBeforeMigration.get(), "deserialized root should not have been set yet");
                deserializedRootBeforeMigration.set(node);
                return newRoot;
            }
            return node;
        });

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final MerkleDataOutputStream out = new MerkleDataOutputStream(byteOut);

        out.writeMerkleTree(testDirectory, originalRoot);
        out.flush();

        final MerkleDataInputStream in =
                new DebuggableMerkleDataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));

        final MerkleInternal deserializedRoot = in.readMerkleTree(testDirectory, Integer.MAX_VALUE);

        assertTrue(areTreesEqual(newRoot, deserializedRoot), "deserialized tree should match new root");
        assertNotNull(deserializedRootBeforeMigration.get(), "deserialized root should have been set");
        assertTrue(deserializedRootBeforeMigration.get().isDestroyed(), "deserialized root should have been destroyed");

        DummyMerkleInternal.resetMigrationMapper();
    }

    /**
     * Disabled on Windows because it does not support changing a file or directory's readability and write-ability.
     *
     * @throws IOException
     */
    @DisabledOnOs(OS.WINDOWS)
    @Test
    @DisplayName("Directory Validation Test")
    @SuppressWarnings("ResultOfMethodCallIgnored")
    void directoryValidationTest() throws IOException {

        resetDirectory();

        final MerkleDataInputStream in = new MerkleDataInputStream(new ByteArrayInputStream(new byte[0]));
        final MerkleDataOutputStream out = new MerkleDataOutputStream(new ByteArrayOutputStream());

        assertThrows(
                NullPointerException.class, () -> in.readMerkleTree(null, 0), "null directory should not be permitted");
        assertThrows(
                IllegalArgumentException.class,
                () -> out.writeMerkleTree(null, null),
                "null directory should not be permitted");

        final Path nonExistentDirectory = new File("if/this/actually/exists/I/will/eat/my/hat").toPath();
        assertThrows(
                IllegalArgumentException.class,
                () -> in.readMerkleTree(nonExistentDirectory, 0),
                "directory must exist");
        assertThrows(
                IllegalArgumentException.class,
                () -> out.writeMerkleTree(nonExistentDirectory, null),
                "directory must exist");

        final Path notADirectory = testDirectory.resolve("notADirectory.txt");
        final SerializableDataOutputStream fOut =
                new SerializableDataOutputStream(new FileOutputStream(notADirectory.toFile()));
        fOut.writeNormalisedString("this is not a directory");
        fOut.close();
        assertThrows(
                IllegalArgumentException.class,
                () -> in.readMerkleTree(notADirectory, 0),
                "must be an actual directory");
        assertThrows(
                IllegalArgumentException.class,
                () -> out.writeMerkleTree(notADirectory, null),
                "must be an actual directory");

        final Path writeProtectedDirectory = testDirectory.resolve("writeProtected");
        Files.createDirectories(writeProtectedDirectory);
        writeProtectedDirectory.toFile().setWritable(false);
        assertThrows(
                EOFException.class,
                () -> in.readMerkleTree(writeProtectedDirectory, 0),
                "should pass directory validation and fail later in the operation, write permission not needed");
        assertThrows(
                IllegalArgumentException.class,
                () -> out.writeMerkleTree(writeProtectedDirectory, null),
                "must have write permissions");
        writeProtectedDirectory.toFile().setWritable(true);

        final Path readProtectedDirectory = testDirectory.resolve("readProtected");
        Files.createDirectories(readProtectedDirectory);
        readProtectedDirectory.toFile().setReadable(false);
        assertThrows(
                IllegalArgumentException.class,
                () -> in.readMerkleTree(readProtectedDirectory, 0),
                "must have read permissions");
        assertThrows(
                IllegalArgumentException.class,
                () -> out.writeMerkleTree(readProtectedDirectory, null),
                "must have read permissions");

        // Reset to true so the directory can be deleted between tests
        readProtectedDirectory.toFile().setReadable(true);
    }
}

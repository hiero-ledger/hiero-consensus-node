// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test;

import static com.swirlds.common.io.streams.StreamDebugUtils.deserializeAndDebugOnFailure;
import static com.swirlds.common.io.streams.internal.SerializationOperation.READ_BYTE_ARRAY;
import static com.swirlds.common.io.streams.internal.SerializationOperation.READ_INSTANT;
import static com.swirlds.common.io.streams.internal.SerializationOperation.READ_INT;
import static com.swirlds.common.io.streams.internal.SerializationOperation.READ_LONG;
import static com.swirlds.common.io.streams.internal.SerializationOperation.READ_MERKLE_NODE;
import static com.swirlds.common.io.streams.internal.SerializationOperation.READ_MERKLE_TREE;
import static com.swirlds.common.io.streams.internal.SerializationOperation.READ_SERIALIZABLE;
import static com.swirlds.common.io.streams.internal.SerializationOperation.READ_SERIALIZABLE_LIST;
import static com.swirlds.common.io.streams.internal.SerializationOperation.STREAM_OPENED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.io.streams.DebuggableMerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.common.io.streams.internal.SerializationStack;
import com.swirlds.common.io.streams.internal.SerializationStackElement;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.common.merkle.utility.MerkleLong;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.tree.MerkleBinaryTree;
import com.swirlds.merkle.tree.MerkleTreeInternalNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Serialization Debug Test")
class SerializationDebugTest {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @BeforeAll
    static void startUp() throws ConstructableRegistryException {
        new TestConfigBuilder().getOrCreateConfig();

        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds");
        registry.registerConstructable(new ClassConstructorPair(MerkleMap.class, MerkleMap::new));
        registry.registerConstructable(new ClassConstructorPair(MerkleBinaryTree.class, MerkleBinaryTree::new));
        registry.registerConstructable(
                new ClassConstructorPair(MerkleTreeInternalNode.class, MerkleTreeInternalNode::new));
        registry.registerConstructable(new ClassConstructorPair(ExplodingValue.class, ExplodingValue::new));
    }

    /**
     * A value for a merkle map that can be configured to throw an error upon deserialization.
     */
    private static class ExplodingValue extends PartialMerkleLeaf implements Keyed<Integer>, MerkleLeaf {

        private static final long CLASS_ID = 0x50ee45f4ec87aa6eL;

        private int key;
        private long seed;

        private static int explodeAfter = 50;

        public ExplodingValue() {}

        public ExplodingValue(final long seed) {
            this.seed = seed;
        }

        private ExplodingValue(final ExplodingValue that) {
            this.key = that.key;
            this.seed = that.seed;
        }

        public static void setExplodeAfter(final int explodeAfter) {
            ExplodingValue.explodeAfter = explodeAfter;
        }

        @Override
        public Integer getKey() {
            return key;
        }

        @Override
        public void setKey(final Integer key) {
            this.key = key;
        }

        @Override
        public long getClassId() {
            return CLASS_ID;
        }

        @Override
        public void serialize(final SerializableDataOutputStream out) throws IOException {
            final Random random = new Random(seed);

            out.writeSerializable(new MerkleLong(key), true);
            out.writeSerializable(new MerkleLong(seed), true);

            out.writeInt(random.nextInt());
            out.writeLong(random.nextLong());
            final byte[] bytes = new byte[100];
            random.nextBytes(bytes);
            out.writeByteArray(bytes);
            out.writeInstant(Instant.ofEpochSecond(random.nextInt()));

            out.writeSerializableList(
                    List.of(new MerkleLong(random.nextInt()), new MerkleLong(random.nextInt())), true, false);
        }

        @Override
        public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
            key = (int) ((MerkleLong) in.readSerializable()).getValue();
            seed = (int) ((MerkleLong) in.readSerializable()).getValue();

            final Random random = new Random(seed);

            assertEquals(in.readInt(), random.nextInt(), "value should match generated value");
            assertEquals(in.readLong(), random.nextLong(), "value should match generated value");

            final byte[] bytes = new byte[100];
            random.nextBytes(bytes);
            assertArrayEquals(bytes, in.readByteArray(Integer.MAX_VALUE), "value should match generated value");

            final Instant instant = in.readInstant();
            assertEquals(Instant.ofEpochSecond(random.nextInt()), instant, "value should match generated value");

            final List<MerkleLong> values = in.readSerializableList(Integer.MAX_VALUE);
            final List<MerkleLong> expectedValues =
                    List.of(new MerkleLong(random.nextInt()), new MerkleLong(random.nextInt()));
            assertEquals(expectedValues.size(), values.size(), "lists should have the same size");
            for (int i = 0; i < expectedValues.size(); i++) {
                assertEquals(expectedValues.get(i), values.get(i), "value should match");
            }

            explodeAfter--;
            if (explodeAfter == 0) {
                throw new RuntimeException("intentional exception");
            }
        }

        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public ExplodingValue copy() {
            return new ExplodingValue(this);
        }
    }

    @Test
    @DisplayName("MerkleMap Test")
    void merkleMapTest() throws IOException {

        final MerkleMap<Integer, ExplodingValue> map = new MerkleMap<>();

        for (int i = 0; i < 100; i++) {
            map.put(i, new ExplodingValue(i));
        }

        final InputOutputStream streams = new InputOutputStream();
        streams.getOutput().writeMerkleTree(testDirectory, map);
        streams.startReading(false, true);

        ExplodingValue.setExplodeAfter(50);

        assertThrows(
                RuntimeException.class,
                () -> streams.getInput().readMerkleTree(testDirectory, Integer.MAX_VALUE),
                "expected deserialization to fail");

        final DebuggableMerkleDataInputStream debug = (DebuggableMerkleDataInputStream) streams.getInput();

        System.out.println(debug.getFormattedStackTrace());

        final SerializationStack stack = debug.getStack();
        final Deque<SerializationStackElement> elements = stack.getInternalStack();

        assertEquals(1, elements.size(), "there should be exactly one top level element");
        final SerializationStackElement opened = elements.getFirst();
        assertEquals(STREAM_OPENED, opened.getOperation(), "unexpected operation");
        assertEquals(1, opened.getTotalChildCount(), "opened element should have one child");

        final SerializationStackElement readMerkleTree = opened.getChildren().getFirst();
        assertEquals(READ_MERKLE_TREE, readMerkleTree.getOperation(), "unexpected operation");
        assertTrue(readMerkleTree.getTotalChildCount() > 100, "element should have many children");
        assertEquals(
                SerializationStackElement.MAX_CHILD_COUNT,
                readMerkleTree.getCurrentChildCount(),
                "current number of children should not exceed maximum");

        int merkleIndex = 0;
        for (final SerializationStackElement child : readMerkleTree.getChildren()) {
            assertEquals(READ_MERKLE_NODE, child.getOperation(), "unexpected operation");
            assertEquals(ExplodingValue.CLASS_ID, child.getClassId(), "unexpected class ID");
            assertEquals(ExplodingValue.class, child.getClazz(), "unexpected class");
            // Class ID, version, plus 7 things serialized = 9 total
            assertEquals(9, child.getTotalChildCount(), "unexpected child count");

            if (merkleIndex == SerializationStackElement.MAX_CHILD_COUNT - 1) {
                // The last child
                assertTrue(
                        child.getCurrentChildCount() <= SerializationStackElement.MAX_CHILD_COUNT, "too many children");

                final Iterator<SerializationStackElement> it =
                        child.getChildren().iterator();
                assertEquals(READ_LONG, it.next().getOperation(), "unexpected operation");
                assertEquals(READ_INT, it.next().getOperation(), "unexpected operation");
                assertEquals(READ_SERIALIZABLE, it.next().getOperation(), "unexpected operation");
                assertEquals(READ_SERIALIZABLE, it.next().getOperation(), "unexpected operation");
                assertEquals(READ_INT, it.next().getOperation(), "unexpected operation");
                assertEquals(READ_LONG, it.next().getOperation(), "unexpected operation");
                assertEquals(READ_BYTE_ARRAY, it.next().getOperation(), "unexpected operation");
                assertEquals(READ_INSTANT, it.next().getOperation(), "unexpected operation");
                assertEquals(READ_SERIALIZABLE_LIST, it.next().getOperation(), "unexpected operation");

            } else if (merkleIndex == SerializationStackElement.MAX_CHILD_COUNT - 2) {
                // The second to last child
                assertTrue(
                        child.getCurrentChildCount() <= SerializationStackElement.SECOND_TO_LAST_MAX_CHILD_COUNT,
                        "too many children");
            } else {
                assertEquals(0, child.getCurrentChildCount(), "element is not permitted to have children");
            }

            merkleIndex++;
        }

        map.release();
    }

    @Test
    @DisplayName("Debug On Failure Test")
    @Disabled("This test needs to be investigated")
    void debugOnFailureTest() {

        final MerkleMap<Integer, ExplodingValue> map = new MerkleMap<>();
        for (int i = 0; i < 100; i++) {
            map.put(i, new ExplodingValue(i));
        }

        final AtomicBoolean failed = new AtomicBoolean(false);
        boolean exception = false;

        try {
            deserializeAndDebugOnFailure(
                    () -> {
                        final InputOutputStream streams = new InputOutputStream();
                        streams.getOutput().writeMerkleTree(testDirectory, map);
                        streams.startReading(false, true);

                        ExplodingValue.setExplodeAfter(50);

                        return streams.getInput();
                    },
                    (final MerkleDataInputStream inputStream) ->
                            inputStream.readMerkleTree(testDirectory, Integer.MAX_VALUE));
        } catch (final Exception ex) {
            assertEquals(RuntimeException.class, ex.getClass(), "proper exception should have been thrown");
            assertEquals("intentional exception", ex.getMessage(), "proper exception should have been thrown");

            assertEquals(0, ex.getSuppressed().length, "should be no suppressed exceptions");
            exception = true;
        }

        assertTrue(failed.get(), "serialization callback should have been invoked");
        assertTrue(exception, "exception should have been thrown");

        map.release();
    }
}
